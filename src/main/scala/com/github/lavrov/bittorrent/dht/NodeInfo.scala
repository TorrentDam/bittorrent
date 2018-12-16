package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import com.github.lavrov.bittorrent.InfoHash
import scodec.bits.ByteVector

import scala.util.Random

final case class NodeInfo(id: NodeId, address: InetSocketAddress)

final case class NodeId(bytes: ByteVector) {
  val int = BigInt(1, bytes.toArray)
}

object NodeId {

  private def distance(a: ByteVector, b: ByteVector): BigInt = BigInt(1, (a xor b).toArray)

  def distance(a: NodeId, b: NodeId): BigInt = distance(a.bytes, b.bytes)

  def distance(a: NodeId, b: InfoHash): BigInt = distance(a.bytes, b.bytes)

  def generate(rnd: Random): NodeId = {
    val buffer = Array.ofDim[Byte](20)
    rnd.nextBytes(buffer)
    NodeId(ByteVector(buffer))
  }
}
