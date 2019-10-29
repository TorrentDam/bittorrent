package com.github.lavrov.bittorrent.protocol.message

import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import scodec.Codec
import scodec.codecs._
import scodec.bits.ByteVector

import scala.util.chaining._

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
    bv => bv get 43,
    supported =>
      ByteVector
        .fill(8)(0)
        .toBitVector
        .pipe(
          v =>
            if (supported) v.set(43)
            else v
        )
  )
  val InfoHashCodec: Codec[InfoHash] = bytes(20).xmap(InfoHash, _.bytes)
  val PeerIdCodec: Codec[PeerId] = bytes(20).xmap(PeerId.apply, _.bytes)
  val HandshakeCodec: Codec[Handshake] =
    (ProtocolStringCodec ~> ReserveCodec :: InfoHashCodec :: PeerIdCodec).as
}

sealed trait Message

object Message {
  case object KeepAlive extends Message
  case object Choke extends Message
  case object Unchoke extends Message
  case object Interested extends Message
  case object NotInterested extends Message
  final case class Have(pieceIndex: Long) extends Message
  final case class Bitfield(bytes: ByteVector) extends Message
  final case class Request(index: Long, begin: Long, length: Long) extends Message
  final case class Piece(index: Long, begin: Long, bytes: ByteVector) extends Message
  final case class Cancel(index: Long, begin: Long, length: Long) extends Message
  final case class Port(port: Int) extends Message
  final case class Extended(id: Long, payload: ByteVector) extends Message

  val MessageSizeCodec: Codec[Long] = uint32

  val MessageBodyCodec: Codec[Message] = {
    val KeepAliveCodec: Codec[KeepAlive.type] = provide(KeepAlive).complete

    val OtherMessagesCodec: Codec[Message] =
      discriminated[Message]
        .by(uint8)
        .|(0) { case m @ Choke => m }(identity)(provide(Choke))
        .|(1) { case m @ Unchoke => m }(identity)(provide(Unchoke))
        .|(2) { case m @ Interested => m }(identity)(provide(Interested))
        .|(3) { case m @ NotInterested => m }(identity)(provide(NotInterested))
        .|(4) { case Have(index) => index }(Have)(uint32)
        .|(5) { case Bitfield(bytes) => bytes }(Bitfield)(bytes)
        .|(6) { case m: Request => m }(identity)((uint32 :: uint32 :: uint32).as)
        .|(7) { case m: Piece => m }(identity)((uint32 :: uint32 :: bytes).as)
        .|(8) { case m: Cancel => m }(identity)((uint32 :: uint32 :: uint32).as)
        .|(9) { case Port(port) => port }(Port)(uint16)
        .|(20) { case m: Extended => m }(identity)((ulong(8) :: bytes).as)

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
