package com.github.lavrov.bittorrent.protocol.extensions

import cats.syntax.all._
import com.github.lavrov.bencode.format._
import scodec.bits.ByteVector
import com.github.lavrov.bencode.BencodeCodec
import scodec.Err

case class ExtensionHandshake(
  extensions: Map[String, Long],
  metadataSize: Option[Long]
)

object ExtensionHandshake {
  val Format =
    (
      field[Map[String, Long]]("m"),
      fieldOptional[Long]("metadata_size")
    ).imapN(ExtensionHandshake.apply)(v => (v.extensions, v.metadataSize))

  def encode(handshake: ExtensionHandshake): ByteVector =
    BencodeCodec.instance
      .encode(ExtensionHandshake.Format.write(handshake).right.get)
      .require
      .toByteVector

  def decode(bytes: ByteVector): Either[Throwable, ExtensionHandshake] =
    for {
      bc <- BencodeCodec.instance
        .decodeValue(bytes.bits)
        .toEither
        .leftMap(Error.BencodeError)
      handshakeResponse <- ExtensionHandshake.Format
        .read(bc)
        .leftMap(Error.HandshakeFormatError("Unable to parse handshake response", _))
    } yield handshakeResponse
  object Error {
    case class BencodeError(err: Err) extends Error(err.messageWithContext)
    case class HandshakeFormatError(message: String, cause: Throwable) extends Error(message, cause)
  }
}
