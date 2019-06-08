package com.github.lavrov.bittorrent.protocol.extensions

import cats.syntax.all._
import com.github.lavrov.bencode.reader._

case class ExtensionHandshake(
    extensions: Map[String, Long],
    metadataSize: Option[Long]
)

object ExtensionHandshake {
  val Format =
    (
      field[Map[String, Long]]("m"),
      optField[Long]("metadata_size")
    ).imapN(ExtensionHandshake.apply)(v => (v.extensions, v.metadataSize))
}
