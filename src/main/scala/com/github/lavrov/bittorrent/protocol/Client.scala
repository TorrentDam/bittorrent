package com.github.lavrov.bittorrent.protocol
import cats._
import cats.effect.Concurrent
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.message.Handshake
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import fs2.Chunk
import fs2.io.tcp.Socket
import scodec.bits.ByteVector

class Client[F[_]: Monad: Concurrent](selfId: PeerId, socket: Socket[F])(
    implicit M: MonadError[F, Throwable]) {

  def handshake(infoHash: InfoHash): F[Handshake] = {
    val message = Handshake("BitTorrent protocol", infoHash, selfId)
    for {
      _ <- socket.write(
        Chunk.byteVector(Handshake.HandshakeCodec.encode(message).require.toByteVector))
      maybeBytes <- socket.read(1024)
      bv = ByteVector(maybeBytes.get.toArray)
      response <- M.fromTry(
        Handshake.HandshakeCodec
          .decodeValue(bv.toBitVector)
          .toTry
      )
    } yield response
  }
}
