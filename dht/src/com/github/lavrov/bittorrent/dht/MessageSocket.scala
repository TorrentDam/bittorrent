package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress
import cats.*
import cats.effect.{Async, Concurrent, Resource}
import cats.syntax.all.*
import com.github.torrentdam.bencode.{decode, encode}
import com.github.torrentdam.bencode.format.BencodeFormat
import fs2.Chunk
import fs2.io.net.{DatagramSocket, DatagramSocketGroup, Datagram}
import org.typelevel.log4cats.Logger
import com.comcast.ip4s.*

class MessageSocket[F[_]](socket: DatagramSocket[F], logger: Logger[F])(implicit
  F: MonadError[F, Throwable]
) {
  import MessageSocket.Error

  def readMessage: F[(SocketAddress[IpAddress], Message)] =
    for {
      datagram <- socket.read
      bc <- F.fromEither(
        decode(datagram.bytes.toBitVector).leftMap(Error.BecodeSerialization.apply)
      )
      message <- F.fromEither(
        summon[BencodeFormat[Message]]
          .read(bc)
          .leftMap(e => Error.MessageFormat(s"Filed to read message from bencode: $bc", e))
      )
      _ <- logger.trace(s"<<< ${datagram.remote} $message")
    } yield (datagram.remote, message)

  def writeMessage(address: SocketAddress[IpAddress], message: Message): F[Unit] = {
    val bc = summon[BencodeFormat[Message]].write(message).toOption.get
    val bytes = encode(bc)
    val packet = Datagram(address, Chunk.byteVector(bytes.bytes))
    socket.write(packet) >> logger.trace(s">>> $address $message")
  }
}

object MessageSocket {

  def apply[F[_]]()(
    implicit
    F: Async[F],
    socketGroup: DatagramSocketGroup[F],
    logger: Logger[F]
  ): Resource[F, MessageSocket[F]] =
    socketGroup
      .openDatagramSocket()
      .map(socket => new MessageSocket(socket, logger))

  object Error {
    case class BecodeSerialization(cause: Throwable) extends Throwable(cause)
    case class MessageFormat(message: String, cause: Throwable) extends Throwable(message, cause)
  }
}
