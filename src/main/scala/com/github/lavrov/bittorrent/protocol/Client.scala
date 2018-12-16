package com.github.lavrov.bittorrent.protocol
import cats._
import cats.syntax.all._
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.github.lavrov.bittorrent.protocol.message.Handshake
import fs2.Chunk
import fs2.io.udp.{Packet, Socket}
import scodec.bits.{BitVector, ByteVector}

class Client[F[_]: Monad](selfId: PeerId, socket: Socket[F])(implicit M: MonadError[F, Throwable]) {

  def handshake(peerInfo: PeerInfo, infoHash: InfoHash): F[Handshake] = {
    val message = Handshake("BitTorrent protocol", ByteVector.fill(8)(0), infoHash, selfId)
    for {
      _ <- socket.write(
        Packet(
          peerInfo.address,
          Chunk.byteVector(Handshake.HandshakeCodec.encode(message).require.toByteVector)))
      packet <- socket.read()
      response <- M fromTry Handshake.HandshakeCodec
        .decodeValue(BitVector(packet.bytes.toArray))
        .toTry
    } yield response
  }
}
