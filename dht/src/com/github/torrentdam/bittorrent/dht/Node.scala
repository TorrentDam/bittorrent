package com.github.torrentdam.bittorrent.dht

import cats.effect.direct.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import fs2.io.net.Network
import fs2.Stream
import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scala.concurrent.duration.DurationInt
import scodec.bits.ByteVector

class Node(val id: NodeId, val client: Client, val routingTable: RoutingTable, val discovery: PeerDiscovery)

object Node {

  /** Cap on buffered ping candidates so query bursts cannot exhaust the heap; excess candidates are dropped. */
  private val MaxCandidateQueueSize = 1024

  def apply(
    port: Option[Port] = None,
    bootstrapNodeAddress: Option[SocketAddress[Host]] = None
  )(using
    random: Random[IO],
    network: Network[IO],
    logger: Logger[IO]
  ): Resource[IO, Node] =
    for
      selfId <- Resource.eval(NodeId.random)
      messageSocket <- MessageSocket(port)
      routingTable <- RoutingTable(selfId).toResource
      queryingNodes <- Queue.bounded[IO, NodeInfo](MaxCandidateQueueSize).toResource
      queryHandler = reportingQueryHandler(queryingNodes, QueryHandler.simple(selfId, routingTable))
      client <- Client(selfId, messageSocket, queryHandler)
      insertingClient = new InsertingClient(client, routingTable)
      bootstrapNodes = bootstrapNodeAddress.map(List(_)).getOrElse(RoutingTableBootstrap.PublicBootstrapNodes)
      discovery = PeerDiscovery(routingTable, insertingClient)
      _ <- RoutingTableBootstrap(routingTable, insertingClient, discovery, bootstrapNodes).toResource
      _ <- RoutingTableRefresh(routingTable, client, discovery).runEvery(15.minutes).background
      _ <- pingCandidates(queryingNodes, client, routingTable).background
    yield new Node(selfId, insertingClient, routingTable, discovery)

  private class InsertingClient(client: Client, routingTable: RoutingTable) extends Client {

    def id: NodeId = client.id

    def getPeers(address: SocketAddress[IpAddress], infoHash: InfoHash): IO[Either[Response.Nodes, Response.Peers]] =
      async[IO] {
        val response = client.getPeers(address, infoHash).await
        val responderId = response match
          case Left(nodes)  => nodes.id
          case Right(peers) => peers.id
        routingTable.insert(NodeInfo(responderId, address)).await
        response
      }

    def findNodes(address: SocketAddress[IpAddress], target: NodeId): IO[Response.Nodes] =
      async[IO] {
        val response = client.findNodes(address, target).await
        routingTable.insert(NodeInfo(response.id, address)).await
        response
      }

    def ping(address: SocketAddress[IpAddress]): IO[Response.Ping] =
      async[IO] {
        val response = client.ping(address).await
        routingTable.insert(NodeInfo(response.id, address)).await
        response
      }

    def sampleInfoHashes(
      address: SocketAddress[IpAddress],
      target: NodeId
    ): IO[Either[Response.Nodes, Response.SampleInfoHashes]] =
      async[IO] {
        val response = client.sampleInfoHashes(address, target).await
        val responderId = response match
          case Left(nodes)   => nodes.id
          case Right(sample) => sample.id
        routingTable.insert(NodeInfo(responderId, address)).await
        response
      }

    override def toString: String = s"InsertingClient($client)"
  }

  private def pingCandidate(node: NodeInfo, client: Client, routingTable: RoutingTable)(using Logger[IO]) =
    async[IO] {
      val alreadyKnown = routingTable.lookup(node.id).await
      if alreadyKnown.isEmpty then
        Logger[IO].info(s"Pinging $node").await
        client.ping(node.address).timeout(5.seconds).attempt.await match
          case Right(_) =>
            Logger[IO].info(s"Got pong from $node -- insert as good").await
            routingTable.insert(node).await
          case Left(_) => ()
    }

  private def pingCandidates(nodes: Queue[IO, NodeInfo], client: Client, routingTable: RoutingTable)(using Logger[IO]) =
    nodes
      .tryTakeN(none)
      .flatMap(candidates =>
        candidates.distinct
          .traverse_(pingCandidate(_, client, routingTable).attempt.void)
      )
      .productR(IO.sleep(1.minute))
      .foreverM

  private def reportingQueryHandler(queue: Queue[IO, NodeInfo], next: QueryHandler): QueryHandler = (address, query) =>
    val nodeInfo = query match
      case Query.Ping(id)                => NodeInfo(id, address)
      case Query.FindNode(id, _)         => NodeInfo(id, address)
      case Query.GetPeers(id, _)         => NodeInfo(id, address)
      case Query.AnnouncePeer(id, _, _)  => NodeInfo(id, address)
      case Query.SampleInfoHashes(id, _) => NodeInfo(id, address)
    // tryOffer: when the queue is full, drop the candidate instead of blocking query handling
    queue.tryOffer(nodeInfo) *> next(address, query)
}
