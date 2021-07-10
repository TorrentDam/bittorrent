package com.github.lavrov.bittorrent.dht

import cats.implicits.*
import cats.MonadError
import cats.effect.kernel.Temporal
import org.typelevel.log4cats.Logger
import com.comcast.ip4s.*
import scala.concurrent.duration.*

object RoutingTableBootstrap {

  def apply[F[_]](
    table: RoutingTable[F],
    client: Client[F]
  )(implicit
    F: Temporal[F],
    dns: Dns[F],
    logger: Logger[F]
  ): F[Unit] =
    for {
      _ <- logger.info("Bootstrapping")
      seedInfo <- resolveSeedNode(client)
      response <- client.findNodes(seedInfo, seedInfo.id)
      _ <- response.nodes.traverse(table.insert)
      _ <- logger.info(s"Bootstrap completed with ${response.nodes.size} nodes")
    } yield {}

  def resolveSeedNode[F[_]](
    client: Client[F]
  )(implicit
    F: Temporal[F],
    dns: Dns[F],
    logger: Logger[F]
  ): F[NodeInfo] =
    SeedNodeAddress.resolve[F].flatMap { seedAddress =>
      def loop: F[NodeInfo] =
        client
          .ping(seedAddress)
          .map(pong => NodeInfo(pong.id, seedAddress))
          .recoverWith {
            case e =>
              val msg = e.getMessage
              logger.info(s"Bootstrap failed $msg $e") >> F.sleep(5.seconds) >> loop
          }
      logger.info(s"Resolve seed node $seedAddress") >>
        loop.flatTap { nodeInfo =>
          logger.info(s"Resolved as ${nodeInfo.id}")
        }
    }

  private val SeedNodeAddress = SocketAddress(host"router.bittorrent.com", port"6881")
}
