package com.github.lavrov.bittorrent.wire

import cats._
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.syntax.all._
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import fs2.io.tcp.SocketGroup
import logstage.LogIO
import monocle.Lens
import monocle.macros.GenLens
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet
import scala.concurrent.duration._

trait Connection[F[_]] {
  def info: PeerInfo
  def extensionProtocol: Boolean
  def interested: F[Unit]
  def request(request: Message.Request): F[ByteVector]
  def chokedStatus: Signal[F, Boolean]
  def availability: Signal[F, BitSet]
  def disconnected: F[Either[Throwable, Unit]]
}

object Connection {

  case class State(lastMessageAt: Long = 0, interested: Boolean = false)
  object State {
    val lastMessageAt: Lens[State, Long] = GenLens[State](_.lastMessageAt)
    val interested: Lens[State, Boolean] = GenLens[State](_.interested)
  }

  trait RequestRegistry[F[_]] {
    def register(request: Message.Request): F[ByteVector]
    def complete(request: Message.Request, bytes: ByteVector): F[Unit]
    def failAll(t: Throwable): F[Unit]
  }
  object RequestRegistry {
    def apply[F[_]: Concurrent]: F[RequestRegistry[F]] =
      for {
        stateRef <- Ref.of(
          Map.empty[Message.Request, Either[Throwable, ByteVector] => F[Unit]]
        )
      } yield new RequestRegistry[F] {

        def register(request: Message.Request): F[ByteVector] = {
          for {
            deferred <- Deferred[F, Either[Throwable, ByteVector]]
            _ <- stateRef.update(_.updated(request, deferred.complete))
            result <- Concurrent[F].guarantee(deferred.get)(
              stateRef.update(_ - request)
            )
            result <- Concurrent[F].fromEither(result)
          } yield result
        }

        def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
          for {
            callback <- stateRef.get.map(_.get(request))
            _ <- callback.traverse(cb => cb(bytes.asRight))
          } yield ()

        def failAll(t: Throwable): F[Unit] =
          for {
            state <- stateRef.get
            _ <- state.values.toList.traverse { cb =>
              cb(t.asLeft)
            }
          } yield ()
      }
  }

  def connect[F[_]](selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(
    implicit F: Concurrent[F],
    cs: ContextShift[F],
    timer: Timer[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): F[Connection[F]] = {
    for {
      stateRef <- Ref.of[F, State](State())
      chokedStatusRef <- SignallingRef(true)
      bitfieldRef <- SignallingRef(BitSet.empty)
      requestRegistry <- RequestRegistry[F]
      result <- MessageSocket.connect(selfId, peerInfo, infoHash).allocated
      (connection, releaseConnection) = result
      _ <- logger.info(s"Connected ${peerInfo.address}")
      cleanUp = requestRegistry.failAll(ConnectionClosed()) >> releaseConnection >> logger
        .info(s"Disconnected ${peerInfo.address}")
      updateLastMessageTime = (l: Long) => stateRef.update(State.lastMessageAt.set(l))
      fiber <- Concurrent[F]
        .race[Nothing, Nothing](
          receiveLoop(
            requestRegistry,
            bitfieldRef.set,
            chokedStatusRef.set,
            updateLastMessageTime,
            connection
          ),
          backgroundLoop(stateRef, timer, connection)
        )
        .attempt
        .flatTap(_ => cleanUp)
        .void
        .start
    } yield {
      new Connection[F] {
        def info: PeerInfo = peerInfo
        def extensionProtocol: Boolean = connection.handshake.extensionProtocol

        def interested: F[Unit] =
          for {
            interested <- stateRef.modify(
              s => (State.interested.set(true)(s), s.interested)
            )
            _ <- F.whenA(!interested)(connection.send(Message.Interested))
          } yield ()

        def request(request: Message.Request): F[ByteVector] =
          connection.send(request) >> requestRegistry
            .register(request)
            .timeout(10.seconds)

        def chokedStatus: Signal[F, Boolean] = chokedStatusRef

        def availability: Signal[F, BitSet] = bitfieldRef

        def disconnected: F[Either[Throwable, Unit]] = fiber.join.attempt
      }
    }
  }

  case class ConnectionClosed() extends Throwable

  private def receiveLoop[F[_]: Monad](
    requestRegistry: RequestRegistry[F],
    updateBitfield: BitSet => F[Unit],
    updateChokeStatus: Boolean => F[Unit],
    updateLastMessageAt: Long => F[Unit],
    socket: MessageSocket[F]
  )(implicit timer: Timer[F]): F[Nothing] =
    socket.receive
      .flatMap {
        case Message.Unchoke =>
          updateChokeStatus(false)
        case Message.Choke =>
          updateChokeStatus(true)
        case Message.Piece(index: Long, begin: Long, bytes: ByteVector) =>
          val request = Message.Request(index, begin, bytes.length)
          requestRegistry.complete(request, bytes)
        case Message.Bitfield(bytes) =>
          val indices = bytes.toBitVector.toIndexedSeq.zipWithIndex.collect {
            case (true, i) => i
          }
          updateBitfield(BitSet(indices: _*))
        case _ =>
          Monad[F].unit
      }
      .flatTap { _ =>
        timer.clock.monotonic(MILLISECONDS).flatMap { currentTime =>
          updateLastMessageAt(currentTime)
        }
      }
      .foreverM

  private def backgroundLoop[F[_]](
    stateRef: Ref[F, State],
    timer: Timer[F],
    socket: MessageSocket[F]
  )(implicit F: MonadError[F, Throwable]): F[Nothing] =
    timer
      .sleep(10.seconds)
      .flatMap { _ =>
        for {
          currentTime <- timer.clock.monotonic(MILLISECONDS)
          timedOut <- stateRef.get.map(
            s => (currentTime - s.lastMessageAt).millis > 1.minute
          )
          _ <- F.whenA(timedOut) {
            F.raiseError(Error("Connection timed out"))
          }
          _ <- socket.send(Message.KeepAlive)
        } yield ()
      }
      .foreverM

  case class Error(message: String) extends Exception(message)
}