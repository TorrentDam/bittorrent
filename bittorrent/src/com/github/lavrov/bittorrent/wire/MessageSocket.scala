package com.github.lavrov.bittorrent.wire

import java.nio.channels.InterruptedByTimeoutException

import cats._
import cats.syntax.all._
import cats.instances.option._
import cats.instances.list._
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import cats.effect.syntax.all._
import com.github.lavrov.bittorrent._
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import fs2.{Chunk, Stream}
import fs2.io.tcp.{Socket, SocketGroup}
import scodec.bits.{BitVector, ByteVector}

import scala.concurrent.duration._
import java.{util => ju}

import fs2.concurrent.Topic
import logstage.LogIO
import monocle.Lens
import monocle.macros.GenLens

class MessageSocket[F[_]](
  val handshake: Handshake,
  val peerInfo: PeerInfo,
  socket: Socket[F],
  logger: LogIO[F]
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

object MessageSocket {
  import fs2.io.tcp.SocketGroup

  def connect[F[_]](
    selfId: PeerId,
    peerInfo: PeerInfo,
    infoHash: InfoHash
  )(
    implicit
    F: Concurrent[F],
    cs: ContextShift[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, MessageSocket[F]] = {
    for {
      socket <- socketGroup.client(to = peerInfo.address)
      socket <- Resource.make(socket.pure[F])(_.close *> logger.debug(s"Closed socket $peerInfo"))
      _ <- Resource.liftF(logger.debug(s"Opened socket $peerInfo"))
      handshakeResponse <- Resource.liftF(handshake(selfId, infoHash, socket, logger))
    } yield new MessageSocket(handshakeResponse, peerInfo, socket, logger)
  }

  def handshake[F[_]](selfId: PeerId, infoHash: InfoHash, socket: Socket[F], logger: LogIO[F])(
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
          case e: InterruptedByTimeoutException => Error("Timeout waiting for handshake", e)
        }
      bytes <- F.fromOption(
        maybeBytes.filter(_.size == handshakeMessageSize),
        Error("Unsuccessful handshake: connection prematurely closed")
      )
      bv = ByteVector(bytes.toArray)
      response <- F.fromEither(
        Handshake.HandshakeCodec
          .decodeValue(bv.toBitVector)
          .toEither
          .leftMap { _ =>
            Error("Unable to decode handhshake reponse")
          }
      )
      _ <- logger.debug(s"Successful handshake")
    } yield response
  }

  case class Error(message: String, cause: Throwable = null) extends Exception(message, cause)
}
