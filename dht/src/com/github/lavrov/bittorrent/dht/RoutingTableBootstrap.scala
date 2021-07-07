package com.github.lavrov.bittorrent.dht

import cats.implicits._
import cats.MonadError
import cats.effect.kernel.Temporal
import org.typelevel.log4cats.Logger
import com.comcast.ip4s._
import scala.concurrent.duration._

object RoutingTableBootstrap {

  def apply[F[_]](
    table: RoutingTable[F],
    client: Client[F]
  )(implicit
    F: Temporal[F],
    dns: Dns[F],
    logger: Logger[F]
  ): F[Unit] =
    resolveSeedNode(client) >>= table.insert

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
      logger.info("Boostrapping") *> loop <* logger.info("Bootstrap complete")
    }

  private val SeedNodeAddress = SocketAddress(host"router.bittorrent.com", port"6881")
}
