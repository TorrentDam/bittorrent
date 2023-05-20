package com.github.lavrov.bittorrent.wire

import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.effect.Async
import cats.effect.Resource
import cats.effect.Temporal
import cats.syntax.all.*
import com.github.lavrov.bittorrent.*
import com.github.lavrov.bittorrent.protocol.message.Handshake
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.io.net.Network
import fs2.io.net.Socket
import fs2.io.net.SocketGroup
import fs2.Chunk
import java.nio.channels.InterruptedByTimeoutException
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scala.concurrent.duration.*
import scodec.bits.ByteVector

class MessageSocket[F[_]](
  val handshake: Handshake,
  val peerInfo: PeerInfo,
  socket: Socket[F],
  logger: Logger[F]
)(using F: Temporal[F]) {

  import MessageSocket.readTimeout
  import MessageSocket.writeTimeout
  import MessageSocket.MaxMessageSize
  import MessageSocket.OversizedMessage

  def send(message: Message): F[Unit] =
    val bytes = Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector)
    for
      _ <- socket.write(bytes)
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
      message <-
        if (size == 0) F.pure(Message.KeepAlive)
        else
          readExactlyN(size.toInt).flatMap(bytes =>
            F.fromTry(
              Message.MessageBodyCodec
                .decodeValue(bytes.toBitVector)
                .toTry
            )
          )
      _ <- logger.trace(s"<<< ${peerInfo.address} $message")
    yield message

  private def readExactlyN(numBytes: Int): F[ByteVector] =
    for
      chunk <- socket.readN(numBytes)
      _ <- if chunk.size == numBytes then F.unit else F.raiseError(new Exception("Connection was interrupted by peer"))
    yield chunk.toByteVector

}

object MessageSocket {

  val MaxMessageSize: Long = 1024 * 1024 // 1MB
  val readTimeout = 1.minute
  val writeTimeout = 10.seconds

  def connect[F[_]](selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(using
    F: Async[F],
    network: Network[F],
    logger: Logger[F]
  ): Resource[F, MessageSocket[F]] = {
    for
      socket <- network.client(to = peerInfo.address).timeout(5.seconds)
      _ <- Resource.make(F.unit)(_ => logger.trace(s"Closed socket $peerInfo"))
      _ <- Resource.eval(logger.trace(s"Opened socket $peerInfo"))
      handshakeResponse <- Resource.eval(
        logger.trace(s"Initiate handshake with ${peerInfo.address}") *>
        handshake(selfId, infoHash, socket) <*
        logger.trace(s"Successful handshake with ${peerInfo.address}")
      )
    yield new MessageSocket(handshakeResponse, peerInfo, socket, logger)
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
          .attempt
          .timeout(readTimeout)
          .rethrow
          .adaptError { case e: InterruptedByTimeoutException =>
            Error("Timeout waiting for handshake", e)
          }
      _ <-
        if bytes.size == handshakeMessageSize
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
