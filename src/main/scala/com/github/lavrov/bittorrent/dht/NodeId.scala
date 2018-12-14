package com.github.lavrov.bittorrent.dht

import scodec.bits.ByteVector

import scala.util.Random

final case class NodeId(bytes: ByteVector) {
  val int = BigInt(1, bytes.toArray)
}

object NodeId {

  def distance(a: NodeId, b: NodeId): BigInt = BigInt(1, (a.bytes xor b.bytes).toArray)

  def generate(rnd: Random): NodeId = {
    val buffer = Array.ofDim[Byte](20)
    rnd.nextBytes(buffer)
    NodeId(ByteVector(buffer))
  }
}
