package com.github.lavrov.bittorrent.dht

import cats.implicits.*
import cats.Monad
import com.comcast.ip4s.*
import com.github.lavrov.bittorrent.PeerInfo

trait QueryHandler[F[_]] {
  def apply(address: SocketAddress[IpAddress], query: Query): F[Response]
}

object QueryHandler {

  def apply[F[_]: Monad](selfId: NodeId, routingTable: RoutingTable[F]): QueryHandler[F] = { (address, query) =>
    query match {
      case Query.Ping(nodeId) =>
        routingTable.insert(NodeInfo(nodeId, address)).as {
          Response.Ping(selfId): Response
        }
      case Query.FindNode(nodeId, target) =>
        routingTable.insert(NodeInfo(nodeId, address)) >>
        routingTable.findBucket(target).map { nodes =>
          Response.Nodes(selfId, nodes): Response
        }
      case Query.GetPeers(nodeId, infoHash) =>
        routingTable.insert(NodeInfo(nodeId, address)) >>
        routingTable.findPeers(infoHash).flatMap {
          case Some(peers) =>
            Response.Peers(selfId, peers.toList).pure[F].widen[Response]
          case None =>
            routingTable
              .findBucket(NodeId(infoHash.bytes))
              .map { nodes =>
                Response.Nodes(selfId, nodes)
              }
              .widen[Response]
        }
      case Query.AnnouncePeer(nodeId, infoHash, port) =>
        routingTable.insert(NodeInfo(nodeId, address)) >>
        routingTable.addPeer(infoHash, PeerInfo(SocketAddress(address.host, Port.fromInt(port.toInt).get))).as {
          Response.Ping(selfId): Response
        }
      case Query.SampleInfoHashes(_, _) =>
        (Response.SampleInfoHashes(selfId, None, List.empty): Response).pure[F]
    }
  }

  def fromFunction[F[_]](f: (SocketAddress[IpAddress], Query) => F[Response]): QueryHandler[F] = f(_, _)
}
