package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Ref
import cats.effect.Sync
import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo
import scala.collection.immutable.ListMap
import scodec.bits.ByteVector

trait RoutingTable[F[_]] {

  def insert(node: NodeInfo): F[Unit]

  def findNodes(nodeId: NodeId): F[Iterable[NodeInfo]]

  def findBucket(nodeId: NodeId): F[List[NodeInfo]]

  def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit]

  def findPeers(infoHash: InfoHash): F[Option[Iterable[PeerInfo]]]
}

object RoutingTable {

  enum TreeNode:
    case Split(center: BigInt, lower: TreeNode, higher: TreeNode)
    case Bucket(from: BigInt, until: BigInt, nodes: ListMap[NodeId, SocketAddress[IpAddress]])

  object TreeNode {

    def empty: TreeNode =
      TreeNode.Bucket(
        from = BigInt(0),
        until = BigInt(1, ByteVector.fill(20)(-1: Byte).toArray),
        ListMap.empty
      )
  }

  val MaxNodes = 8

  import TreeNode.*

  extension (bucket: TreeNode) {

    def insert(node: NodeInfo, selfId: NodeId): TreeNode =
      bucket match {
        case b @ Split(center, lower, higher) =>
          if (node.id.int < center)
            b.copy(lower = lower.insert(node, selfId))
          else
            b.copy(higher = higher.insert(node, selfId))
        case Bucket(from, until, nodes) =>
          if (nodes.size == MaxNodes) {
            val tree =
              if (selfId.int >= from && selfId.int < until) {
                val center = (from + until) / 2
                val splitBucket: TreeNode =
                  Split(center, Bucket(from, center - 1, ListMap.empty), Bucket(center, until, ListMap.empty))
                nodes.view.map(NodeInfo.apply.tupled).foldLeft(splitBucket)(_.insert(_, selfId))
              } else {
                Bucket(from, until, nodes.init)
              }
            tree.insert(node, selfId)
          } else Bucket(from, until, nodes.updated(node.id, node.address))
      }

    def remove(nodeId: NodeId): TreeNode =
      bucket match {
        case b @ Split(center, lower, higher) =>
          if (nodeId.int < center)
            (lower.remove(nodeId), higher) match {
              case (Bucket(lowerFrom, _, nodes), finalHigher: Bucket) if nodes.isEmpty =>
                finalHigher.copy(from = lowerFrom)
              case (l, _) =>
                b.copy(lower = l)
            }
          else
            (higher.remove(nodeId), lower) match {
              case (Bucket(_, higherUntil, nodes), finalLower: Bucket) if nodes.isEmpty =>
                finalLower.copy(until = higherUntil)
              case (h, _) =>
                b.copy(higher = h)
            }
        case b @ Bucket(_, _, nodes) =>
          b.copy(nodes = nodes - nodeId)
      }

    def findBucket(nodeId: NodeId): Bucket =
      bucket match {
        case Split(center, lower, higher) =>
          if (nodeId.int < center)
            lower.findBucket(nodeId)
          else
            higher.findBucket(nodeId)
        case b: Bucket => b
      }

    def findNodes(nodeId: NodeId): Iterable[NodeInfo] =
      bucket match {
        case Split(center, lower, higher) =>
          if (nodeId.int < center)
            lower.findNodes(nodeId) ++ higher.findNodes(nodeId)
          else
            higher.findNodes(nodeId) ++ lower.findNodes(nodeId)
        case b: Bucket => b.nodes.toIterable.map(NodeInfo.apply.tupled)
      }

  }

  def apply[F[_]: Concurrent](selfId: NodeId): F[RoutingTable[F]] =
    for {
      treeNodeRef <- Ref.of(TreeNode.empty)
      peers <- Ref.of(Map.empty[InfoHash, Set[PeerInfo]])
    } yield new RoutingTable[F] {

      def insert(node: NodeInfo): F[Unit] =
        treeNodeRef.update(_.insert(node, selfId))

      def findNodes(nodeId: NodeId): F[Iterable[NodeInfo]] =
        treeNodeRef.get.map(_.findNodes(nodeId))

      def findBucket(nodeId: NodeId): F[List[NodeInfo]] =
        treeNodeRef.get.map(_.findBucket(nodeId).nodes.map(NodeInfo.apply.tupled).toList)

      def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit] =
        peers.update { map =>
          map.updatedWith(infoHash) {
            case Some(set) => Some(set + peerInfo)
            case None      => Some(Set(peerInfo))
          }
        }

      def findPeers(infoHash: InfoHash): F[Option[Iterable[PeerInfo]]] =
        peers.get.map(_.get(infoHash))
    }

}
