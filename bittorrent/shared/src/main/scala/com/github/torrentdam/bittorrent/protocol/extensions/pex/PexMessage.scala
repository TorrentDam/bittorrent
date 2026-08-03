package com.github.torrentdam.bittorrent.protocol.extensions.pex

import cats.syntax.all.*
import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.format.*
import com.github.torrentdam.bittorrent.CompactPeer.CompactPeer6ListFormat
import com.github.torrentdam.bittorrent.CompactPeer.CompactPeerListFormat
import com.github.torrentdam.bittorrent.PeerInfo
import scodec.bits.ByteVector

final case class PexFlags(
  encrypted: Boolean,
  seeder: Boolean,
  utp: Boolean,
  holepunch: Boolean,
  connectable: Boolean
)

object PexFlags:

  val Empty: PexFlags = PexFlags(false, false, false, false, false)

  def fromByte(byte: Byte): PexFlags =
    PexFlags(
      encrypted = (byte & 0x01) != 0,
      seeder = (byte & 0x02) != 0,
      utp = (byte & 0x04) != 0,
      holepunch = (byte & 0x08) != 0,
      connectable = (byte & 0x10) != 0
    )

  def toByte(flags: PexFlags): Byte =
    var b: Byte = 0
    if flags.encrypted then b = (b | 0x01).toByte
    if flags.seeder then b = (b | 0x02).toByte
    if flags.utp then b = (b | 0x04).toByte
    if flags.holepunch then b = (b | 0x08).toByte
    if flags.connectable then b = (b | 0x10).toByte
    b

  val ListCodec: scodec.Codec[List[PexFlags]] =
    scodec.codecs.list(scodec.codecs.byte.xmap(fromByte, toByte))

  val ListFormat: BencodeFormat[List[PexFlags]] = encodedString(ListCodec)

end PexFlags

final case class PexMessage(
  added: List[PeerInfo],
  addedFlags: List[PexFlags],
  dropped: List[PeerInfo],
  added6: List[PeerInfo],
  dropped6: List[PeerInfo]
)

object PexMessage:

  private val format =
    (
      fieldOptional[List[PeerInfo]]("added")(using CompactPeerListFormat),
      fieldOptional[List[PexFlags]]("added.f")(using PexFlags.ListFormat),
      fieldOptional[List[PeerInfo]]("dropped")(using CompactPeerListFormat),
      fieldOptional[List[PeerInfo]]("added6")(using CompactPeer6ListFormat),
      fieldOptional[List[PeerInfo]]("dropped6")(using CompactPeer6ListFormat)
    ).imapN[PexMessage] {
      case (added, addedFlags, dropped, added6, dropped6) =>
        PexMessage(
          added.getOrElse(Nil),
          addedFlags.getOrElse(Nil),
          dropped.getOrElse(Nil),
          added6.getOrElse(Nil),
          dropped6.getOrElse(Nil)
        )
    }(m =>
      (
        m.added.some.filter(_.nonEmpty),
        m.addedFlags.some.filter(_.nonEmpty),
        m.dropped.some.filter(_.nonEmpty),
        m.added6.some.filter(_.nonEmpty),
        m.dropped6.some.filter(_.nonEmpty)
      )
    )

  def encode(message: PexMessage): ByteVector =
    bencode
      .encode(format.write(message).toOption.get)
      .toByteVector

  def decode(bytes: ByteVector): Either[Throwable, PexMessage] =
    for
      bc <- bencode.decode(bytes.bits).leftMap(Error.BencodeError.apply)
      message <-
        format
          .read(bc)
          .leftMap(Error.FormatError("Unable to parse PEX message", _))
    yield message

  object Error:
    case class BencodeError(cause: Throwable) extends Error(cause)
    case class FormatError(message: String, cause: Throwable) extends Error(message, cause)

end PexMessage