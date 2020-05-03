package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

sealed trait RoutingTable

object RoutingTable {
  final case class Split(center: BigInt, lower: RoutingTable, higher: RoutingTable) extends RoutingTable
  final case class Bucket(from: BigInt, until: BigInt, nodes: Map[NodeId, InetSocketAddress]) extends RoutingTable

  val MaxNodes = 8

  def insert(bucket: RoutingTable, node: NodeInfo): RoutingTable =
    bucket match {
      case b @ Split(center, lower, higher) =>
        if (node.id.int < center)
          b.copy(lower = insert(lower, node))
        else
          b.copy(higher = insert(higher, node))
      case Bucket(from, until, nodes) =>
        if (nodes.size == MaxNodes) {
          val center = (from + until) / 2
          val splitBucket: RoutingTable =
            Split(center, Bucket(from, center - 1, Map.empty), Bucket(center, until, Map.empty))
          nodes.updated(node.id, node.address).view.map(NodeInfo.tupled).foldLeft(splitBucket)(insert)
        }
        else
          Bucket(from, until, nodes.updated(node.id, node.address))
    }

  def remove(bucket: RoutingTable, nodeId: NodeId): RoutingTable =
    bucket match {
      case b @ Split(center, lower, higher) =>
        if (nodeId.int < center)
          (remove(lower, nodeId), higher) match {
            case (Bucket(lowerFrom, _, nodes), finalHigher: Bucket) if nodes.isEmpty =>
              finalHigher.copy(from = lowerFrom)
            case (l, _) =>
              b.copy(lower = l)
          }
        else
          (remove(higher, nodeId), lower) match {
            case (Bucket(_, higherUntil, nodes), finalLower: Bucket) if nodes.isEmpty =>
              finalLower.copy(until = higherUntil)
            case (h, _) =>
              b.copy(higher = h)
          }
      case b @ Bucket(_, _, nodes) =>
        b.copy(nodes = nodes - nodeId)
    }

  def findBucket(bucketTree: RoutingTable, nodeId: NodeId): Bucket =
    bucketTree match {
      case Split(center, lower, higher) =>
        if (nodeId.int < center)
          findBucket(lower, nodeId)
        else
          findBucket(higher, nodeId)
      case b: Bucket => b
    }
}
