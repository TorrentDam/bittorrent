package com.github.lavrov.bittorrent
import scodec.bits.ByteVector

import scala.util.Random

final case class PeerId(bytes: ByteVector) {
  override def toString() = s"PeerId(${bytes.decodeUtf8.getOrElse(bytes.toHex)})"
}

object PeerId {
  def apply(b0: Byte, b1: Byte, b2: Byte, b3: Byte, b4: Byte, b5: Byte): PeerId = {
    val bytes = Array[Byte](b0, b1, b2, b3, b4, b5)
    val hexPart = ByteVector(bytes).toHex
    new PeerId(ByteVector.encodeUtf8("-qB0000-" + hexPart).right.get)
  }

  private def apply(bytes: Array[Byte]): PeerId = {
    val hexPart = ByteVector(bytes).toHex
    new PeerId(ByteVector.encodeUtf8("-qB0000-" + hexPart).right.get)
  }

  def generate(rnd: Random): PeerId = {
    val buffer: Array[Byte] = Array.ofDim(6)
    rnd.nextBytes(buffer)
    PeerId(buffer)
  }
}
