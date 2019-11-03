package com.github.lavrov.bittorrent.wire

import cats._
import cats.implicits._
import cats.effect.syntax.all._
import cats.effect.{Concurrent, ContextShift, Sync, Timer}
import cats.effect.concurrent.Ref
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.github.lavrov.bittorrent.protocol.message.Message
import scodec.bits.{BitVector, ByteVector}
import fs2.Stream
import fs2.concurrent.Topic
import fs2.io.tcp.SocketGroup
import logstage.LogIO
import monocle.Lens
import monocle.macros.GenLens

import scala.concurrent.duration._

trait Connection[F[_]] {
  def info: PeerInfo
  def extensionProtocol: Boolean
  def interested: F[Unit]
  def request(request: Message.Request): F[ByteVector]
  def chokeStatus: Stream[F, Boolean]
  def disconnected: F[Either[Throwable, Unit]]
}

object Connection {

  case class State(
    lastMessageAt: Long = 0,
    interested: Boolean = false,
    peerChoking: Boolean = true,
    bitfield: Option[BitVector] = None
  )
  object State {
    val lastMessageAt: Lens[State, Long] = GenLens[State](_.lastMessageAt)
    val interested: Lens[State, Boolean] = GenLens[State](_.interested)
    val peerChoking: Lens[State, Boolean] = GenLens[State](_.peerChoking)
    val bitfield: Lens[State, Option[BitVector]] = GenLens[State](_.bitfield)
  }

  trait RequestRegistry[F[_]] {
    def register(request: Message.Request): F[ByteVector]
    def complete(request: Message.Request, bytes: ByteVector): F[Unit]
    def completeAll(t: Throwable): F[Unit]
  }
  object RequestRegistry {
    def apply[F[_]: Concurrent]: F[RequestRegistry[F]] =
      for {
        stateRef <- Ref.of(Map.empty[Message.Request, Either[Throwable, ByteVector] => F[Unit]])
      } yield new RequestRegistry[F] {
        def register(request: Message.Request): F[ByteVector] = Concurrent.cancelableF { cb =>
          val callback: Either[Throwable, ByteVector] => F[Unit] = r => Sync[F].delay { cb(r) }
          for {
            _ <- stateRef.update(_.updated(request, callback))
          } yield stateRef.update(_ - request)
        }

        def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
          for {
            callback <- stateRef.get.map(_.get(request))
            _ <- callback.traverse(cb => cb(bytes.asRight))
          } yield ()

        def completeAll(t: Throwable): F[Unit] =
          for {
            state <- stateRef.modify(s => (Map.empty, s))
            _ <- state.values.toList.traverse { cb =>
              cb(t.asLeft)
            }
          } yield ()
      }
  }

  def connect[F[_]](
    selfId: PeerId,
    peerInfo: PeerInfo,
    infoHash: InfoHash
  )(
    implicit F: Concurrent[F],
    cs: ContextShift[F],
    timer: Timer[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): F[Connection[F]] = {
    for {
      stateRef <- Ref.of[F, State](State())
      chokedStatusTopic <- Topic(true)
      requestRegistry <- RequestRegistry[F]
      result <- MessageSocket.connect(selfId, peerInfo, infoHash).allocated
      (connection, releaseConnection) = result
      cleanUp = requestRegistry.completeAll(ConnectionClosed()) >> releaseConnection
      fiber <- Concurrent[F]
        .race[Nothing, Nothing](
          receiveLoop(stateRef, requestRegistry, chokedStatusTopic.publish1, connection, timer),
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
            interested <- stateRef.modify(s => (State.interested.set(true)(s), s.interested))
            _ <- F.whenA(!interested)(
              connection.send(Message.Interested)
            )
          } yield ()

        def request(request: Message.Request): F[ByteVector] =
          connection.send(request) >> requestRegistry.register(request)

        def chokeStatus: Stream[F, Boolean] = chokedStatusTopic.subscribe(1)

        def disconnected: F[Either[Throwable, Unit]] = fiber.join.attempt
      }
    }
  }

  case class ConnectionClosed() extends Throwable

  private def receiveLoop[F[_]: Monad](
    stateRef: Ref[F, State],
    requestRegistry: RequestRegistry[F],
    updateChokeStatus: Boolean => F[Unit],
    socket: MessageSocket[F],
    timer: Timer[F]
  ): F[Nothing] =
    socket.receive
      .flatMap {
        case Message.Unchoke =>
          stateRef.update(State.peerChoking.set(false)) >> updateChokeStatus(false)
        case Message.Choke =>
          stateRef.update(State.peerChoking.set(true)) >> updateChokeStatus(true)
        case Message.Piece(index: Long, begin: Long, bytes: ByteVector) =>
          val request = Message.Request(index, begin, bytes.length)
          requestRegistry.complete(request, bytes)
        case Message.Bitfield(bytes) =>
          stateRef.update(State.bitfield.set(bytes.toBitVector.some))
        case _ =>
          Monad[F].unit
      }
      .flatTap { _ =>
        timer.clock.monotonic(MILLISECONDS).flatMap { currentTime =>
          stateRef.update(State.lastMessageAt.set(currentTime))
        }
      }
      .foreverM

  private def backgroundLoop[F[_]](
    stateRef: Ref[F, State],
    timer: Timer[F],
    socket: MessageSocket[F]
  )(
    implicit F: MonadError[F, Throwable]
  ): F[Nothing] =
    timer
      .sleep(10.seconds)
      .flatMap { _ =>
        for {
          currentTime <- timer.clock.monotonic(MILLISECONDS)
          timedOut <- stateRef.get.map(s => (currentTime - s.lastMessageAt).millis > 30.seconds)
          _ <- F.whenA(timedOut) {
            F.raiseError(Error("Connection timed out"))
          }
          _ <- socket.send(Message.KeepAlive)
        } yield ()
      }
      .foreverM

  case class Error(message: String) extends Exception(message)
}
