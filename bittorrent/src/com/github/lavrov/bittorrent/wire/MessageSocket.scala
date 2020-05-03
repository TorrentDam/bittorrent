package com.github.lavrov.bittorrent.wire

import java.nio.channels.InterruptedByTimeoutException

import cats._
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, Resource}
import cats.syntax.all._
import com.github.lavrov.bittorrent._
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import fs2.Chunk
import fs2.io.tcp.Socket
import logstage.LogIO
import scodec.bits.ByteVector

import scala.concurrent.duration._

class MessageSocket[F[_]](
  val handshake: Handshake,
  val peerInfo: PeerInfo,
  socket: Socket[F],
  writeMutex: Semaphore[F],
  logger: LogIO[F]
)(implicit F: MonadError[F, Throwable]) {

  def send(message: Message): F[Unit] =
    for {
      _ <- writeMutex.withPermit {
        socket.write(
          Chunk.byteVector(
            Message.MessageCodec.encode(message).require.toByteVector
          )
        )
      }
      _ <- logger.trace(s">>> ${peerInfo.address} $message")
    } yield ()

  def receive: F[Message] =
    for {
      bytes <- readExactlyN(4)
      size <-
        F fromTry Message.MessageSizeCodec
          .decodeValue(bytes.toBitVector)
          .toTry
      bytes <- readExactlyN(size.toInt)
      message <- F.fromTry(
        Message.MessageBodyCodec
          .decodeValue(bytes.toBitVector)
          .toTry
      )
      _ <- logger.trace(s"<<< ${peerInfo.address} $message")
    } yield message

  private def readExactlyN(numBytes: Int): F[ByteVector] =
    for {
      maybeChunk <- socket.readN(numBytes)
      chunk <- F.fromOption(
        maybeChunk.filter(_.size == numBytes),
        new Exception("Connection was interrupted by peer")
      )
    } yield chunk.toByteVector

}

object MessageSocket {
  import fs2.io.tcp.SocketGroup

  def connect[F[_]](selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(implicit
    F: Concurrent[F],
    cs: ContextShift[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, MessageSocket[F]] = {
    for {
      socket <- socketGroup.client(to = peerInfo.address)
      socket <- Resource.make(socket.pure[F])(
        _.close *> logger.trace(s"Closed socket $peerInfo")
      )
      _ <- Resource.liftF(logger.trace(s"Opened socket $peerInfo"))
      handshakeResponse <- Resource.liftF(
        logger.trace(s"Initiate handshake with ${peerInfo.address}") *>
        handshake(selfId, infoHash, socket) <*
        logger.trace(s"Successful handshake with ${peerInfo.address}")
      )
      writeMutex <- Resource.liftF(Semaphore(1))
    } yield new MessageSocket(handshakeResponse, peerInfo, socket, writeMutex, logger)
  }

  def handshake[F[_]](
    selfId: PeerId,
    infoHash: InfoHash,
    socket: Socket[F]
  )(implicit F: Concurrent[F]): F[Handshake] = {
    val message = Handshake(extensionProtocol = true, infoHash, selfId)
    for {
      _ <- socket.write(
        bytes = Chunk.byteVector(
          Handshake.HandshakeCodec.encode(message).require.toByteVector
        ),
        timeout = Some(5.seconds)
      )
      handshakeMessageSize = Handshake.HandshakeCodec.sizeBound.exact.get.toInt / 8
      maybeBytes <-
        socket
          .readN(handshakeMessageSize, timeout = Some(5.seconds))
          .adaptError {
            case e: InterruptedByTimeoutException =>
              Error("Timeout waiting for handshake", e)
          }
      bytes <- F.fromOption(
        maybeBytes.filter(_.size == handshakeMessageSize),
        Error("Unsuccessful handshake: connection prematurely closed")
      )
      response <- F.fromEither(
        Handshake.HandshakeCodec
          .decodeValue(bytes.toBitVector)
          .toEither
          .leftMap { _ =>
            Error("Unable to decode handhshake reponse")
          }
      )
    } yield response
  }

  case class Error(message: String, cause: Throwable = null) extends Exception(message, cause)
}
