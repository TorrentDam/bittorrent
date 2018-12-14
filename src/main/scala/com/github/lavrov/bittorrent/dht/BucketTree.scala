package com.github.lavrov.bittorrent.dht


sealed trait BucketTree

object BucketTree {
  final case class Split(center: BigInt, lower: BucketTree, higher: BucketTree) extends BucketTree
  final case class Final(from: BigInt, until: BigInt, nodes: List[NodeId]) extends BucketTree

  val MaxNodes = 8

  def insert(bucket: BucketTree, node: NodeId): BucketTree = bucket match {
    case b@Split(center, lower, higher) =>
      if (node.int < center)
        b.copy(lower = insert(lower, node))
      else
        b.copy(higher = insert(higher, node))
    case Final(from, until, nodes) =>
      if (nodes.size == MaxNodes) {
        val center = (from + until) / 2
        val splitBucket: BucketTree = Split(center, Final(from, center - 1, Nil), Final(center, until, Nil))
        (node :: nodes).foldLeft(splitBucket)(insert)
      }
      else
        Final(from, until, node :: nodes)
  }

  def remove(bucket: BucketTree, node: NodeId): BucketTree = bucket match {
    case b@Split(center, lower, higher) =>
      if (node.int < center)
        (remove(lower, node), higher) match {
          case (Final(lowerFrom, _, Nil), finalHigher: Final) =>
            finalHigher.copy(from = lowerFrom)
          case (l, _) =>
            b.copy(lower = l)
        }
      else
        (remove(higher, node), lower) match {
          case (Final(_, higherUntil, Nil), finalLower: Final) =>
            finalLower.copy(until = higherUntil)
          case (h, _) =>
            b.copy(higher = h)
        }
    case b@Final(_, _, nodes) =>
      b.copy(nodes = node :: nodes)
  }

  def findBucket(bucketTree: BucketTree, nodeId: NodeId): Final = bucketTree match {
    case Split(center, lower, higher) =>
      if (nodeId.int < center)
        findBucket(lower, nodeId)
      else
        findBucket(higher, nodeId)
    case b: Final => b
  }
}

