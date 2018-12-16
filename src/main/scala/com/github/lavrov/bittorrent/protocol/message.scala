package com.github.lavrov.bittorrent.protocol.message
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import scodec.Codec
import scodec.codecs._
import scodec.bits.ByteVector

final case class Handshake(
    protocolString: String,
    reserve: ByteVector,
    infoHash: InfoHash,
    peerId: PeerId
)

object Handshake {
  val ProtocolStringCodec: Codec[String] = variableSizeBytes(uint8, ascii)
  val ReserveCodec: Codec[ByteVector] = bytes(8)
  val InfoHashCodec: Codec[InfoHash] = bytes(20).xmap(InfoHash, _.bytes)
  val PeerIdCodec: Codec[PeerId] = fixedSizeBytes(20, ascii).xmap(PeerId.apply, _.value)
  val HandshakeCodec: Codec[Handshake] = (ProtocolStringCodec :: ReserveCodec :: InfoHashCodec :: PeerIdCodec).as
}