package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats._
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.syntax.all._
import fs2.io.udp.{Packet, Socket}
import fs2.Chunk
import fs2.io.udp.AsynchronousSocketGroup
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{BitVector, ByteVector}

import scala.concurrent.duration.DurationInt

import com.github.lavrov.bencode.{decode, encode}
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}

class Client[F[_]](val selfId: NodeId, socket: Socket[F], logger: Logger[F])(
    implicit F: MonadError[F, Throwable]
) {

  def readMessage: F[Message] =
    for {
      packet <- socket.read(10.seconds.some)
      bc <- F.fromEither(
        decode(BitVector(packet.bytes.toArray)).left.map(e => new Exception(e.message))
      )
      message <- F.fromEither(
        Message.MessageFormat
          .read(bc)
          .left
          .map(e => new Exception(s"Filed to read message: $e. Bencode: $bc"))
      )
    } yield message

  def sendMessage(address: InetSocketAddress, message: Message): F[Unit] =
    for {
      bc <- F.fromEither(Message.MessageFormat.write(message).left.map(new Exception(_)))
      bytes = encode(bc)
      _ <- socket.write(Packet(address, Chunk.byteVector(bytes.bytes)))

    } yield ()
}

object Client {
  def apply[F[_]](selfId: NodeId)(
      implicit F: Concurrent[F],
      cs: ContextShift[F],
      asg: AsynchronousSocketGroup
  ): Resource[F, Client[F]] =
    Socket[F](address = new InetSocketAddress(6881)).evalMap(
      socket =>
        for {
          logger <- Slf4jLogger.fromClass[F](getClass)
        } yield new Client(selfId, socket, logger)
    )
}
