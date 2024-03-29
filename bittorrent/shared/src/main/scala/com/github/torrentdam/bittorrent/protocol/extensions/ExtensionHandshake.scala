package com.github.torrentdam.bittorrent.protocol.extensions

import cats.syntax.all.*
import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.format.*
import scodec.bits.ByteVector

case class ExtensionHandshake(
  extensions: Map[String, Long],
  metadataSize: Option[Long]
)

object ExtensionHandshake {

  private val format =
    (
      field[Map[String, Long]]("m"),
      fieldOptional[Long]("metadata_size")
    ).imapN(ExtensionHandshake.apply)(v => (v.extensions, v.metadataSize))

  def encode(handshake: ExtensionHandshake): ByteVector =
    bencode
      .encode(format.write(handshake).toOption.get)
      .toByteVector

  def decode(bytes: ByteVector): Either[Throwable, ExtensionHandshake] =
    for
      bc <-
        bencode
          .decode(bytes.bits)
          .leftMap(Error.BencodeError.apply)
      handshakeResponse <-
        ExtensionHandshake.format
          .read(bc)
          .leftMap(Error.HandshakeFormatError("Unable to parse handshake response", _))
    yield handshakeResponse

  object Error {
    case class BencodeError(cause: Throwable) extends Error(cause)
    case class HandshakeFormatError(message: String, cause: Throwable) extends Error(message, cause)
  }
}
