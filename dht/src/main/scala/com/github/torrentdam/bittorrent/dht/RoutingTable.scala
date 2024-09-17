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

import scala.annotation.tailrec

trait RoutingTable[F[_]] {

  def insert(node: NodeInfo): F[Unit]
  
  def remove(nodeId: NodeId): F[Unit]

  def goodNodes(nodeId: NodeId): F[Iterable[NodeInfo]]

  def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit]

  def findPeers(infoHash: InfoHash): F[Option[Iterable[PeerInfo]]]

  def allNodes: F[Iterable[RoutingTable.Node]]
  
  def buckets: F[Iterable[RoutingTable.TreeNode.Bucket]]

  def updateGoodness(good: Set[NodeId], bad: Set[NodeId]): F[Unit]
  
  def lookup(nodeId: NodeId): F[Option[RoutingTable.Node]]
}

object RoutingTable {

  enum TreeNode:
    case Split(center: BigInt, lower: TreeNode, higher: TreeNode)
    case Bucket(from: BigInt, until: BigInt, nodes: Map[NodeId, Node])
    
  case class Node(id: NodeId, address: SocketAddress[IpAddress], isGood: Boolean, badCount: Int = 0):
    def toNodeInfo: NodeInfo = NodeInfo(id, address)

  object TreeNode {

    def empty: TreeNode =
      TreeNode.Bucket(
        from = BigInt(0),
        until = BigInt(1, ByteVector.fill(20)(-1: Byte).toArray),
        Map.empty
      )
  }

  val MaxNodes = 8

  import TreeNode.*

  extension (bucket: TreeNode)
    def insert(node: NodeInfo, selfId: NodeId): TreeNode =
      bucket match
        case b @ Split(center, lower, higher) =>
          if (node.id.int < center)
            b.copy(lower = lower.insert(node, selfId))
          else
            b.copy(higher = higher.insert(node, selfId))
        case b @ Bucket(from, until, nodes) =>
          if nodes.size >= MaxNodes && !nodes.contains(selfId)
          then  
            if selfId.int >= from && selfId.int < until
            then
              // split the bucket because it contains the self node
              val center = (from + until) / 2
              val splitNode = 
                Split(
                  center,
                  lower = Bucket(from, center, nodes.view.filterKeys(_.int < center).to(ListMap)),
                  higher = Bucket(center, until, nodes.view.filterKeys(_.int >= center).to(ListMap))
                )
              splitNode.insert(node, selfId)
            else
              // drop one node from the bucket
              val badNode = nodes.values.find(!_.isGood)
              badNode match
                case Some(badNode) => Bucket(from, until, nodes.removed(badNode.id)).insert(node, selfId)
                case None => b
          else
            Bucket(from, until, nodes.updated(node.id, Node(node.id, node.address, isGood = true)))

    def remove(nodeId: NodeId): TreeNode =
      bucket match
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

    @tailrec
    def findBucket(nodeId: NodeId): Bucket =
      bucket match
        case Split(center, lower, higher) =>
          if (nodeId.int < center)
            lower.findBucket(nodeId)
          else
            higher.findBucket(nodeId)
        case b: Bucket => b

    def findNodes(nodeId: NodeId): Iterable[Node] =
      bucket match
        case Split(center, lower, higher) =>
          if (nodeId.int < center)
            lower.findNodes(nodeId) ++ higher.findNodes(nodeId)
          else
            higher.findNodes(nodeId) ++ lower.findNodes(nodeId)
        case b: Bucket => b.nodes.values.to(LazyList)
        
    def buckets: Iterable[Bucket] =
      bucket match
        case b: Bucket => Iterable(b)
        case Split(_, lower, higher) => lower.buckets ++ higher.buckets
        
    def update(fn: Node => Node): TreeNode =
      bucket match
        case b @ Split(_, lower, higher) =>
          b.copy(lower = lower.update(fn), higher = higher.update(fn))
        case b @ Bucket(from, until, nodes) =>
          b.copy(nodes = nodes.view.mapValues(fn).to(ListMap))
          
  end extension

  def apply[F[_]: Concurrent](selfId: NodeId): F[RoutingTable[F]] =
    for {
      treeNodeRef <- Ref.of(TreeNode.empty)
      peers <- Ref.of(Map.empty[InfoHash, Set[PeerInfo]])
    } yield new RoutingTable[F] {

      def insert(node: NodeInfo): F[Unit] =
        treeNodeRef.update(_.insert(node, selfId))
        
      def remove(nodeId: NodeId): F[Unit] =
        treeNodeRef.update(_.remove(nodeId))

      def goodNodes(nodeId: NodeId): F[Iterable[NodeInfo]] =
        treeNodeRef.get.map(_.findNodes(nodeId).filter(_.isGood).map(_.toNodeInfo))

      def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): F[Unit] =
        peers.update { map =>
          map.updatedWith(infoHash) {
            case Some(set) => Some(set + peerInfo)
            case None      => Some(Set(peerInfo))
          }
        }

      def findPeers(infoHash: InfoHash): F[Option[Iterable[PeerInfo]]] =
        peers.get.map(_.get(infoHash))
        
      def allNodes: F[Iterable[Node]] =
        treeNodeRef.get.map(_.findNodes(selfId))
        
      def buckets: F[Iterable[TreeNode.Bucket]] =
        treeNodeRef.get.map(_.buckets)
        
      def updateGoodness(good: Set[NodeId], bad: Set[NodeId]): F[Unit] =
        treeNodeRef.update(
          _.update(node =>
            if good.contains(node.id) then node.copy(isGood = true, badCount = 0)
            else if bad.contains(node.id) then node.copy(isGood = false, badCount = node.badCount + 1)
            else node
          )
        )
        
      def lookup(nodeId: NodeId): F[Option[Node]] =
        treeNodeRef.get.map(_.findBucket(nodeId).nodes.get(nodeId))
    }
}
