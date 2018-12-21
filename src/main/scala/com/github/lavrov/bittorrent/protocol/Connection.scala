package com.github.lavrov.bittorrent.protocol
import cats._
import cats.effect.Concurrent
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import fs2.Chunk
import fs2.io.tcp.Socket
import scodec.bits.ByteVector

import scala.concurrent.duration._

class Connection[F[_]: Monad: Concurrent](socket: Socket[F])(implicit M: MonadError[F, Throwable]) {

  def handshake(selfId: PeerId, infoHash: InfoHash): F[Handshake] = {
    val message = Handshake("BitTorrent protocol", infoHash, selfId)
    for {
      _ <- socket.write(
        bytes = Chunk.byteVector(Handshake.HandshakeCodec.encode(message).require.toByteVector),
        timeout = Some(5.seconds)
      )
      maybeBytes <- socket.read(1024, timeout = Some(5.seconds))
      bytes <- M.fromOption(
        maybeBytes,
        new Exception("Connection was closed unexpectedly")
      )
      bv = ByteVector(bytes.toArray)
      response <- M.fromTry(
        Handshake.HandshakeCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
    } yield response
  }

  def send(message: Message): F[Unit] = ???

  def receive: F[Message] = ???
}
