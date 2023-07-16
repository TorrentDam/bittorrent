package com.github.torrentdam.bittorrent
import scodec.bits.ByteVector

final case class InfoHash(bytes: ByteVector) {
  def toHex: String = bytes.toHex
  override def toString: String = toHex
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
