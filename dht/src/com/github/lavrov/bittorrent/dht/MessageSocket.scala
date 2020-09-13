package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats._
import cats.effect.{Concurrent, ContextShift, Resource}
import cats.syntax.all._
import com.github.torrentdam.bencode.{decode, encode}
import fs2.Chunk
import fs2.io.udp.{Packet, Socket, SocketGroup}
import logstage.LogIO

class MessageSocket[F[_]](socket: Socket[F], logger: LogIO[F])(implicit
  F: MonadError[F, Throwable]
) {
  import MessageSocket.Error

  def readMessage: F[(InetSocketAddress, Message)] =
    for {
      packet <- socket.read()
      bc <- F.fromEither(
        decode(packet.bytes.toBitVector).leftMap(Error.BecodeSerialization)
      )
      message <- F.fromEither(
        Message.MessageFormat
          .read(bc)
          .leftMap(e => Error.MessageFormat(s"Filed to read message from bencode: $bc", e))
      )
      _ <- logger.trace(s"<<< ${packet.remote} $message")
    } yield (packet.remote, message)

  def writeMessage(address: InetSocketAddress, message: Message): F[Unit] = {
    val bc = Message.MessageFormat.write(message).right.get
    val bytes = encode(bc)
    val packet = Packet(address, Chunk.byteVector(bytes.bytes))
    socket.write(packet) >> logger.trace(s">>> $address $message")
  }
}

object MessageSocket {
  def apply[F[_]](
    port: Int
  )(implicit
    F: Concurrent[F],
    cs: ContextShift[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, MessageSocket[F]] =
    socketGroup
      .open[F](address = new InetSocketAddress(port))
      .map(socket => new MessageSocket(socket, logger))

  object Error {
    case class BecodeSerialization(cause: Throwable) extends Throwable(cause)
    case class MessageFormat(message: String, cause: Throwable) extends Throwable(message, cause)
  }
}
