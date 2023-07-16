package com.github.torrentdam.bittorrent.wire

import cats.*
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.effect.IO
import cats.effect.Outcome
import cats.effect.Resource
import cats.implicits.*
import com.github.torrentdam.bittorrent.wire.ExtensionHandler.ExtensionApi
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerId
import com.github.torrentdam.bittorrent.PeerInfo
import com.github.torrentdam.bittorrent.TorrentMetadata
import com.github.torrentdam.bittorrent.protocol.message.Message
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.net.Network
import fs2.io.net.SocketGroup
import monocle.macros.GenLens
import monocle.Lens
import org.legogroup.woof.given
import org.legogroup.woof.Logger

import scala.collection.immutable.BitSet
import scala.concurrent.duration.*
import scodec.bits.ByteVector

trait Connection {
  def info: PeerInfo
  def extensionProtocol: Boolean
  def interested: IO[Unit]
  def request(request: Message.Request): IO[ByteVector]
  def choked: Signal[IO, Boolean]
  def availability: Signal[IO, BitSet]
  def disconnected: IO[Either[Throwable, Unit]]
  def extensionApi: IO[ExtensionApi[IO]]
}

object Connection {

  case class State(lastMessageAt: Long = 0, interested: Boolean = false)
  object State {
    val lastMessageAt: Lens[State, Long] = GenLens[State](_.lastMessageAt)
    val interested: Lens[State, Boolean] = GenLens[State](_.interested)
  }

  trait RequestRegistry {
    def register(request: Message.Request): IO[ByteVector]
    def complete(request: Message.Request, bytes: ByteVector): IO[Unit]
  }
  object RequestRegistry {
    def apply(): Resource[IO, RequestRegistry] =
      for stateRef <- Resource.eval(
          IO.ref(Map.empty[Message.Request, Either[Throwable, ByteVector] => IO[Boolean]])
        )
//        _ <- Resource.onFinalize(
//          for
//            state <- stateRef.get
//            _ <- state.values.toList.traverse { cb =>
//              cb(ConnectionClosed().asLeft)
//            }
//          yield ()
//        )
      yield new RequestRegistry {

        def register(request: Message.Request): IO[ByteVector] =
          IO.deferred[Either[Throwable, ByteVector]]
            .flatMap { deferred =>
              val update = stateRef.update(_.updated(request, deferred.complete))
              val delete = stateRef.update(_ - request)
              (update >> deferred.get).guarantee(delete)
            }
            .flatMap(IO.fromEither)

        def complete(request: Message.Request, bytes: ByteVector): IO[Unit] =
          for
            callback <- stateRef.get.map(_.get(request))
            _ <- callback.traverse(cb => cb(bytes.asRight))
          yield ()
      }
  }

  def connect(selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(using
    network: Network[IO],
    logger: Logger[IO]
  ): Resource[IO, Connection] =
    for
      requestRegistry <- RequestRegistry()
      socket <- MessageSocket.connect[IO](selfId, peerInfo, infoHash)
      stateRef <- Resource.eval(IO.ref(State()))
      chokedStatusRef <- Resource.eval(SignallingRef[IO].of(true))
      bitfieldRef <- Resource.eval(SignallingRef[IO].of(BitSet.empty))
      sendQueue <- Resource.eval(Queue.bounded[IO, Message](10))
      (extensionHandler, initExtension) <- Resource.eval(
        ExtensionHandler.InitExtension(
          infoHash,
          sendQueue.offer,
          new ExtensionHandler.UtMetadata.Create[IO]
        )
      )
      updateLastMessageTime = (l: Long) => stateRef.update(State.lastMessageAt.replace(l))
      closed <- Resource.eval(IO.deferred[Either[Throwable, Unit]])
      _ <-
        (
          receiveLoop(
            requestRegistry,
            bitfieldRef.update,
            chokedStatusRef.set,
            updateLastMessageTime,
            socket,
            extensionHandler
          ),
          sendLoop(sendQueue, socket),
          keepAliveLoop(stateRef, sendQueue.offer)
        ).parTupled
          .guarantee(
            closed.complete(Right(())).void
          )
          .background
    yield new Connection {
      def info: PeerInfo = peerInfo
      def extensionProtocol: Boolean = socket.handshake.extensionProtocol

      def interested: IO[Unit] =
        for
          interested <- stateRef.modify(s => (State.interested.replace(true)(s), s.interested))
          _ <- IO.whenA(!interested)(sendQueue.offer(Message.Interested))
        yield ()

      def request(request: Message.Request): IO[ByteVector] =
        sendQueue.offer(request) >>
        requestRegistry.register(request).flatMap { bytes =>
          if bytes.length == request.length
          then bytes.pure[IO]
          else Error.InvalidBlockLength(request, bytes.length).raiseError[IO, ByteVector]
        }

      def choked: Signal[IO, Boolean] = chokedStatusRef

      def availability: Signal[IO, BitSet] = bitfieldRef

      def disconnected: IO[Either[Throwable, Unit]] = closed.get

      def extensionApi: IO[ExtensionApi[IO]] = initExtension.init
    }
    end for

  case class ConnectionClosed() extends Throwable

  private def receiveLoop(
    requestRegistry: RequestRegistry,
    updateBitfield: (BitSet => BitSet) => IO[Unit],
    updateChokeStatus: Boolean => IO[Unit],
    updateLastMessageAt: Long => IO[Unit],
    socket: MessageSocket[IO],
    extensionHandler: ExtensionHandler[IO]
  ): IO[Nothing] =
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
          val indices = bytes.toBitVector.toIndexedSeq.zipWithIndex.collect { case (true, i) =>
            i
          }
          updateBitfield(_ => BitSet(indices*))
        case m: Message.Extended =>
          extensionHandler(m)
        case _ =>
          IO.unit
      }
      .flatTap { _ =>
        IO.realTime.flatMap { currentTime =>
          updateLastMessageAt(currentTime.toMillis)
        }
      }
      .foreverM

  private def keepAliveLoop(
    stateRef: Ref[IO, State],
    send: Message => IO[Unit]
  ): IO[Nothing] =
    IO
      .sleep(10.seconds)
      .flatMap { _ =>
        for
          currentTime <- IO.realTime
          timedOut <- stateRef.get.map(s => (currentTime - s.lastMessageAt.millis) > 30.seconds)
          _ <- IO.whenA(timedOut) {
            IO.raiseError(Error.ConnectionTimeout())
          }
          _ <- send(Message.KeepAlive)
        yield ()
      }
      .foreverM

  private def sendLoop(queue: Queue[IO, Message], socket: MessageSocket[IO]): IO[Nothing] =
    queue.take.flatMap(socket.send).foreverM

  enum Error(message: String) extends Exception(message):
    case ConnectionTimeout() extends Error("Connection timed out")
    case InvalidBlockLength(request: Message.Request, responseLength: Long) extends Error("Invalid block length")
}
