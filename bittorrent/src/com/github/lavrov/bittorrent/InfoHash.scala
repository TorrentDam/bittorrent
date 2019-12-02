package com.github.lavrov.bittorrent
import scodec.bits.ByteVector

final case class InfoHash(bytes: ByteVector)

object InfoHashFromString {
  def unapply(s: String): Option[InfoHash] =
    for {
      b <- ByteVector.fromHexDescriptive(s).toOption
      _ <- if (b.length == 20) Some(()) else None
    } yield InfoHash(b)
}
