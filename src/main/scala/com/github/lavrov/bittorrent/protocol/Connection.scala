package com.github.lavrov.bittorrent.protocol
import cats._
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.message.{Handshake, Message}
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import fs2.Chunk
import fs2.io.tcp.Socket
import scodec.bits.ByteVector

import scala.concurrent.duration._

class Connection[F[_]: Monad: Concurrent: Sync](socket: Socket[F])(implicit M: MonadError[F, Throwable]) {

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

  def send(message: Message): F[Unit] =
    for {
      _ <- Sync[F].delay(println(s"Sending $message"))
      _ <- socket.write(Chunk.byteVector(Message.MessageCodec.encode(message).require.toByteVector))
      _ <- Sync[F].delay(println(s"Sent: $message"))
    }
    yield ()

  def receive: F[Message] =
    for {
      _ <- Sync[F].delay(println("Receiving..."))
      maybeChunk <- socket.read(32 * 1024)
      chunk <- M.fromOption(maybeChunk, new Exception("Connection was closed unexpectedly"))
      bv = ByteVector(chunk.toArray)
      message <- M.fromTry(
        Message.MessageCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
      _ <- Sync[F].delay(println(s"Received $message"))
    }
    yield message

}
