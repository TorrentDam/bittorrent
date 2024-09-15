package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Temporal
import cats.implicits.*
import cats.MonadError
import cats.effect.IO
import cats.effect.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import fs2.Stream

import scala.concurrent.duration.*

object RoutingTableBootstrap {

  def apply(
    table: RoutingTable[IO],
    client: Client,
    discovery: PeerDiscovery,
    bootstrapNodeAddress: List[SocketAddress[Host]] = PublicBootstrapNodes
  )(using
    dns: Dns[IO],
    logger: Logger[IO]
  ): IO[Unit] =
    for {
      _ <- logger.info("Bootstrapping")
      count <- resolveNodes(client, bootstrapNodeAddress).compile.count
      _ <- logger.info(s"Pinged $count bootstrap nodes")
      _ <- logger.info("Discover self to fill up routing table")
      _ <- discovery.discover(InfoHash(client.id.bytes)).take(10).compile.drain
      nodeCount <- table.allNodes.map(_.size)
      _ <- logger.info(s"Bootstrapping finished with $nodeCount nodes")
    } yield {}

  private def resolveNodes(
    client: Client,
    bootstrapNodeAddress: List[SocketAddress[Host]]
  )(using
    dns: Dns[IO],
    logger: Logger[IO]
  ): Stream[IO, NodeInfo] =
    def tryThis(hostname: SocketAddress[Host]): Stream[IO, NodeInfo] =
      Stream.eval(logger.info(s"Trying to reach $hostname")) >>
      Stream
        .evals(
          hostname.host.resolveAll[IO]
            .recoverWith: e =>
              logger.info(s"Failed to resolve $hostname $e").as(List.empty)
        )
        .evalMap: ipAddress =>
          val resolvedAddress = SocketAddress(ipAddress, hostname.port)
          client
            .ping(resolvedAddress)
            .timeout(5.seconds)
            .map(pong => NodeInfo(pong.id, resolvedAddress))
            .map(_.some)
            .recoverWith: e =>
              logger.info(s"Failed to reach $resolvedAddress $e").as(none)
        .collect {
          case Some(node) => node
        }        
    Stream
      .emits(bootstrapNodeAddress)
      .covary[IO]
      .flatMap(tryThis)

  val PublicBootstrapNodes: List[SocketAddress[Host]] = List(
    SocketAddress(host"router.bittorrent.com", port"6881"),
    SocketAddress(host"router.utorrent.com", port"6881"),
    SocketAddress(host"dht.transmissionbt.com", port"6881"),
    SocketAddress(host"router.bitcomet.com", port"6881"),
    SocketAddress(host"dht.aelitis.com", port"6881"),
  )
}
