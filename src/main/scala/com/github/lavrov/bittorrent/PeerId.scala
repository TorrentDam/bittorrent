package com.github.lavrov.bittorrent
import scodec.bits.ByteVector

import scala.util.Random

final case class PeerId(bytes: ByteVector) {
  override def toString() = s"PeerId(${bytes.decodeUtf8.getOrElse(bytes.toHex)})"
}

object PeerId {
  def generate(rnd: Random): PeerId = {
    val buffer: Array[Byte] = Array.ofDim(6)
    rnd.nextBytes(buffer)
    val randomId = ByteVector(buffer).toHex
    PeerId(ByteVector.encodeUtf8("-qB0000-" + randomId).right.get)
  }
}
