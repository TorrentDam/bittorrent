package com.github.torrentdam.bittorrent.dht

import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import cats.implicits.*
import com.comcast.ip4s.*
import fs2.Stream
import com.github.torrentdam.bittorrent.InfoHash

import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

class Node(val id: NodeId, val client: Client, val routingTable: RoutingTable[IO], val discovery: PeerDiscovery)

object Node {

  def apply(
    port: Option[Port] = None,
    bootstrapNodeAddress: Option[SocketAddress[Host]] = None
  )(using
    random: Random[IO],
    logger: Logger[IO]
  ): Resource[IO, Node] =
    for
      selfId <- Resource.eval(NodeId.random[IO])
      messageSocket <- MessageSocket(port)
      routingTable <- RoutingTable[IO](selfId).toResource
      queryingNodes <- Queue.unbounded[IO, NodeInfo].toResource
      queryHandler = reportingQueryHandler(queryingNodes, QueryHandler.simple(selfId, routingTable))
      client <- Client(selfId, messageSocket, queryHandler)
      insertingClient = new InsertingClient(client, routingTable)
      bootstrapNodes = bootstrapNodeAddress.map(List(_)).getOrElse(RoutingTableBootstrap.PublicBootstrapNodes)
      discovery = PeerDiscovery(routingTable, insertingClient)
      _ <- RoutingTableBootstrap(routingTable, insertingClient, discovery, bootstrapNodes).toResource
      _ <- RoutingTableRefresh(routingTable, client, discovery).runEvery(15.minutes).background
      _ <- pingCandidates(queryingNodes, client, routingTable).background
    yield new Node(selfId, insertingClient, routingTable, discovery)

  private class InsertingClient(client: Client, routingTable: RoutingTable[IO]) extends Client {

    def id: NodeId = client.id

    def getPeers(address: SocketAddress[IpAddress], infoHash: InfoHash): IO[Either[Response.Nodes, Response.Peers]] =
      client.getPeers(address, infoHash).flatTap { response =>
        routingTable.insert(
          NodeInfo(
            response match
              case Left(response) => response.id
              case Right(response) => response.id,
            address
          )
        )
      }

    def findNodes(address: SocketAddress[IpAddress], target: NodeId): IO[Response.Nodes] =
      client.findNodes(address, target).flatTap { response =>
        routingTable.insert(NodeInfo(response.id, address))
      }

    def ping(address: SocketAddress[IpAddress]): IO[Response.Ping] =
      client.ping(address).flatTap { response =>
        routingTable.insert(NodeInfo(response.id, address))
      }

    def sampleInfoHashes(address: SocketAddress[IpAddress], target: NodeId): IO[Either[Response.Nodes, Response.SampleInfoHashes]] =
      client.sampleInfoHashes(address, target).flatTap { response =>
        routingTable.insert(
          response match
            case Left(response) => NodeInfo(response.id, address)
            case Right(response) => NodeInfo(response.id, address)
        )
      }

    override def toString: String = s"InsertingClient($client)"
  }

  private def pingCandidate(node: NodeInfo, client: Client, routingTable: RoutingTable[IO])(using Logger[IO]) =
    routingTable.lookup(node.id).flatMap {
      case Some(_) => IO.unit
      case None =>
        Logger[IO].info(s"Pinging $node") *>
        client.ping(node.address).timeout(5.seconds).attempt.flatMap {
          case Right(_) =>
            Logger[IO].info(s"Got pong from $node -- insert as good") *>
            routingTable.insert(node)
          case Left(_) => IO.unit
        }
    }

  private def pingCandidates(nodes: Queue[IO, NodeInfo], client: Client, routingTable: RoutingTable[IO])(using Logger[IO]) =
    nodes
      .tryTakeN(none)
      .flatMap(candidates => 
        candidates
          .distinct
          .traverse_(pingCandidate(_, client, routingTable).attempt.void)
      )
      .productR(IO.sleep(1.minute))
      .foreverM


  private def reportingQueryHandler(queue: Queue[IO, NodeInfo], next: QueryHandler[IO]): QueryHandler[IO] = (address, query) =>
    val nodeInfo = query match
      case Query.Ping(id) => NodeInfo(id, address)
      case Query.FindNode(id, _) => NodeInfo(id, address)
      case Query.GetPeers(id, _) => NodeInfo(id, address)
      case Query.AnnouncePeer(id, _, _) => NodeInfo(id, address)
      case Query.SampleInfoHashes(id, _) => NodeInfo(id, address)
    queue.offer(nodeInfo) *> next(address, query)
}
