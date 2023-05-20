package com.github.lavrov.bittorrent.dht

import cats.*
import cats.effect.Async
import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.github.torrentdam.bencode.decode
import com.github.torrentdam.bencode.encode
import com.github.torrentdam.bencode.format.BencodeFormat
import fs2.io.net.Datagram
import fs2.io.net.DatagramSocket
import fs2.io.net.DatagramSocketGroup
import fs2.io.net.Network
import fs2.Chunk
import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger

class MessageSocket(socket: DatagramSocket[IO], logger: Logger[IO]) {
  import MessageSocket.Error

  def readMessage: IO[(SocketAddress[IpAddress], Message)] =
    for {
      datagram <- socket.read
      bc <- IO.fromEither(
        decode(datagram.bytes.toBitVector).leftMap(Error.BecodeSerialization.apply)
      )
      message <- IO.fromEither(
        summon[BencodeFormat[Message]]
          .read(bc)
          .leftMap(e => Error.MessageFormat(s"Filed to read message from bencode: $bc", e))
      )
      _ <- logger.trace(s"<<< ${datagram.remote} $message")
    } yield (datagram.remote, message)

  def writeMessage(address: SocketAddress[IpAddress], message: Message): IO[Unit] = {
    val bc = summon[BencodeFormat[Message]].write(message).toOption.get
    val bytes = encode(bc)
    val packet = Datagram(address, Chunk.byteVector(bytes.bytes))
    socket.write(packet) >> logger.trace(s">>> $address $message")
  }
}

object MessageSocket {

  def apply()(using
    logger: Logger[IO]
  ): Resource[IO, MessageSocket] =
    Network[IO]
      .openDatagramSocket()
      .map(socket => new MessageSocket(socket, logger))

  object Error {
    case class BecodeSerialization(cause: Throwable) extends Throwable(cause)
    case class MessageFormat(message: String, cause: Throwable) extends Throwable(message, cause)
  }
}
