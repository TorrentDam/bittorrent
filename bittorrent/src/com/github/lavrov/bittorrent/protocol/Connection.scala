package com.github.lavrov.bittorrent.protocol

import java.nio.channels.InterruptedByTimeoutException
import java.util.concurrent.TimeUnit

import cats.effect.concurrent.{Deferred, MVar, Ref}
import cats.effect.{Concurrent, Timer}
import cats.mtl._
import cats.syntax.all._
import cats.{Monad, MonadError}
import com.github.lavrov.bencode.BencodeCodec
import com.github.lavrov.bittorrent.protocol.Connection.Event
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.olegpy.meow.effects._
import fs2.{Chunk, Stream}
import fs2.concurrent.Queue
import fs2.io.tcp.Socket
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.ListSet
import scala.concurrent.duration._
import cats.effect.Resource
import java.nio.channels.AsynchronousChannelGroup
import cats.effect.ContextShift
import java.{util => ju}
import fs2.concurrent.Topic

trait Connection[F[_]] {
  def uniqueId: ju.UUID
  def info: PeerInfo
  def extensionProtocol: Boolean
  def interested: F[Unit]
  def request(request: Message.Request): F[Unit]
  def cancel(request: Message.Request): F[Unit]
  def events: Stream[F, Event]
  def choked: Stream[F, Boolean]
  def disconnected: F[Either[Throwable, Unit]]
}

object Connection {

  case class State(
      lastMessageAt: Long = 0,
      choking: Boolean = true,
      interested: Boolean = false,
      peerChoking: Boolean = true,
      peerInterested: Boolean = false,
      bitfield: Option[BitVector] = None,
      pending: ListSet[Message.Request] = ListSet.empty,
      lastPieceAt: Option[Long] = None
  )

  trait Effects[F[_]] {
    def currentTime: F[Long]
    def send(message: Message): F[Unit]
    def schedule(in: FiniteDuration, msg: Command[Unit]): F[Unit]
    def emit(event: Event): F[Unit]
    def updateChoked(status: Boolean): F[Unit]
    def state: MonadState[F, State]
  }

  sealed trait Event

  object Event {
    case class Downloaded(request: Message.Request, bytes: ByteVector) extends Event
  }

  sealed trait Command[A]

  object Command {
    case class PeerMessage(message: Message) extends Command[Unit]
    case class SendKeepAlive() extends Command[Unit]
    case object MakeInterested extends Command[Unit]
    case class SendRequest(request: Message.Request) extends Command[Boolean]
    case class Cancel(request: Message.Request) extends Command[Unit]
    case object CheckProgress extends Command[Unit]
  }

  class Behaviour[F[_]](
      handshake: Handshake,
      keepAliveInterval: FiniteDuration,
      effects: Effects[F],
      logger: Logger[F]
  )(implicit F: MonadError[F, Throwable]) {

    def receive[A](command: Command[A]): F[A] = command match {
      case Command.PeerMessage(message) => handleMessage(message)
      case Command.SendKeepAlive() => sendKeepAlive
      case Command.MakeInterested => makeInterested
      case Command.SendRequest(request) => sendRequest(request)
      case Command.Cancel(request) => cancelPiece(request)
      case Command.CheckProgress => checkProgress
    }

    def handleMessage(msg: Message): F[Unit] = {
      for {
        time <- effects.currentTime
        _ <- msg match {
          case Message.KeepAlive => Monad[F].unit
          case Message.Choke =>
            effects.state.modify(_.copy(peerChoking = true)) *>
              effects.updateChoked(true)
          case Message.Unchoke =>
            effects.state.modify(_.copy(peerChoking = false)) *>
              effects.updateChoked(false)
          case Message.Interested =>
            effects.state.modify(_.copy(peerInterested = true))
          case Message.NotInterested =>
            effects.state.modify(_.copy(peerInterested = false))
          case piece: Message.Piece => receivePiece(piece)
          case Message.Bitfield(bytes) =>
            effects.state.modify(_.copy(bitfield = bytes.bits.some))
          case _ => Monad[F].unit
        }
        _ <- effects.state.modify(_.copy(lastMessageAt = time))
      } yield ()
    }

    def sendKeepAlive: F[Unit] = {
      for {
        state <- effects.state.get
        time <- effects.currentTime
        durationSinceLastMessage = (time - state.lastMessageAt).millis
        _ <- if (durationSinceLastMessage > keepAliveInterval)
          effects.send(Message.KeepAlive)
        else
          Monad[F].unit
        _ <- effects.schedule(keepAliveInterval, Command.SendKeepAlive())
      } yield ()
    }

    def makeInterested: F[Unit] = {
      for {
        interested <- effects.state.inspect(_.interested)
        _ <- F.whenA(!interested)(
          effects.send(Message.Interested)
        )
      } yield ()
    }

    def sendRequest(request: Message.Request): F[Boolean] = {
      for {
        state <- effects.state.get
        requested <- if (state.peerChoking)
          false.pure
        else
          for {
            _ <- effects.send(request)
            _ <- effects.state.set(state.copy(pending = state.pending + request))
            _ <- effects.schedule(30.seconds, Command.CheckProgress)
          } yield true
      } yield requested
    }

    def cancelPiece(request: Message.Request): F[Unit] = {
      for {
        state <- effects.state.get
        _ <- effects.state.set(state.copy(pending = state.pending - request))
        _ <- effects.send(Message.Cancel(request.index, request.begin, request.length))
      } yield ()
    }

    def checkProgress: F[Unit] = {
      for {
        currentTime <- effects.currentTime
        tooSlow <- effects.state.inspect(_.lastPieceAt).map {
          case Some(lastPieceAt) =>
            val sinceLastPiece = (currentTime - lastPieceAt).milliseconds
            sinceLastPiece > 10.seconds
          case None => false
        }
        _ <- if (tooSlow) F.raiseError[Unit](new Exception("Peer doesn't respond"))
        else Monad[F].unit
      } yield ()
    }

    def receivePiece(piece: Message.Piece): F[Unit] = {
      for {
        state <- effects.state.get
        request = Message.Request(piece.index, piece.begin, piece.bytes.length)
        inPending = state.pending.contains(request)
        _ <- {
          if (inPending)
            for {
              _ <- effects.emit(Event.Downloaded(request, piece.bytes))
              currentTime <- effects.currentTime
              _ <- effects.state.set(
                state.copy(
                  pending = state.pending.filterNot(_ == request),
                  lastPieceAt = currentTime.some
                )
              )
            } yield ()
          else
            F.raiseError(new Exception("Unexpected piece"))
        }: F[Unit]
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
      acg: AsynchronousChannelGroup
  ): Resource[F, Connection[F]] = {
    type PendingCommand[A] = (Command[A], A => F[Unit])
    val Noop = (_: Unit) => F.unit
    Connection0.connect(selfId, peerInfo, infoHash).evalMap { connection =>
      for {
        logger <- Slf4jLogger.fromClass(getClass)
        queue <- Queue.unbounded[F, Command[_]]
        eventQueue <- Queue.noneTerminated[F, Event]
        initState = State()
        chokedTopic <- Topic(initState.peerChoking)
        stateRef <- Ref.of[F, State](initState)
        effects = new Effects[F] {
          def currentTime: F[Long] =
            timer.clock.realTime(TimeUnit.MILLISECONDS)
          def send(message: Message): F[Unit] =
            connection.send(message)
          def schedule(in: FiniteDuration, msg: Command[Unit]): F[Unit] =
            F.start(timer.sleep(in) *> queue.enqueue1(msg)).void
          def emit(event: Event): F[Unit] =
            eventQueue.enqueue1(event.some)
          def updateChoked(choked: Boolean): F[Unit] =
            chokedTopic.publish1(choked)
          val state: MonadState[F, State] = stateRef.stateInstance
        }
        enqueueFiber <- Concurrent[F] start Stream
          .repeatEval(connection.receive)
          .map(Command.PeerMessage)
          .through(queue.enqueue)
          .compile
          .drain
        behaviour = new Behaviour(connection.handshake, 10.seconds, effects, logger)
        dequeueFiber <- Concurrent[F] start {
          queue.dequeue
            .evalTap { command =>
              behaviour.receive(command).void
            }
            .compile
            .drain
        }
        runningProcess <- Concurrent[F].start {
          Concurrent[F]
            .race(enqueueFiber.join, dequeueFiber.join)
            .onError {
              case e =>
                logger.debug(e)(s"Connection error $peerInfo") *>
                  eventQueue.enqueue1(None)
            }
        }
      } yield {
        new Connection[F] {
          val uniqueId = ju.UUID.randomUUID()
          val info = peerInfo
          val extensionProtocol = connection.handshake.extensionProtocol
          def interested: F[Unit] = queue.enqueue1(Command.MakeInterested)
          def request(request: Message.Request): F[Unit] =
            queue.enqueue1(Command.SendRequest(request))
          def cancel(request: Message.Request): F[Unit] =
            queue.enqueue1(Command.Cancel(request))
          def events: Stream[F, Event] = eventQueue.dequeue
          def choked: Stream[F, Boolean] = chokedTopic.subscribe(Int.MaxValue)
          def disconnected: F[Either[Throwable, Unit]] = runningProcess.join.void.attempt
        }
      }
    }
  }
}

class Connection0[F[_]](
    val handshake: Handshake,
    val peerInfo: PeerInfo,
    socket: Socket[F],
    logger: Logger[F]
)(
    implicit F: MonadError[F, Throwable]
) {

  def send(message: Message): F[Unit] =
    for {
      _ <- socket.write(Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector))
      _ <- logger.debug(s"Sent $message")
    } yield ()

  def receive: F[Message] =
    for {
      bytes <- readExactlyN(4)
      size <- F fromTry Message.MessageSizeCodec.decodeValue(bytes.toBitVector).toTry
      bytes <- readExactlyN(size.toInt)
      message <- F.fromTry(
        Message.MessageBodyCodec
          .decodeValue(bytes.toBitVector)
          .toTry
      )
      _ <- logger.debug(s"Received $message")
    } yield message

  private def readExactlyN(numBytes: Int): F[ByteVector] =
    for {
      maybeChunk <- socket.readN(numBytes)
      chunk <- F.fromOption(
        maybeChunk.filter(_.size == numBytes),
        new Exception("Connection was interrupted by peer")
      )
    } yield ByteVector(chunk.toArray)

}

object Connection0 {
  import fs2.io.tcp.{Socket => TCPSocket}

  def connect[F[_]](
      selfId: PeerId,
      peerInfo: PeerInfo,
      infoHash: InfoHash
  )(
      implicit F: Concurrent[F],
      sc: ContextShift[F],
      acg: AsynchronousChannelGroup
  ): Resource[F, Connection0[F]] = {
    for {
      logger <- Resource.liftF(Slf4jLogger.fromClass(getClass))
      socket <- TCPSocket.client(to = peerInfo.address)
      socket <- Resource.make(socket.pure)(_.close *> logger.debug(s"Closed socket $peerInfo"))
      _ <- Resource.liftF(logger.debug(s"Opened socket $peerInfo"))
      handshakeResponse <- Resource.liftF(handshake(selfId, infoHash, socket, logger))
    } yield new Connection0(handshakeResponse, peerInfo, socket, logger)
  }

  def handshake[F[_]](selfId: PeerId, infoHash: InfoHash, socket: Socket[F], logger: Logger[F])(
      implicit F: Concurrent[F]
  ): F[Handshake] = {
    val message = Handshake(extensionProtocol = true, infoHash, selfId)
    for {
      _ <- logger.debug(s"Initiate handshake")
      _ <- socket.write(
        bytes = Chunk.byteVector(Handshake.HandshakeCodec.encode(message).require.toByteVector),
        timeout = Some(5.seconds)
      )
      handshakeMessageSize = Handshake.HandshakeCodec.sizeBound.exact.get.toInt / 8
      maybeBytes <- socket
        .readN(
          handshakeMessageSize,
          timeout = Some(5.seconds)
        )
        .adaptError {
          case e: InterruptedByTimeoutException => new Exception("Timeout waiting for handshake", e)
        }
      bytes <- F.fromOption(
        maybeBytes.filter(_.size == handshakeMessageSize),
        new Exception("Unsuccessful handshake: connection prematurely closed")
      )
      bv = ByteVector(bytes.toArray)
      response <- F.fromTry(
        Handshake.HandshakeCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
      _ <- logger.debug(s"Successful handshake")
    } yield response
  }
}
