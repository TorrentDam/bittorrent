package com.github.lavrov.bittorrent.dht

import cats.Show.Shown
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Resource}
import cats.instances.all.*
import cats.syntax.all.*
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import fs2.Stream
import org.legogroup.woof.{Logger, given}
import Logger.withLogContext
import scala.concurrent.duration.DurationInt

trait PeerDiscovery {

  def discover(infoHash: InfoHash): Stream[IO, PeerInfo]
}

object PeerDiscovery {

  def make(
    routingTable: RoutingTable[IO],
    dhtClient: Client[IO]
  )(
    using
    logger: Logger[IO]
  ): Resource[IO, PeerDiscovery] =
    Resource.pure[IO, PeerDiscovery] {

      new PeerDiscovery {

        def discover(infoHash: InfoHash): Stream[IO, PeerInfo] = {

          Stream
            .eval {
              for {
                _ <- logger.info("Start discovery")
                initialNodes <- routingTable.findNodes(NodeId(infoHash.bytes))
                initialNodes <- initialNodes.take(100).toList.pure[IO]
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
              case _ => IO.unit
            }
        }
      }
    }

  private[dht] def start(
    infoHash: InfoHash,
    getPeers: (NodeInfo, InfoHash) => IO[Either[Response.Nodes, Response.Peers]],
    state: DiscoveryState,
    parallelism: Int = 10
  )(
    using logger: Logger[IO]
  ): Stream[IO, PeerInfo] = {

    Stream
      .repeatEval(state.next)
      .parEvalMapUnordered(parallelism) { nodeInfo =>
         getPeers(nodeInfo, infoHash).timeout(5.seconds).attempt <* logger.trace(s"Get peers $nodeInfo")
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
