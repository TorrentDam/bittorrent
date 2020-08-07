package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.Monad
import cats.implicits._
import com.github.lavrov.bittorrent.dht.message.{Query, Response}

trait QueryHandler[F[_]] {
  def apply(address: InetSocketAddress, query: Query): F[Response]
}

object QueryHandler {

  def apply[F[_]: Monad](selfId: NodeId, routingTable: RoutingTable[F]): QueryHandler[F] = { (_, query) =>
    query match {
      case Query.Ping(_) => (Response.Ping(selfId): Response).pure[F]
      case Query.FindNode(_, target) =>
        routingTable.findBucket(target).map { nodes =>
          Response.Nodes(selfId, nodes): Response
        }
      case Query.GetPeers(_, infoHash) =>
        routingTable.findBucket(NodeId(infoHash.bytes)).map { nodes =>
          Response.Nodes(selfId, nodes): Response
        }
    }
  }

  def fromFunction[F[_]](f: (InetSocketAddress, Query) => F[Response]): QueryHandler[F] = f(_, _)
}
