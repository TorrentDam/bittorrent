package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.implicits._
import cats.effect.implicits._
import cats.{Applicative, MonadError, Parallel}
import cats.effect.Timer
import logstage.LogIO

import scala.concurrent.duration._

object RoutingTableBootstrap {

  def resolveSeedNode[F[_]](
    client: Client[F]
  )(implicit
    F: MonadError[F, Throwable],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[NodeInfo] = {
    def loop: F[NodeInfo] =
      client
        .ping(SeedNodeAddress)
        .map(pong => NodeInfo(pong.id, SeedNodeAddress))
        .recoverWith {
          case e =>
            val msg = e.getMessage()
            logger.info(s"Bootstrap failed $msg $e") >> timer.sleep(5.seconds) >> loop
        }
    logger.info("Boostrapping") *> loop <* logger.info("Bootstrap complete")
  }

  def search[F[_]: MonadError[*[_], Throwable]: Parallel](
    selfId: NodeId,
    seedNodes: List[NodeInfo],
    onFound: NodeInfo => F[Unit],
    findNodes: NodeInfo => F[List[NodeInfo]],
  ): F[Unit] = {

    def selfDistance(nodeInfo: NodeInfo) = NodeId.distance(nodeInfo.id, selfId)

    def loop(iterationsLeft: Int, nodes: List[NodeInfo], filter: Set[NodeId]): F[Unit] = {
      val filter1 = filter ++ nodes.map(_.id).toSet
      nodes
        .parFlatTraverse { nodeInfo =>
          findNodes(nodeInfo).handleError(_ => List.empty)
        }
        .map { foundNodes =>
          foundNodes
            .filterNot(nodeInfo => filter1.contains(nodeInfo.id))
            .distinct
            .sortBy(selfDistance)
            .take(50)
        }
        .flatTap { closest =>
          closest.traverse_(onFound)
        }
        .flatMap { closest =>
          if (iterationsLeft == 0 || closest.isEmpty) Applicative[F].unit
          else loop(iterationsLeft - 1, closest, filter1)
        }
    }

    loop(10, seedNodes, Set.empty)
  }

  private val SeedNodeAddress = new InetSocketAddress("router.bittorrent.com", 6881)
}
