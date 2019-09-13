package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats._
import cats.effect.{Concurrent, ContextShift, Resource}
import cats.syntax.all._
import com.github.lavrov.bencode.{decode, encode}
import com.github.lavrov.bittorrent.dht.message.Message
import fs2.Chunk
import fs2.io.udp.{Packet, Socket, SocketGroup}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.Err
import scodec.bits.BitVector

class MessageSocket[F[_]](socket: Socket[F], logger: Logger[F])(
    implicit F: MonadError[F, Throwable]
) {
  import MessageSocket.Error

  def readMessage: F[(InetSocketAddress, Message)] =
    for {
      packet <- socket.read()
      bc <- F.fromEither(
        decode(BitVector(packet.bytes.toArray)).leftMap(Error.BecodeSerialization)
      )
      message <- F.fromEither(
        Message.MessageFormat
          .read(bc)
          .leftMap(
            e => Error.MessageFormat(s"Filed to read message from bencode: $bc", e)
          )
      )
      _ <- logger.debug(s"<<< ${packet.remote} $message")
    } yield (packet.remote, message)

  def writeMessage(address: InetSocketAddress, message: Message): F[Unit] = {
    val bc = Message.MessageFormat.write(message).right.get
    val bytes = encode(bc)
    val packet = Packet(address, Chunk.byteVector(bytes.bytes))
    socket.write(packet) >> logger.debug(s">>> $address $message")
  }
}

object MessageSocket {
  def apply[F[_]](
      port: Int
  )(
      implicit F: Concurrent[F],
      cs: ContextShift[F],
      socketGroup: SocketGroup
  ): Resource[F, MessageSocket[F]] =
    socketGroup
      .open[F](address = new InetSocketAddress(port))
      .evalMap(
        socket =>
          for {
            logger <- Slf4jLogger.fromClass[F](getClass)
          } yield new MessageSocket(socket, logger)
      )

  object Error {
    case class BecodeSerialization(err: Err) extends Throwable(err.messageWithContext)
    case class MessageFormat(message: String, cause: Throwable) extends Throwable(message, cause)
  }
}
