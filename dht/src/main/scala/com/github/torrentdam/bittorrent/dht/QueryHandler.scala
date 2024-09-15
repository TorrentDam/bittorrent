package com.github.torrentdam.bittorrent.dht

import cats.implicits.*
import cats.Monad
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.PeerInfo

trait QueryHandler[F[_]] {
  def apply(address: SocketAddress[IpAddress], query: Query): F[Option[Response]]
}

object QueryHandler {
  
  def noop[F[_]: Monad]: QueryHandler[F] = (_, _) => none.pure[F]

  def simple[F[_]: Monad](selfId: NodeId, routingTable: RoutingTable[F]): QueryHandler[F] = { (address, query) =>
    query match {
      case Query.Ping(_) =>
        Response.Ping(selfId).some.pure[F]
      case Query.FindNode(_, target) =>
        routingTable.findBucket(target).map { nodes =>
          Response.Nodes(selfId, nodes).some
        }
      case Query.GetPeers(_, infoHash) =>
        routingTable.findPeers(infoHash).flatMap {
          case Some(peers) =>
            Response.Peers(selfId, peers.toList).some.pure[F]
          case None =>
            routingTable
              .findBucket(NodeId(infoHash.bytes))
              .map { nodes =>
                Response.Nodes(selfId, nodes).some
              }
        }
      case Query.AnnouncePeer(_, infoHash, port) =>
        routingTable
          .addPeer(infoHash, PeerInfo(SocketAddress(address.host, Port.fromInt(port.toInt).get)))
          .as(
            Response.Ping(selfId).some
          )
      case Query.SampleInfoHashes(_, _) =>
        Response.SampleInfoHashes(selfId, None, List.empty).some.pure[F]
    }
  }
}
