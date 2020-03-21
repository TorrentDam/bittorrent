package com.github.lavrov.bittorrent
import scodec.bits.ByteVector

final case class InfoHash(bytes: ByteVector) {
  override def toString = bytes.toHex
}
