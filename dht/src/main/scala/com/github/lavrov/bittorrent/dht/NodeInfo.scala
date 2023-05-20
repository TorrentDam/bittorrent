package com.github.lavrov.bittorrent.dht

import cats.effect.std.Random
import cats.syntax.all.*
import cats.Monad
import com.comcast.ip4s.*
import com.github.lavrov.bittorrent.InfoHash
import scodec.bits.ByteVector

final case class NodeInfo(id: NodeId, address: SocketAddress[IpAddress])

final case class NodeId(bytes: ByteVector) {
  val int = BigInt(1, bytes.toArray)
}

object NodeId {

  private def distance(a: ByteVector, b: ByteVector): BigInt = BigInt(1, (a.xor(b)).toArray)

  def distance(a: NodeId, b: NodeId): BigInt = distance(a.bytes, b.bytes)

  def distance(a: NodeId, b: InfoHash): BigInt = distance(a.bytes, b.bytes)

  def generate[F[_]](using Random[F], Monad[F]): F[NodeId] = {
    for bytes <- Random[F].nextBytes(20)
    yield NodeId(ByteVector.view(bytes))
  }
}
