package com.github.lavrov.bittorrent.wire

import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.nio.channels.InterruptedByTimeoutException
import cats.effect.std.Semaphore
import cats.effect.{Async, Temporal, Resource}
import com.github.lavrov.bittorrent.*
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import fs2.Chunk
import fs2.io.net.{Socket, SocketGroup}
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector
import scala.concurrent.duration.*


class MessageSocket[F[_]](
  val handshake: Handshake,
  val peerInfo: PeerInfo,
  socket: Socket[F],
  writeMutex: Semaphore[F],
  logger: Logger[F]
)(using F: Temporal[F]) {

  import MessageSocket.{MaxMessageSize, OversizedMessage, readTimeout, writeTimeout}

  def send(message: Message): F[Unit] =
    for
      _ <- writeMutex.permit.use { _ =>
        val bytes = Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector)
        socket.write(bytes).timeout(writeTimeout)
      }
      _ <- logger.trace(s">>> ${peerInfo.address} $message")
    yield ()

  def receive: F[Message] =
    for
      bytes <- readExactlyN(4)
      size <-
        Message.MessageSizeCodec
          .decodeValue(bytes.toBitVector)
          .toTry
          .liftTo[F]
      _ <- F.whenA(size > MaxMessageSize)(
        logger.error(s"Oversized payload $size $MaxMessageSize") >>
        OversizedMessage(size, MaxMessageSize).raiseError
      )
      bytes <- readExactlyN(size.toInt)
      message <- F.fromTry(
        Message.MessageBodyCodec
          .decodeValue(bytes.toBitVector)
          .toTry
      )
      _ <- logger.trace(s"<<< ${peerInfo.address} $message")
    yield message

  private def readExactlyN(numBytes: Int): F[ByteVector] =
    for
      chunk <- socket.readN(numBytes).timeout(readTimeout)
      _ <- if chunk.size == numBytes then F.unit else F.raiseError(new Exception("Connection was interrupted by peer"))
    yield chunk.toByteVector

}

object MessageSocket {

  val MaxMessageSize: Long = 1024 * 1024 // 1MB
  val readTimeout = 10.seconds
  val writeTimeout = 10.seconds

  def connect[F[_]](selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(
    using
    F: Async[F],
    socketGroup: SocketGroup[F],
    logger: Logger[F]
  ): Resource[F, MessageSocket[F]] = {
    for
      socket <- socketGroup.client(to = peerInfo.address)
      _ <- Resource.make(F.unit)(
        _ => logger.trace(s"Closed socket $peerInfo")
      )
      _ <- Resource.eval(logger.trace(s"Opened socket $peerInfo"))
      handshakeResponse <- Resource.eval(
        logger.trace(s"Initiate handshake with ${peerInfo.address}") *>
        handshake(selfId, infoHash, socket) <*
        logger.trace(s"Successful handshake with ${peerInfo.address}")
      )
      writeMutex <- Resource.eval(Semaphore(1))
    yield new MessageSocket(handshakeResponse, peerInfo, socket, writeMutex, logger)
  }

  def handshake[F[_]](
    selfId: PeerId,
    infoHash: InfoHash,
    socket: Socket[F]
  )(using F: Temporal[F]): F[Handshake] = {
    val message = Handshake(extensionProtocol = true, infoHash, selfId)
    for
      _ <- socket
        .write(
          bytes = Chunk.byteVector(
            Handshake.HandshakeCodec.encode(message).require.toByteVector
          )
        )
        .timeout(writeTimeout)
      handshakeMessageSize = Handshake.HandshakeCodec.sizeBound.exact.get.toInt / 8
      bytes <-
        socket
          .readN(handshakeMessageSize)
          .timeout(readTimeout)
          .adaptError {
            case e: InterruptedByTimeoutException =>
              Error("Timeout waiting for handshake", e)
          }
      _ <- if bytes.size == handshakeMessageSize
           then F.unit
           else F.raiseError(Error("Unsuccessful handshake: connection prematurely closed"))
      response <- F.fromEither(
        Handshake.HandshakeCodec
          .decodeValue(bytes.toBitVector)
          .toEither
          .leftMap { _ =>
            Error("Unable to decode handhshake reponse")
          }
      )
    yield response
  }

  case class Error(message: String, cause: Throwable = null) extends Exception(message, cause)
  case class OversizedMessage(size: Long, maxSize: Long) extends Throwable(s"Oversized message [$size > $maxSize]")
}
