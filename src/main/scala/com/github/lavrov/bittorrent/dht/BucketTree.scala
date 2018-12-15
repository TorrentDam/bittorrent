package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

sealed trait BucketTree

object BucketTree {
  final case class Split(center: BigInt, lower: BucketTree, higher: BucketTree) extends BucketTree
  final case class Final(from: BigInt, until: BigInt, nodes: Map[NodeId, InetSocketAddress]) extends BucketTree

  val MaxNodes = 8

  def insert(bucket: BucketTree, node: DHTNode): BucketTree = bucket match {
    case b@Split(center, lower, higher) =>
      if (node.id.int < center)
        b.copy(lower = insert(lower, node))
      else
        b.copy(higher = insert(higher, node))
    case Final(from, until, nodes) =>
      if (nodes.size == MaxNodes) {
        val center = (from + until) / 2
        val splitBucket: BucketTree = Split(center, Final(from, center - 1, Map.empty), Final(center, until, Map.empty))
        nodes.updated(node.id, node.address).view.map(DHTNode.tupled).foldLeft(splitBucket)(insert)
      }
      else
        Final(from, until, nodes.updated(node.id, node.address))
  }

  def remove(bucket: BucketTree, nodeId: NodeId): BucketTree = bucket match {
    case b@Split(center, lower, higher) =>
      if (nodeId.int < center)
        (remove(lower, nodeId), higher) match {
          case (Final(lowerFrom, _, nodes), finalHigher: Final) if nodes.isEmpty =>
            finalHigher.copy(from = lowerFrom)
          case (l, _) =>
            b.copy(lower = l)
        }
      else
        (remove(higher, nodeId), lower) match {
          case (Final(_, higherUntil, nodes), finalLower: Final) if nodes.isEmpty =>
            finalLower.copy(until = higherUntil)
          case (h, _) =>
            b.copy(higher = h)
        }
    case b@Final(_, _, nodes) =>
      b.copy(nodes = nodes - nodeId)
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

