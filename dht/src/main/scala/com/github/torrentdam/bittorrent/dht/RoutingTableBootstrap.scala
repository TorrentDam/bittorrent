package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Temporal
import cats.implicits.*
import cats.MonadError
import cats.effect.implicits.*
import com.comcast.ip4s.*
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import fs2.Stream

import scala.concurrent.duration.*

object RoutingTableBootstrap {

  def apply[F[_]](
    table: RoutingTable[F],
    client: Client[F],
    bootstrapNodeAddress: List[SocketAddress[Host]] = PublicBootstrapNodes
  )(using
    F: Temporal[F],
    dns: Dns[F],
    logger: Logger[F]
  ): F[Unit] =
    for {
      _ <- logger.info("Bootstrapping")
      count <- resolveBootstrapNode(client, bootstrapNodeAddress)
        .evalMap(table.insert)
        .compile
        .count
      _ <- logger.info(s"Bootstrap completed with $count nodes")
    } yield {}

  private def resolveBootstrapNode[F[_]](
    client: Client[F],
    bootstrapNodeAddress: List[SocketAddress[Host]]
  )(using
    F: Temporal[F],
    dns: Dns[F],
    logger: Logger[F]
  ): Stream[F, NodeInfo] =
    def tryThis(hostname: SocketAddress[Host]): F[Option[NodeInfo]] =
      logger.info(s"Trying to reach $hostname") >>
      hostname
        .resolve[F]
        .flatMap: seedAddress =>
          client
            .ping(seedAddress)
            .timeout(5.seconds)
            .map(pong => NodeInfo(pong.id, seedAddress))
        .map(_.some)
        .recoverWith:
          e =>
            val msg = e.getMessage
            logger.info(s"Failed to reach $hostname $msg $e").as(none)
    Stream.emits(bootstrapNodeAddress)
      .covary[F]
      .evalMap(tryThis)
      .collect {
        case Some(node) => node
      }

  val PublicBootstrapNodes: List[SocketAddress[Host]] = List(
    SocketAddress(host"router.bittorrent.com", port"6881"),
    SocketAddress(host"router.utorrent.com", port"6881"),
    SocketAddress(host"dht.transmissionbt.com", port"6881"),
    SocketAddress(host"router.bitcomet.com", port"6881"),
    SocketAddress(host"dht.aelitis.com", port"6881"),
  )
}
