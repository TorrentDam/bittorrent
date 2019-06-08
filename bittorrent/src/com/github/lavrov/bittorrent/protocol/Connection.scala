package com.github.lavrov.bittorrent.protocol

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Timer}
import cats.mtl._
import cats.syntax.all._
import cats.{Monad, MonadError}
import com.github.lavrov.bittorrent.protocol.Connection.Event
import com.github.lavrov.bittorrent.protocol.extensions.Extensions
import com.github.lavrov.bittorrent.protocol.extensions.metadata.MetadataExtension
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.github.lavrov.bittorrent.protocol.extensions.metadata.{Message => MetadataMessage}
import com.olegpy.meow.effects._
import fs2.{Chunk, Stream}
import fs2.concurrent.Queue
import fs2.io.tcp.Socket
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

trait Connection[F[_]] {
  def info: PeerInfo
  def extensionProtocol: Boolean
  def download(request: Message.Request): F[Unit]
  def events: Stream[F, Event]
  def metadataExtension: F[MetadataExtension[F]]
}

object Connection {

  case class State(
      lastMessageAt: Long = 0,
      choking: Boolean = true,
      interested: Boolean = false,
      peerChoking: Boolean = true,
      peerInterested: Boolean = false,
      bitfield: Option[BitVector] = None,
      queue: ListSet[Message.Request] = ListSet.empty,
      pending: ListSet[Message.Request] = ListSet.empty,
      extensionProtocol: Option[ExtensionProtocolState] = None
  )

  case class ExtensionProtocolState(
      messageIds: List[(String, Long)],
      metadataSize: Long,
      metadata: ByteVector
  ) {
    def messageIdFor(extension: String): Option[Long] = messageIds.find(_._1 == extension).map(_._2)
    def extensionNameFor(messageId: Long): Option[String] = messageIds.find(_._2 == messageId).map(_._1)
  }

  trait Effects[F[_]] {
    def currentTime: F[Long]
    def send(message: Message): F[Unit]
    def schedule(in: FiniteDuration, msg: Command): F[Unit]
    def emit(event: Event): F[Unit]
    def state: MonadState[F, State]
    def completeMetadataExtension(messageId: Long): F[Unit]
  }

  sealed trait Event

  object Event {
    case class Downloaded(request: Message.Request, bytes: ByteVector) extends Event
    case class DownloadedMetadata(bytes: ByteVector) extends Event
  }

  sealed trait Command

  object Command {
    case class PeerMessage(message: Message) extends Command
    case class SendKeepAlive() extends Command
    case class Download(request: Message.Request) extends Command
    case class CheckRequest(request: Message.Request) extends Command
    case class RequestMetadata(piece: Long) extends Command
  }

  class Behaviour[F[_]](
      handshake: Handshake,
      keepAliveInterval: FiniteDuration,
      effects: Effects[F],
      logger: Logger[F]
  )(implicit F: MonadError[F, Throwable]) {

    def initialize: F[Unit] = {
      logger.debug(s"Initialize connection") *>
      effects.send(Extensions.handshake).whenA(handshake.extensionProtocol)
    }

    def receive: Command => F[Unit] = {
      case Command.PeerMessage(message) => handleMessage(message)
      case Command.SendKeepAlive() => sendKeepAlive
      case Command.Download(request) => requestPiece(request)
      case Command.CheckRequest(request) => checkRequest(request)
      case Command.RequestMetadata(piece) => requestMetadataPiece(piece)
    }

    def handleMessage(msg: Message): F[Unit] = {
      for {
        time <- effects.currentTime
        _ <- msg match {
          case Message.KeepAlive => Monad[F].unit
          case Message.Choke =>
            effects.state.modify(_.copy(peerChoking = true))
          case Message.Unchoke =>
            for {
              _ <- effects.state.modify(_.copy(peerChoking = false))
              _ <- requestPieceFromQueue
            } yield ()
          case Message.Interested =>
            effects.state.modify(_.copy(peerInterested = true))
          case Message.NotInterested =>
            effects.state.modify(_.copy(peerInterested = false))
          case piece: Message.Piece => receivePiece(piece)
          case Message.Bitfield(bytes) =>
            effects.state.modify(_.copy(bitfield = bytes.bits.some))
          case Message.Extended(id, payload) =>
            if (id == 0) {
              Extensions
                .processHandshake(payload.toBitVector, logger)
                .flatMap { handshake =>
                  logger.debug(s"Extension handshake $handshake") >>
                  effects.state.modify(
                    _.copy(
                      extensionProtocol =
                        ExtensionProtocolState(
                          handshake.extensions.toList,
                          handshake.metadataSize.getOrElse(0L),
                          ByteVector.empty
                        ).some
                    )
                  )
                } >>
                effects.completeMetadataExtension(0)
            } else
              inExtensionProtocol { state =>
                val extensionName = state.extensionNameFor(id).get
                extensionName match {
                  case "ut_metadata" =>
                    F.fromEither(MetadataMessage.decode(payload))
                      .flatMap {
                        case MetadataMessage.Data(piece, bytes) =>
                          val downloadedMetadata = if (piece == 0) bytes else state.metadata ++ bytes
                          (
                            if (downloadedMetadata.size == state.metadataSize)
                              logger.debug(s"Downloaded metadata") *>
                              (
                                if (downloadedMetadata.digest("SHA-1") == handshake.infoHash.bytes)
                                  logger.debug("Metadata hash is valid") *>
                                  effects.emit(Event.DownloadedMetadata(downloadedMetadata))
                                else
                                  logger.debug("Metadata hash does not match info-hash")
                              )
                            else
                              requestMetadataPiece(piece + 1)
                          ) *>
                          F.pure(
                            state.copy(
                              metadata = downloadedMetadata
                            )
                          )
                        case _ =>
                          logger.warn(s"Unsupported metadata extension message")
                          F.pure(state)
                      }
                  case _ =>
                    logger.warn(s"Unsupported extension message") *>
                    F.pure(state)
                }
              }
          case _ => Monad[F].unit
        }
        _ <- effects.state.modify(_.copy(lastMessageAt = time))
      } yield ()
    }

    def requestPieceFromQueue: F[Unit] = {
      for {
        iAmInterested <- effects.state.inspect(_.interested)
        _ <- effects.send(Message.Interested).whenA(!iAmInterested)
        _ <- effects.state.modify(_.copy(interested = true))
        state <- effects.state.get
        _ <- if (state.peerChoking)
          Monad[F].unit
        else
          state.queue.headOption match {
            case Some(request) =>
              for {
                _ <- effects.state.set(
                  state.copy(
                    queue = state.queue.tail,
                    pending = state.pending + request
                  )
                )
                _ <- effects.send(request)
                _ <- effects.schedule(10.seconds, Command.CheckRequest(request))
              } yield ()
            case None =>
              Monad[F].unit
          }

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

    def requestPiece(request: Message.Request): F[Unit] = {
      for {
        state <- effects.state.get
        _ <- effects.state.set(state.copy(queue = state.queue + request))
        _ <- requestPieceFromQueue
      } yield ()
    }

    def checkRequest(request: Message.Request): F[Unit] = {
      for {
        stillPending <- effects.state.inspect(
          s => s.pending.contains(request) || s.queue.contains(request)
        )
        _ <- if (stillPending) F.raiseError[Unit](new Exception("Peer doesn't respond"))
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
              _ <- effects.state.set(
                state.copy(
                  pending = state.pending.filterNot(_ == request)
                )
              )
              _ <- requestPieceFromQueue
            } yield ()
          else
            F.raiseError(new Exception("Unexpected piece"))
        }: F[Unit]
      } yield ()
    }

    def requestMetadataPiece(piece: Long): F[Unit] = inExtensionProtocol { state =>
      if (state.metadataSize > 0)
        state.messageIdFor("ut_metadata") match {
          case Some(messageId) =>
            val message = Message.Extended(messageId, MetadataMessage.encode(MetadataMessage.Request(piece)))
            effects.send(message) *>
            F.pure(state)
          case None =>
            F.pure(state)
        }
      else
        F.pure(state)
    }

    private def inExtensionProtocol(f: ExtensionProtocolState => F[ExtensionProtocolState]): F[Unit] = {
      for {
        state <- effects.state.get
        _ <- state.extensionProtocol match {
          case Some(v) =>
            f(v).flatMap(s =>
              effects.state.modify(_.copy(extensionProtocol = s.some))
            )
          case None => F.raiseError[Unit](new Exception("Extension protocol not initialized"))
        }
      } yield ()
    }
  }

  def connect[F[_]: Concurrent](
      selfId: PeerId,
      peerInfo: PeerInfo,
      infoHash: InfoHash,
      socket: Socket[F],
      timer: Timer[F]
  ): F[Connection[F]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      connection <- connect0(selfId, peerInfo, infoHash, socket)
      queue <- Queue.unbounded[F, Command]
      eventQueue <- Queue.noneTerminated[F, Event]
      stateRef <- Ref.of[F, State](State())
      metadataExtensionDeferred <- Deferred[F, MetadataExtension[F]]
      effects = new Effects[F] {
        def currentTime: F[Long] =
          timer.clock.realTime(TimeUnit.MILLISECONDS)

        def send(message: Message): F[Unit] =
          connection.send(message)

        def schedule(in: FiniteDuration, msg: Command): F[Unit] =
          Concurrent[F].start(timer.sleep(in) *> queue.enqueue1(msg)).void

        def emit(event: Event): F[Unit] =
          eventQueue.enqueue1(event.some)

        val state: MonadState[F, State] = stateRef.stateInstance
        def completeMetadataExtension(messageId: Long): F[Unit] =
          metadataExtensionDeferred.complete(
            new MetadataExtension[F] {
              def get(piece: Long): F[Unit] = {
                queue.enqueue1(Command.RequestMetadata(piece))
              }
            }
          )
      }
      enqueueFiber <- Concurrent[F] start Stream
        .repeatEval(connection.receive)
        .map(Command.PeerMessage)
        .through(queue.enqueue)
        .compile
        .drain
      behaviour = new Behaviour(connection.handshake, 10.seconds, effects, logger)
      _ <- behaviour.initialize
      dequeueFiber <- Concurrent[F] start {
        queue.dequeue.evalTap(behaviour.receive).compile.drain
      }
      _ <- Concurrent[F].start {
        Concurrent[F]
          .race(enqueueFiber.join, dequeueFiber.join)
          .onError {
            case e =>
              logger.info(s"Disconnected $peerInfo") *>
                logger.debug(e)(s"Connection error") *>
                eventQueue.enqueue1(None)
          }
      }
    } yield {
      new Connection[F] {
        val info = peerInfo
        val extensionProtocol = connection.handshake.extensionProtocol
        def download(request: Message.Request): F[Unit] = queue.enqueue1(Command.Download(request))
        def events: Stream[F, Event] = eventQueue.dequeue
        def metadataExtension: F[MetadataExtension[F]] = metadataExtensionDeferred.get
      }
    }
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
      maybeBytes <- socket.readN(
        Handshake.HandshakeCodec.sizeBound.exact.get.toInt / 8,
        timeout = Some(5.seconds)
      )
      bytes <- F.fromOption(
        maybeBytes,
        new Exception("Connection was closed unexpectedly")
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

  def connect0[F[_]: Concurrent](
      selfId: PeerId,
      peerInfo: PeerInfo,
      infoHash: InfoHash,
      socket: Socket[F]
  ): F[Connection0[F]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      handshakeResponse <- handshake(selfId, infoHash, socket, logger)
    } yield {
      new Connection0(handshakeResponse, peerInfo, socket, logger)
    }
  }
}

class Connection0[F[_]](val handshake: Handshake, peerInfo: PeerInfo, socket: Socket[F], logger: Logger[F])(
    implicit F: Concurrent[F]
) {

  def send(message: Message): F[Unit] =
    for {
      _ <- socket.write(Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector))
      _ <- logger.debug(s"Sent $message")
    } yield ()

  def receive: F[Message] =
    for {
      maybeChunk <- socket.readN(4)
      chunk <- F.fromOption(maybeChunk, new Exception("Connection was closed unexpectedly"))
      size <- F fromTry Message.MessageSizeCodec.decodeValue(BitVector(chunk.toArray)).toTry
      maybeChunk <- socket.readN(size.toInt)
      chunk <- F.fromOption(maybeChunk, new Exception("Connection was closed unexpectedly"))
      bv = ByteVector(chunk.toArray)
      message <- F.fromTry(
        Message.MessageBodyCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
      _ <- logger.debug(s"Received $message")
    } yield message

}
