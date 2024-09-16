package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Temporal
import cats.implicits.*
import cats.MonadError
import cats.effect.IO
import cats.effect.implicits.*
import cats.effect.cps.{given, *}
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
    for
      _ <- logger.info("Bootstrapping")
      count <- resolveNodes(client, bootstrapNodeAddress).compile.count.iterateUntil(_ > 0)
      _ <- logger.info(s"Communicated with $count bootstrap nodes")
      _ <- selfDiscovery(table, client, discovery)
      nodeCount <- table.allNodes.map(_.size)
      _ <- logger.info(s"Bootstrapping finished with $nodeCount nodes")
    yield {}

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
          logger.info(s"Resolved to $ipAddress") *>
          client
            .ping(resolvedAddress)
            .timeout(5.seconds)
            .map(pong => NodeInfo(pong.id, resolvedAddress))
            .flatTap: _ =>
              logger.info(s"Reached $resolvedAddress node")
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

  private def selfDiscovery(
    table: RoutingTable[IO],
    client: Client,
    discovery: PeerDiscovery
  )(using Logger[IO]) =
    def attempt(number: Int): IO[Unit] = async[IO]:
      Logger[IO].info(s"Discover self to fill up routing table (attempt $number)").await
      val count = discovery.findNodes(client.id).take(30).interruptAfter(30.seconds).compile.count.await
      Logger[IO].info(s"Communicated with $count nodes during self discovery").await
      val nodeCount = table.allNodes.await.size
      if nodeCount < 20 then attempt(number + 1).await else IO.unit
    attempt(1)

  val PublicBootstrapNodes: List[SocketAddress[Host]] = List(
    SocketAddress(host"router.bittorrent.com", port"6881"),
    SocketAddress(host"router.utorrent.com", port"6881"),
    SocketAddress(host"dht.transmissionbt.com", port"6881"),
    SocketAddress(host"router.bitcomet.com", port"6881"),
    SocketAddress(host"dht.aelitis.com", port"6881"),
  )
}
