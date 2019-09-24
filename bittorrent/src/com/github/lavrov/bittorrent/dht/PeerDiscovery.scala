package com.github.lavrov.bittorrent.dht

import cats.effect.{Concurrent, ConcurrentEffect, Timer}
import cats.instances.all._
import cats.syntax.all._
import com.github.lavrov.bittorrent.dht.message.Response
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.timwspence.cats.stm.{STM, TVar}

object PeerDiscovery {

  def start[F[_]](infoHash: InfoHash, client: Client[F])(
      implicit F: ConcurrentEffect[F], timer: Timer[F]
  ): F[Stream[F, PeerInfo]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      _ <- logger.info("Start discovery")
      bootstrapNode <- RoutingTableManager.bootstrap(client, logger)
      tvar <- TVar.of(State(bootstrapNode :: Nil)).commit[F]
    } yield {
      val next = STM.atomically {
        tvar.get
          .flatMap { state =>
            val check = STM.check(state.nodesToTry.nonEmpty)
            def update: STM[Unit] = {
              val state1 = state.copy(nodesToTry = state.nodesToTry.tail)
              tvar.set(state1)
            }
            check >> update as state.nodesToTry.headOption
          }
      }
      def update(nodes: List[NodeInfo]): F[Unit] =
        tvar.modify(updateNodeList(nodes, infoHash)).commit[F]
      def filter(peers: List[PeerInfo]): F[List[PeerInfo]] = {
        for {
          state <- tvar.get
          (state1, newPeers) = filterNewPeers(peers)(state)
          _ <- tvar.set(state1)
        } yield newPeers
      }.commit[F]

      start(
        infoHash,
        next,
        update,
        filter,
        client.getPeers,
        logger
      )
    }
  }

  case class State(
      nodesToTry: List[NodeInfo],
      seenNodes: Set[NodeInfo] = Set.empty,
      seenPeers: Set[PeerInfo] = Set.empty
  )

  private[dht] def start[F[_]](
      infoHash: InfoHash,
      nextNode: F[Option[NodeInfo]],
      updateNodeList: List[NodeInfo] => F[Unit],
      filter: List[PeerInfo] => F[List[PeerInfo]],
      getPeers: (NodeInfo, InfoHash) => F[Either[Response.Nodes, Response.Peers]],
      logger: Logger[F]
  )(implicit F: Concurrent[F]): Stream[F, PeerInfo] = {
    Stream
      .repeatEval(nextNode.flatMap {
        case Some(nodeInfo) => F.pure(nodeInfo)
        case None => F.raiseError[NodeInfo](ExhaustedNodeList())
      })
      .parEvalMapUnordered(10) { nodeInfo =>
        logger.debug(s"Get peers $nodeInfo") >> getPeers(nodeInfo, infoHash).attempt
      }
      .flatMap {
        case Right(response) =>
          response match {
            case Left(Response.Nodes(_, nodes)) =>
              Stream
                .eval(updateNodeList(nodes)) >> Stream.empty
            case Right(Response.Peers(_, peers)) =>
              Stream
                .eval(filter(peers))
                .flatMap(Stream.emits)
          }
        case Left(e) =>
          Stream.empty
      }
  }

  def filterNewPeers(peers: List[PeerInfo])(state: State): (State, List[PeerInfo]) = {
    val newPeers = peers.filterNot(state.seenPeers)
    val state1 = state.copy(
      seenPeers = state.seenPeers ++ newPeers
    )
    (state1, newPeers)
  }

  def updateNodeList(nodes: List[NodeInfo], infoHash: InfoHash)(state: State): State = {
    val (seenNodes, newNodes) = {
      val newNodes = nodes.filterNot(state.seenNodes)
      (state.seenNodes ++ newNodes, newNodes)
    }
    val nodesToTry = (newNodes ++ state.nodesToTry).sortBy(n => NodeId.distance(n.id, infoHash))
    state.copy(
      nodesToTry = nodesToTry,
      seenNodes = seenNodes
    )
  }

  case class ExhaustedNodeList() extends Exception
}
