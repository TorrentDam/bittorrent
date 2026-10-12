package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.PeerInfo

trait QueryHandler {
  def apply(address: SocketAddress[IpAddress], query: Query): IO[Option[Response]]
}

object QueryHandler {

  val noop: QueryHandler = (_, _) => IO.pure(None)

  def simple(selfId: NodeId, routingTable: RoutingTable): QueryHandler = { (address, query) =>
    query match {
      case Query.Ping(_) =>
        IO.pure(Some(Response.Ping(selfId)))
      case Query.FindNode(_, target) =>
        routingTable.goodNodes(target).map { nodes =>
          Some(Response.Nodes(selfId, nodes.take(8).toList))
        }
      case Query.GetPeers(_, infoHash) =>
        routingTable.findPeers(infoHash).flatMap {
          case Some(peers) =>
            IO.pure(Some(Response.Peers(selfId, peers.toList)))
          case None =>
            routingTable
              .goodNodes(NodeId(infoHash.bytes))
              .map { nodes =>
                Some(Response.Nodes(selfId, nodes.take(8).toList))
              }
        }
      case Query.AnnouncePeer(_, infoHash, port) =>
        routingTable
          .addPeer(infoHash, PeerInfo(SocketAddress(address.host, Port.fromInt(port.toInt).get)))
          .as(
            Some(Response.Ping(selfId))
          )
      case Query.SampleInfoHashes(_, _) =>
        IO.pure(Some(Response.SampleInfoHashes(selfId, None, List.empty)))
    }
  }
}
