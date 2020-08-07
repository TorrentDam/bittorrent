package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.implicits._
import cats.effect.implicits._
import cats.{MonadError, Parallel}
import cats.effect.Timer
import logstage.LogIO

import scala.concurrent.duration._

object RoutingTableBootstrap {

  def seedNode[F[_]](
    routingTable: RoutingTable[F],
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
        .flatTap { nodeInfo =>
          routingTable.insert(nodeInfo)
        }
        .recoverWith {
          case e =>
            val msg = e.getMessage()
            logger.info(s"Bootstrap failed $msg $e") >> timer.sleep(5.seconds) >> loop
        }
    logger.info("Boostrapping") *> loop <* logger.info("Bootstrap complete")
  }

  def fill[F[_]: MonadError[*[_], Throwable]: Parallel](
    routingTable: RoutingTable[F],
    client: Client[F],
    selfId: NodeId
  ): F[Unit] = {

    def selfDistance(nodeInfo: NodeInfo) = NodeId.distance(nodeInfo.id, selfId)

    def loop(iterationsLeft: Int, nodes: List[NodeInfo]): F[Unit] = {
      nodes
        .parFlatTraverse { nodeInfo =>
          client.findNodes(nodeInfo).map(_.nodes).handleError(_ => List.empty)
        }
        .map { foundNodes =>
          foundNodes
            .sortBy(selfDistance)
            .take(10)
        }
        .flatTap { closest =>
          closest.traverse_(routingTable.insert)
        }
        .flatTap { closest =>
          loop(iterationsLeft - 1, closest)
        }
        .void
    }

    routingTable
      .findNodes(selfId)
      .flatMap { nodes =>
        val closest = nodes.toList.sortBy(selfDistance).take(10)
        loop(3, closest)
      }
  }

  private val SeedNodeAddress = new InetSocketAddress("router.bittorrent.com", 6881)
}
