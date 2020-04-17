package com.github.lavrov.bittorrent.app.domain

import scodec.bits.ByteVector
import upickle.default.ReadWriter

final case class InfoHash(bytes: ByteVector) {
  override def toString = bytes.toHex
}

object InfoHash {

  val fromString: PartialFunction[String, InfoHash] =
    Function.unlift { s =>
      for {
        b <- ByteVector.fromHexDescriptive(s.toLowerCase).toOption
        _ <- if (b.length == 20) Some(()) else None
      } yield InfoHash(b)
    }

  implicit val infoHashRW: ReadWriter[InfoHash] =
    implicitly[ReadWriter[String]].bimap(
      infoHash => infoHash.bytes.toHex,
      string => InfoHash(ByteVector.fromValidHex(string))
    )
}
