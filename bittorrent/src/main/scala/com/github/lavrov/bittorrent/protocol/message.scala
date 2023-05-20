package com.github.lavrov.bittorrent.protocol.message

import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.PeerId
import scala.util.chaining.*
import scodec.bits.ByteVector
import scodec.codecs.*
import scodec.Codec

final case class Handshake(
  extensionProtocol: Boolean,
  infoHash: InfoHash,
  peerId: PeerId
)

object Handshake {
  val ProtocolStringCodec: Codec[Unit] = uint8.unit(19) ~> fixedSizeBytes(
    19,
    utf8.unit("BitTorrent protocol")
  )
  val ReserveCodec: Codec[Boolean] = bits(8 * 8).xmap(
    bv => bv.get(43),
    supported =>
      ByteVector
        .fill(8)(0)
        .toBitVector
        .pipe(v => if supported then v.set(43) else v)
  )
  val InfoHashCodec: Codec[InfoHash] = bytes(20).xmap(InfoHash(_), _.bytes)
  val PeerIdCodec: Codec[PeerId] = bytes(20).xmap(PeerId.apply, _.bytes)
  val HandshakeCodec: Codec[Handshake] =
    (ProtocolStringCodec ~> ReserveCodec :: InfoHashCodec :: PeerIdCodec).as
}

enum Message:
  case KeepAlive
  case Choke
  case Unchoke
  case Interested
  case NotInterested
  case Have(pieceIndex: Long)
  case Bitfield(bytes: ByteVector)
  case Request(index: Long, begin: Long, length: Long)
  case Piece(index: Long, begin: Long, bytes: ByteVector)
  case Cancel(index: Long, begin: Long, length: Long)
  case Port(port: Int)
  case Extended(id: Long, payload: ByteVector)

object Message {

  val MessageSizeCodec: Codec[Long] = uint32

  val MessageBodyCodec: Codec[Message] = {
    val KeepAliveCodec: Codec[KeepAlive.type] = provide(KeepAlive).complete

    val OtherMessagesCodec: Codec[Message] =
      discriminated[Message]
        .by(uint8)
        .caseP(0) { case m @ Choke => m }(identity)(provide(Choke))
        .caseP(1) { case m @ Unchoke => m }(identity)(provide(Unchoke))
        .caseP(2) { case m @ Interested => m }(identity)(provide(Interested))
        .caseP(3) { case m @ NotInterested => m }(identity)(provide(NotInterested))
        .caseP(4) { case Have(index) => index }(Have.apply)(uint32)
        .caseP(5) { case Bitfield(bytes) => bytes }(Bitfield.apply)(bytes)
        .caseP(6) { case m: Request => m }(identity)((uint32 :: uint32 :: uint32).as)
        .caseP(7) { case m: Piece => m }(identity)((uint32 :: uint32 :: bytes).as)
        .caseP(8) { case m: Cancel => m }(identity)((uint32 :: uint32 :: uint32).as)
        .caseP(9) { case Port(port) => port }(Port.apply)(uint16)
        .caseP(20) { case m: Extended => m }(identity)((ulong(8) :: bytes).as)

    choice(
      KeepAliveCodec.upcast,
      OtherMessagesCodec
    )
  }

  val MessageCodec: Codec[Message] = {
    variableSizeBytesLong(
      MessageSizeCodec,
      MessageBodyCodec
    )
  }
}
