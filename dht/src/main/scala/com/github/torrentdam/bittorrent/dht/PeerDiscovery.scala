package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.Resource
import cats.instances.all.*
import cats.syntax.all.*
import cats.effect.cps.{given, *}
import cats.Show.Shown
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo
import fs2.Stream
import org.legogroup.woof.given
import org.legogroup.woof.Logger

import scala.concurrent.duration.DurationInt
import Logger.withLogContext
import com.comcast.ip4s.{IpAddress, SocketAddress}

trait PeerDiscovery {

  def discover(infoHash: InfoHash): Stream[IO, PeerInfo]
  
  def findNodes(NodeId: NodeId): Stream[IO, NodeInfo]
}

object PeerDiscovery {

  def apply(
    routingTable: RoutingTable[IO],
    dhtClient: Client
  )(using
    logger: Logger[IO]
  ): PeerDiscovery = new {
    def discover(infoHash: InfoHash): Stream[IO, PeerInfo] = {
      Stream
        .eval {
          for {
            _ <- logger.info("Start discovery")
            initialNodes <- routingTable.goodNodes(NodeId(infoHash.bytes))
            initialNodes <- initialNodes.take(16).toList.pure[IO]
            _ <- logger.info(s"Received ${initialNodes.size} from own routing table")
            state <- DiscoveryState(initialNodes, infoHash)
          } yield {
            start(
              infoHash,
              dhtClient.getPeers,
              state
            )
          }
        }
        .flatten
        .onFinalizeCase {
          case Resource.ExitCase.Errored(e) => logger.error(s"Discovery failed with ${e.getMessage}")
          case _                            => IO.unit
        }
    }
    
    def findNodes(nodeId: NodeId): Stream[IO, NodeInfo] =
      Stream
        .eval(
          for 
            _ <- logger.info(s"Start finding nodes for $nodeId")
            initialNodes <- routingTable.goodNodes(nodeId)
            initialNodes <- initialNodes
              .take(16)
              .toList
              .sortBy(nodeInfo => NodeId.distance(nodeInfo.id, dhtClient.id))
              .pure[IO]
          yield
            FindNodesState(nodeId, initialNodes)
        )
        .flatMap { state =>
          Stream
            .unfoldEval(state)(_.next)
            .flatMap(Stream.emits)
        }

    case class FindNodesState(
                               targetId: NodeId,
                               nodesToQuery: List[NodeInfo],
                               usedNodes: Set[NodeInfo] = Set.empty,
                               respondedCount: Int = 0
                             ):
      def next: IO[Option[(List[NodeInfo], FindNodesState)]] = async[IO]:
        if nodesToQuery.isEmpty then
          none
        else
          val responses = nodesToQuery
            .parTraverse(nodeInfo =>
              dhtClient
                .findNodes(nodeInfo.address, targetId)
                .map(_.nodes.some)
                .timeout(5.seconds)
                .orElse(none.pure[IO])
                .tupleLeft(nodeInfo)
            )
            .await
          val respondedNodes = responses.collect { case (nodeInfo, Some(_)) => nodeInfo }
          val foundNodes = responses.collect { case (_, Some(nodes)) => nodes }.flatten
          val threshold =
            if respondedCount > 10
            then NodeId.distance(nodesToQuery.head.id, targetId)
            else NodeId.MaxValue
          val closeNodes = foundNodes
            .filterNot(usedNodes)
            .distinct
            .filter(nodeInfo => NodeId.distance(nodeInfo.id, targetId) < threshold)
            .sortBy(nodeInfo => NodeId.distance(nodeInfo.id, targetId))
            .take(10)
          (
            respondedNodes,
            copy(
              nodesToQuery = closeNodes,
              usedNodes = usedNodes ++ respondedNodes,
              respondedCount = respondedCount + respondedNodes.size)
          ).some
  }

  private[dht] def start(
                          infoHash: InfoHash,
                          getPeers: (SocketAddress[IpAddress], InfoHash) => IO[Either[Response.Nodes, Response.Peers]],
                          state: DiscoveryState,
                          parallelism: Int = 10
  )(using
    logger: Logger[IO]
  ): Stream[IO, PeerInfo] = {

    Stream
      .repeatEval(state.next)
      .parEvalMapUnordered(parallelism) { nodeInfo =>
        getPeers(nodeInfo.address, infoHash).timeout(5.seconds).attempt <* logger.trace(s"Get peers $nodeInfo")
      }
      .flatMap {
        case Right(response) =>
          response match {
            case Left(Response.Nodes(_, nodes)) =>
              Stream
                .eval(state.addNodes(nodes)) >> Stream.empty
            case Right(Response.Peers(_, peers)) =>
              Stream
                .eval(state.addPeers(peers))
                .flatMap(newPeers => Stream.emits(newPeers))
          }
        case Left(_) =>
          Stream.empty
      }
  }

  class DiscoveryState(ref: Ref[IO, DiscoveryState.Data], infoHash: InfoHash) {

    def next: IO[NodeInfo] =
      IO.deferred[NodeInfo]
        .flatMap { deferred =>
          ref.modify { state =>
            state.nodesToTry match {
              case x :: xs => (state.copy(nodesToTry = xs), x.pure[IO])
              case _ =>
                (state.copy(waiters = deferred :: state.waiters), deferred.get)
            }
          }.flatten
        }

    def addNodes(nodes: List[NodeInfo]): IO[Unit] = {
      ref.modify { state =>
        val newNodes = nodes.filterNot(state.seenNodes)
        val seenNodes = state.seenNodes ++ newNodes
        val nodesToTry = (newNodes ++ state.nodesToTry).sortBy(n => NodeId.distance(n.id, infoHash))
        val waiters = state.waiters.drop(nodesToTry.size)
        val newState =
          state.copy(
            nodesToTry = nodesToTry.drop(state.waiters.size),
            seenNodes = seenNodes,
            waiters = waiters
          )
        val io =
          state.waiters.zip(nodesToTry).map { case (deferred, nodeInfo) => deferred.complete(nodeInfo) }.sequence_
        (newState, io)
      }.flatten
    }

    type NewPeers = List[PeerInfo]

    def addPeers(peers: List[PeerInfo]): IO[NewPeers] = {
      ref
        .modify { state =>
          val newPeers = peers.filterNot(state.seenPeers)
          val newState = state.copy(
            seenPeers = state.seenPeers ++ newPeers
          )
          (newState, newPeers)
        }
    }
  }

  object DiscoveryState {

    case class Data(
      nodesToTry: List[NodeInfo],
      seenNodes: Set[NodeInfo],
      seenPeers: Set[PeerInfo] = Set.empty,
      waiters: List[Deferred[IO, NodeInfo]] = Nil
    )

    def apply(initialNodes: List[NodeInfo], infoHash: InfoHash): IO[DiscoveryState] =
      for {
        ref <- IO.ref(Data(initialNodes, initialNodes.toSet))
      } yield {
        new DiscoveryState(ref, infoHash)
      }
  }

  case class ExhaustedNodeList() extends Exception
}
