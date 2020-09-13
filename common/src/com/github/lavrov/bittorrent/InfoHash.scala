package com.github.lavrov.bittorrent
import scodec.bits.ByteVector

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
}
