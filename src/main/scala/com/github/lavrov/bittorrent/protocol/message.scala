package com.github.lavrov.bittorrent.protocol.message
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import scodec.Codec
import scodec.codecs._
import scodec.bits.ByteVector

final case class Handshake(
    protocolString: String,
    infoHash: InfoHash,
    peerId: PeerId
)

object Handshake {
  val ProtocolStringCodec: Codec[String] = variableSizeBytes(uint8, utf8)
  val ReserveCodec: Codec[Unit] = bytes(8).unit(ByteVector.fill(8)(0))
  val InfoHashCodec: Codec[InfoHash] = bytes(20).xmap(InfoHash, _.bytes)
  val PeerIdCodec: Codec[PeerId] = bytes(20).xmap(PeerId.apply, _.bytes)
  val HandshakeCodec: Codec[Handshake] = ((ProtocolStringCodec <~ ReserveCodec) :: InfoHashCodec :: PeerIdCodec).as
}

sealed trait Message

object Message {
  case object KeepAlive extends Message
  case object Choke extends Message
  case object Unchoke extends Message
  case object Interested extends Message
  case object NotInterested extends Message
  final case class Have(pieceIndex: Int) extends Message
  final case class Bitfield(bytes: ByteVector) extends Message
  final case class Request(index: Int, begin: Int, length: Int) extends Message
  final case class Piece(index: Int, begin: Int, bytes: ByteVector) extends Message
  final case class Cancel(index: Int, begin: Int, length: Int) extends Message
  final case class Port(port: Int) extends Message
}