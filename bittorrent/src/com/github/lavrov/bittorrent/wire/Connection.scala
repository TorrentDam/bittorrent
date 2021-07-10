package com.github.lavrov.bittorrent.wire

import cats.*
import cats.implicits.*
import cats.effect.kernel.{Clock, Deferred, Ref, Temporal}
import cats.effect.syntax.all.*
import cats.effect.{Async, Concurrent, Resource}
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.wire.ExtensionHandler.ExtensionApi
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo, TorrentMetadata}
import fs2.concurrent.{Signal, SignallingRef}
import fs2.io.net.SocketGroup
import org.typelevel.log4cats.Logger
import monocle.Lens
import monocle.macros.GenLens
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet
import scala.concurrent.duration.*

trait Connection[F[_]] {
  def info: PeerInfo
  def extensionProtocol: Boolean
  def interested: F[Unit]
  def request(request: Message.Request): F[ByteVector]
  def choked: Signal[F, Boolean]
  def availability: Signal[F, BitSet]
  def disconnected: F[Either[Throwable, Unit]]
  def close: F[Unit]
  def extensionApi: F[ExtensionApi[F]]
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
      for
        stateRef <- Ref.of(
          Map.empty[Message.Request, Either[Throwable, ByteVector] => F[Unit]]
        )
      yield new RequestRegistry[F] {

        def register(request: Message.Request): F[ByteVector] = {
          for
            deferred <- Deferred[F, Either[Throwable, ByteVector]]
            _ <- stateRef.update(_.updated(request, deferred.complete(_).void))
            result <- deferred.get.guarantee(
              stateRef.update(_ - request)
            )
            result <- Concurrent[F].fromEither(result)
          yield result
        }

        def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
          for
            callback <- stateRef.get.map(_.get(request))
            _ <- callback.traverse(cb => cb(bytes.asRight))
          yield ()

        def failAll(t: Throwable): F[Unit] =
          for
            state <- stateRef.get
            _ <- state.values.toList.traverse { cb =>
              cb(t.asLeft)
            }
          yield ()
      }
  }

  def connect[F[_]](selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(
    using
    F: Async[F],
    socketGroup: SocketGroup[F],
    logger: Logger[F]
  ): Resource[F, Connection[F]] =
    MessageSocket.connect(selfId, peerInfo, infoHash).flatMap { socket =>
      Resource {
        for
          stateRef <- Ref.of[F, State](State())
          chokedStatusRef <- SignallingRef(true)
          bitfieldRef <- SignallingRef(BitSet.empty)
          requestRegistry <- RequestRegistry[F]
          (extensionHandler, initExtension) <- ExtensionHandler.InitExtension(
            infoHash,
            socket.send,
            new ExtensionHandler.UtMetadata.Create[F]
          )
          _ <- logger.debug(s"Connected ${peerInfo.address}")
          updateLastMessageTime = (l: Long) => stateRef.update(State.lastMessageAt.replace(l))
          fiber <-
            Concurrent[F]
              .race[Nothing, Nothing](
                receiveLoop(
                  requestRegistry,
                  bitfieldRef.update,
                  chokedStatusRef.set,
                  updateLastMessageTime,
                  socket,
                  extensionHandler
                ),
                backgroundLoop(stateRef, socket)
              )
              .void
              .attempt
              .start
          closed <- Deferred[F, Either[Throwable, Unit]]
          doClose =
            (reason: Either[Throwable, Unit]) =>
              fiber.cancel >>
              requestRegistry.failAll(ConnectionClosed()) >>
              logger.debug(s"Disconnected ${peerInfo.address}") >>
              closed.complete(reason).attempt.void
          _ <- fiber.join.flatMap(_ => doClose(().asRight)).start
        yield
          val impl: Connection[F] = new Connection[F] {
            def info: PeerInfo = peerInfo
            def extensionProtocol: Boolean = socket.handshake.extensionProtocol

            def interested: F[Unit] =
              for
                interested <- stateRef.modify(s => (State.interested.replace(true)(s), s.interested))
                _ <- F.whenA(!interested)(socket.send(Message.Interested))
              yield ()

            def request(request: Message.Request): F[ByteVector] =
              socket.send(request) >>
              requestRegistry.register(request).flatMap { bytes =>
                if bytes.length == request.length
                then
                  bytes.pure[F]
                else
                  Error.InvalidBlockLength(request, bytes.length).raiseError[F, ByteVector]
              }

            def choked: Signal[F, Boolean] = chokedStatusRef

            def availability: Signal[F, BitSet] = bitfieldRef

            def disconnected: F[Either[Throwable, Unit]] = closed.get

            def close: F[Unit] = doClose(().asRight)

            def extensionApi: F[ExtensionApi[F]] = initExtension.init
          }
          (impl, doClose(Right(())))
        end for
      }
    }

  case class ConnectionClosed() extends Throwable

  private def receiveLoop[F[_]: Monad](
    requestRegistry: RequestRegistry[F],
    updateBitfield: (BitSet => BitSet) => F[Unit],
    updateChokeStatus: Boolean => F[Unit],
    updateLastMessageAt: Long => F[Unit],
    socket: MessageSocket[F],
    extensionHandler: ExtensionHandler[F]
  )(using clock: Clock[F]): F[Nothing] =
    socket.receive
      .flatMap {
        case Message.Unchoke =>
          updateChokeStatus(false)
        case Message.Choke =>
          updateChokeStatus(true)
        case Message.Piece(index: Long, begin: Long, bytes: ByteVector) =>
          val request: Message.Request = Message.Request(index, begin, bytes.length)
          requestRegistry.complete(request, bytes)
        case Message.Have(index) =>
          updateBitfield(_ incl index.toInt)
        case Message.Bitfield(bytes) =>
          val indices = bytes.toBitVector.toIndexedSeq.zipWithIndex.collect {
            case (true, i) => i
          }
          updateBitfield(_ => BitSet(indices*))
        case m: Message.Extended =>
          extensionHandler(m)
        case _ =>
          Monad[F].unit
      }
      .flatTap { _ =>
        clock.realTime.flatMap { currentTime =>
          updateLastMessageAt(currentTime.toMillis)
        }
      }
      .foreverM

  private def backgroundLoop[F[_]](
    stateRef: Ref[F, State],
    socket: MessageSocket[F]
  )(using F: Temporal[F]): F[Nothing] =
    F
      .sleep(10.seconds)
      .flatMap { _ =>
        for
          currentTime <- F.realTime
          timedOut <- stateRef.get.map(s => (currentTime - s.lastMessageAt.millis) > 1.minute)
          _ <- F.whenA(timedOut) {
            F.raiseError(Error.ConnectionTimeout())
          }
          _ <- socket.send(Message.KeepAlive)
        yield ()
      }
      .foreverM

  enum Error(message: String) extends Exception(message):
    case ConnectionTimeout() extends Error("Connection timed out")
    case InvalidBlockLength(request: Message.Request, responseLength: Long) extends Error("Invalid block length")
}
