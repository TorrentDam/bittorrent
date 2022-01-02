package com.github.lavrov.bittorrent.dht

import cats.Show.Shown
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.{Concurrent, Resource}
import cats.instances.all.*
import cats.syntax.all.*
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
  )(
    using
    F: Concurrent[F],
    logger: StructuredLogger[F]
  ): Resource[F, PeerDiscovery[F]] =
    Resource.pure[F, PeerDiscovery[F]] {

      val logger0 = logger

      new PeerDiscovery[F] {

        def discover(infoHash: InfoHash): Stream[F, PeerInfo] = {

          given logger: Logger[F] =
            logger0.addContext(("infoHash", infoHash.toString: Shown))

          Stream
            .eval {
              for {
                _ <- logger.info("Start discovery")
                initialNodes <- routingTable.findNodes(NodeId(infoHash.bytes))
                initialNodes <- initialNodes.take(100).toList.pure[F]
                _ <- logger.info(s"Got ${initialNodes.size} from routing table")
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
              case _ => F.unit
            }
        }
      }
    }

  private[dht] def start[F[_]](
    infoHash: InfoHash,
    getPeers: (NodeInfo, InfoHash) => F[Either[Response.Nodes, Response.Peers]],
    state: DiscoveryState[F]
  )(
    using
    F: Concurrent[F],
    logger: Logger[F]
  ): Stream[F, PeerInfo] = {

    Stream
      .repeatEval(state.next)
      .parEvalMapUnordered(10) { nodeInfo =>
         getPeers(nodeInfo, infoHash).attempt <* logger.trace(s"Get peers $nodeInfo")
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

  class DiscoveryState[F[_]: Concurrent](ref: Ref[F, DiscoveryState.Data[F]], infoHash: InfoHash) {

    def next: F[NodeInfo] =
    Concurrent[F].deferred[NodeInfo]
      .flatMap { deferred =>
        ref.modify { state =>
          state.nodesToTry match {
            case x :: xs => (state.copy(nodesToTry = xs), x.pure[F])
            case _ =>
              (state.copy(waiters = deferred :: state.waiters), deferred.get)
          }
        }.flatten
    }

    def addNodes(nodes: List[NodeInfo]): F[Unit] = {
      ref
        .modify { state =>
          val (seenNodes, newNodes) = {
            val newNodes = nodes.filterNot(state.seenNodes)
            (state.seenNodes ++ newNodes, newNodes)
          }
          val nodesToTry = (newNodes ++ state.nodesToTry).sortBy(n => NodeId.distance(n.id, infoHash))
          val waiters = state.waiters.drop(nodesToTry.size)
          val newState =
            state.copy(
              nodesToTry = nodesToTry.drop(state.waiters.size),
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

    type NewPeers = List[PeerInfo]

    def addPeers(peers: List[PeerInfo]): F[NewPeers] = {
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

    case class Data[F[_]](
      nodesToTry: List[NodeInfo],
      seenNodes: Set[NodeInfo],
      seenPeers: Set[PeerInfo] = Set.empty,
      waiters: List[Deferred[F, NodeInfo]] = Nil
    )

    def apply[F[_]: Concurrent](initialNodes: List[NodeInfo], infoHash: InfoHash): F[DiscoveryState[F]] =
      for {
        ref <- Ref.of(Data[F](initialNodes, initialNodes.toSet))
      } yield {
        new DiscoveryState(ref, infoHash)
      }
  }

  case class ExhaustedNodeList() extends Exception
}
