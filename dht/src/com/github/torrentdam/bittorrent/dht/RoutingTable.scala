package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.effect.kernel.Clock
import cats.effect.kernel.Ref
import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scodec.bits.ByteVector

import scala.annotation.tailrec

trait RoutingTable {

  def insert(node: NodeInfo): IO[Unit]

  def remove(nodeId: NodeId): IO[Unit]

  def goodNodes(nodeId: NodeId): IO[Iterable[NodeInfo]]

  def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): IO[Unit]

  def findPeers(infoHash: InfoHash): IO[Option[Iterable[PeerInfo]]]

  def allNodes: IO[Iterable[RoutingTable.Node]]

  def buckets: IO[Iterable[RoutingTable.TreeNode.Bucket]]

  def updateGoodness(good: Set[NodeId], bad: Set[NodeId]): IO[Unit]

  def lookup(nodeId: NodeId): IO[Option[RoutingTable.Node]]
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

  /** Maximum number of info hashes with tracked peers. Bounds the peers map so public announce_peer traffic cannot exhaust the heap. */
  val MaxTrackedInfoHashes = 256

  /** Maximum number of peers tracked per info hash. */
  val MaxPeersPerInfoHash = 32

  /** How long a peer entry is served; matches the ~30 minute DHT announce TTL. */
  val PeerEntryTtl: FiniteDuration = 30.minutes

  /** Peers announced for one info hash, in announce order, plus the time of the most recent announce. */
  case class PeerEntry(announcedAt: FiniteDuration, peers: ListMap[PeerInfo, Unit]):
    def isFresh(now: FiniteDuration): Boolean = now - announcedAt < PeerEntryTtl

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

  def apply(selfId: NodeId)(using clock: Clock[IO]): IO[RoutingTable] =
    for {
      treeNodeRef <- Ref.of[IO, TreeNode](TreeNode.empty)
      peers <- Ref.of[IO, ListMap[InfoHash, PeerEntry]](ListMap.empty)
    } yield new RoutingTable {

      def insert(node: NodeInfo): IO[Unit] =
        treeNodeRef.update(_.insert(node, selfId))

      def remove(nodeId: NodeId): IO[Unit] =
        treeNodeRef.update(_.remove(nodeId))

      def goodNodes(nodeId: NodeId): IO[Iterable[NodeInfo]] =
        treeNodeRef.get.map(_.findNodes(nodeId).filter(_.isGood).map(_.toNodeInfo))

      def addPeer(infoHash: InfoHash, peerInfo: PeerInfo): IO[Unit] =
        clock.realTime.flatMap { now =>
          peers.update { map =>
            // drop expired entries, then record the announce, keeping both maps bounded and most-recent last
            val fresh = map.filter { case (_, entry) => entry.isFresh(now) }
            val entry = fresh.get(infoHash) match
              case Some(e) => e.copy(announcedAt = now, peers = (e.peers - peerInfo) + (peerInfo -> ()))
              case None    => PeerEntry(now, ListMap(peerInfo -> ()))
            val boundedPeers =
              if entry.peers.size > MaxPeersPerInfoHash
              then entry.peers.drop(entry.peers.size - MaxPeersPerInfoHash)
              else entry.peers
            val updated = (fresh - infoHash) + (infoHash -> entry.copy(peers = boundedPeers))
            if updated.size > MaxTrackedInfoHashes
            then updated.drop(updated.size - MaxTrackedInfoHashes)
            else updated
          }
        }

      // expired entries are pruned lazily on addPeer, so reads stay a plain lookup without touching the clock
      def findPeers(infoHash: InfoHash): IO[Option[Iterable[PeerInfo]]] =
        peers.get.map(_.get(infoHash).map(_.peers.keys))

      def allNodes: IO[Iterable[Node]] =
        treeNodeRef.get.map(_.findNodes(selfId))

      def buckets: IO[Iterable[TreeNode.Bucket]] =
        treeNodeRef.get.map(_.buckets)

      def updateGoodness(good: Set[NodeId], bad: Set[NodeId]): IO[Unit] =
        treeNodeRef.update(
          _.update(node =>
            if good.contains(node.id) then node.copy(isGood = true, badCount = 0)
            else if bad.contains(node.id) then node.copy(isGood = false, badCount = node.badCount + 1)
            else node
          )
        )

      def lookup(nodeId: NodeId): IO[Option[Node]] =
        treeNodeRef.get.map(_.findBucket(nodeId).nodes.get(nodeId))
    }
}
