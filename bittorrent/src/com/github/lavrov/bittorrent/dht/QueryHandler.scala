package com.github.lavrov.bittorrent.dht

import cats.Monad
import cats.implicits._
import com.github.lavrov.bittorrent.dht.message.{Query, Response}

trait QueryHandler[F[_]] {
  def apply(query: Query): F[Response]
}

object QueryHandler {

  def apply[F[_]: Monad](selfId: NodeId, routingTable: RoutingTable[F]): QueryHandler[F] =
    new QueryHandler[F] {

      def apply(query: Query): F[Response] =
        query match {
          case Query.Ping(_) => (Response.Ping(selfId): Response).pure[F]
          case Query.FindNode(_, target) =>
            routingTable.findNodes(target).map { nodes =>
              Response.Nodes(selfId, nodes): Response
            }
          case Query.GetPeers(_, infoHash) =>
            routingTable.findNodes(NodeId(infoHash.bytes)).map { nodes =>
              Response.Nodes(selfId, nodes): Response
            }

        }
    }
}
