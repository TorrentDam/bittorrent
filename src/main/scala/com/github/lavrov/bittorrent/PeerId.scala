package com.github.lavrov.bittorrent
import scodec.bits.ByteVector

import scala.util.Random

final case class PeerId(value: String)

object PeerId {
  def generate(rnd: Random): PeerId = {
    val buffer: Array[Byte] = Array.ofDim(6)
    rnd.nextBytes(buffer)
    val randomId = ByteVector(buffer).toHex
    PeerId("-ZZ0000-" + randomId)
  }
}
