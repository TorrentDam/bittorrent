package com.github.torrentdam.bittorrent.wire

import cats.effect.syntax.all.*
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.github.torrentdam.bittorrent.*
import com.github.torrentdam.bittorrent.protocol.message.{Handshake, Message}
import fs2.io.net.Network
import fs2.io.net.Socket
import fs2.Chunk
import org.legogroup.woof.given
import org.legogroup.woof.Logger

import scala.concurrent.duration.*
import scodec.bits.ByteVector

class MessageSocket(
  val handshake: Handshake,
  val peerInfo: PeerInfo,
  socket: Socket[IO],
  logger: Logger[IO]
) {

  import MessageSocket.readTimeout
  import MessageSocket.writeTimeout
  import MessageSocket.MaxMessageSize
  import MessageSocket.OversizedMessage

  def send(message: Message): IO[Unit] =
    val bytes = Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector)
    for
      _ <- socket.write(bytes)
      _ <- logger.trace(s">>> ${peerInfo.address} $message")
    yield ()

  def receive: IO[Message] =
    for
      bytes <- readExactlyN(4)
      size <-
        Message.MessageSizeCodec
          .decodeValue(bytes.toBitVector)
          .toTry
          .liftTo[IO]
      _ <- IO.whenA(size > MaxMessageSize)(
        logger.error(s"Oversized payload $size $MaxMessageSize") >>
        OversizedMessage(size, MaxMessageSize).raiseError
      )
      message <-
        if (size == 0) IO.pure(Message.KeepAlive)
        else
          readExactlyN(size.toInt).flatMap(bytes =>
            IO.fromTry(
              Message.MessageBodyCodec
                .decodeValue(bytes.toBitVector)
                .toTry
            )
          )
      _ <- logger.trace(s"<<< ${peerInfo.address} $message")
    yield message

  private def readExactlyN(numBytes: Int): IO[ByteVector] =
    for
      chunk <- socket.readN(numBytes)
      _ <- if chunk.size == numBytes then IO.unit else IO.raiseError(new Exception("Connection was interrupted by peer"))
    yield chunk.toByteVector

}

object MessageSocket {

  val MaxMessageSize: Long = 1024 * 1024 // 1MB
  val readTimeout = 1.minute
  val writeTimeout = 10.seconds

  def connect(selfId: PeerId, peerInfo: PeerInfo, infoHash: InfoHash)(using
    network: Network[IO],
    logger: Logger[IO]
  ): Resource[IO, MessageSocket] = {
    for
      socket <- network.client(to = peerInfo.address).timeout(5.seconds)
      _ <- Resource.make(IO.unit)(_ => logger.trace(s"Closed socket $peerInfo"))
      _ <- Resource.eval(logger.trace(s"Opened socket $peerInfo"))
      handshakeResponse <- Resource.eval(
        logger.trace(s"Initiate handshake with ${peerInfo.address}") >>
        handshake(selfId, infoHash, socket) <*
        logger.trace(s"Successful handshake with ${peerInfo.address}")
      )
    yield new MessageSocket(handshakeResponse, peerInfo, socket, logger)
  }

  def handshake(
    selfId: PeerId,
    infoHash: InfoHash,
    socket: Socket[IO]
  )(using logger: Logger[IO]): IO[Handshake] = {
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
          .adaptError(e =>
            Error("Unsuccessful handshake", e)
          )
      _ <-
        if bytes.size == handshakeMessageSize
        then IO.unit
        else IO.raiseError(Error("Unsuccessful handshake: connection prematurely closed"))
      response <- IO.fromEither(
        Handshake.HandshakeCodec
          .decodeValue(bytes.toBitVector)
          .toEither
          .leftMap { e =>
            Error(s"Unable to decode handhshake reponse: ${e.message}")
          }
      )
    yield response
  }

  case class Error(message: String, cause: Throwable = null) extends Exception(message, cause)
  case class OversizedMessage(size: Long, maxSize: Long) extends Throwable(s"Oversized message [$size > $maxSize]")
}
