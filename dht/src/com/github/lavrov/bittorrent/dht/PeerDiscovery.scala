package com.github.lavrov.bittorrent.dht

import cats.Show.Shown
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ExitCase, Resource, Timer}
import cats.instances.all._
import cats.syntax.all._
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import fs2.Stream
import org.typelevel.log4cats.{Logger, StructuredLogger}

trait PeerDiscovery[F[_]] {

  def discover(infoHash: InfoHash): Stream[F, PeerInfo]
}

object PeerDiscovery {

  def make[F[_]](
    routingTable: RoutingTable[F],
    dhtClient: Client[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: StructuredLogger[F]): Resource[F, PeerDiscovery[F]] =
    Resource.pure[F, PeerDiscovery[F]] {

      val logger0 = logger

      new PeerDiscovery[F] {

        def discover(infoHash: InfoHash): Stream[F, PeerInfo] = {
          val logger = logger0.addContext(("infoHash", infoHash.toString: Shown))
          Stream
            .eval {
              for {
                _ <- logger.info("Start discovery")
                initialNodes <- routingTable.findNodes(NodeId(infoHash.bytes))
                initialNodes <- initialNodes.take(100).toList.pure[F]
                _ <- logger.info(s"Got ${initialNodes.size} from routing table")
                stateOps <- StateOps(initialNodes, infoHash)
              } yield {
                start(
                  infoHash,
                  dhtClient.getPeers,
                  stateOps,
                  logger
                )
              }
            }
            .flatten
            .onFinalizeCase {
              case ExitCase.Error(e) => logger.error(s"Discovery failed with ${e.getMessage}")
              case _ => F.unit
            }
        }
      }
    }

  private case class State[F[_]](
    nodesToTry: List[NodeInfo],
    seenNodes: Set[NodeInfo],
    seenPeers: Set[PeerInfo] = Set.empty,
    waiters: List[Deferred[F, NodeInfo]] = List.empty
  )

  private[dht] def start[F[_]](
    infoHash: InfoHash,
    getPeers: (NodeInfo, InfoHash) => F[Either[Response.Nodes, Response.Peers]],
    stateOps: StateOps[F],
    logger: Logger[F]
  )(implicit F: Concurrent[F]): Stream[F, PeerInfo] = {
    Stream
      .repeatEval(stateOps.next)
      .parEvalMapUnordered(10) { nodeInfo =>
        logger.trace(s"Get peers $nodeInfo") >> getPeers(nodeInfo, infoHash).attempt
      }
      .flatMap {
        case Right(response) =>
          response match {
            case Left(Response.Nodes(_, nodes)) =>
              Stream
                .eval(stateOps.updateNodeList(nodes)) >> Stream.empty
            case Right(Response.Peers(_, peers)) =>
              Stream
                .eval(stateOps.filterNewPeers(peers))
                .flatMap(Stream.emits)
          }
        case Left(_) =>
          Stream.empty
      }
  }

  class StateOps[F[_]: Concurrent](ref: Ref[F, State[F]], infoHash: InfoHash) {

    def next: F[NodeInfo] =
      ref
        .modify { state =>
          state.nodesToTry match {
            case x :: xs => (state.copy(nodesToTry = xs), x.pure[F])
            case _ =>
              val deferred = Deferred.unsafe[F, NodeInfo]
              (state.copy(waiters = deferred :: state.waiters), deferred.get)
          }
        }
        .flatten

    def updateNodeList(nodes: List[NodeInfo]): F[Unit] = {
      ref
        .modify { state =>
          val (seenNodes, newNodes) = {
            val newNodes = nodes.filterNot(state.seenNodes)
            (state.seenNodes ++ newNodes, newNodes)
          }
          val nodesToTry = (newNodes ++ state.nodesToTry)
            .sortBy(n => NodeId.distance(n.id, infoHash))
            .drop(state.waiters.size)
          val waiters = state.waiters.drop(nodesToTry.size)
          val newState =
            state.copy(
              nodesToTry = nodesToTry,
              seenNodes = seenNodes,
              waiters = waiters
            )
          val io = state.waiters.zip(nodesToTry)
            .map { case (deferred, nodeInfo) => deferred.complete(nodeInfo) }
            .sequence_
          (newState, io)
        }
        .flatten
    }


    def filterNewPeers(peers: List[PeerInfo]): F[List[PeerInfo]] = {
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

  object StateOps {

    def apply[F[_]: Concurrent](initialNodes: List[NodeInfo], infoHash: InfoHash): F[StateOps[F]] =
      for {
        ref <- Ref.of(State[F](initialNodes, initialNodes.toSet))
      } yield {
        new StateOps(ref, infoHash)
      }
  }

  case class ExhaustedNodeList() extends Exception
}
