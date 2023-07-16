package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Temporal
import cats.effect.Concurrent
import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scodec.bits.ByteVector

trait Client[F[_]] {

  def getPeers(nodeInfo: NodeInfo, infoHash: InfoHash): F[Either[Response.Nodes, Response.Peers]]

  def findNodes(nodeInfo: NodeInfo, target: NodeId): F[Response.Nodes]

  def ping(address: SocketAddress[IpAddress]): F[Response.Ping]

  def sampleInfoHashes(nodeInfo: NodeInfo, target: NodeId): F[Either[Response.Nodes, Response.SampleInfoHashes]]
}

object Client {

  def apply[F[_]](
    selfId: NodeId,
    sendQueryMessage: (SocketAddress[IpAddress], Message.QueryMessage) => F[Unit],
    receiveResponse: F[(SocketAddress[IpAddress], Either[Message.ErrorMessage, Message.ResponseMessage])],
    generateTransactionId: F[ByteVector]
  )(using
    F: Temporal[F],
    logger: Logger[F]
  ): Resource[F, Client[F]] = {
    for {
      requestResponse <- RequestResponse.make(
        generateTransactionId,
        sendQueryMessage,
        receiveResponse
      )
    } yield new Client[F] {

      def getPeers(
        nodeInfo: NodeInfo,
        infoHash: InfoHash
      ): F[Either[Response.Nodes, Response.Peers]] =
        requestResponse.sendQuery(nodeInfo.address, Query.GetPeers(selfId, infoHash)).flatMap {
          case nodes: Response.Nodes => nodes.asLeft.pure
          case peers: Response.Peers => peers.asRight.pure
          case _                     => F.raiseError(InvalidResponse())
        }

      def findNodes(nodeInfo: NodeInfo, target: NodeId): F[Response.Nodes] =
        requestResponse.sendQuery(nodeInfo.address, Query.FindNode(selfId, target)).flatMap {
          case nodes: Response.Nodes => nodes.pure
          case _                     => Concurrent[F].raiseError(InvalidResponse())
        }

      def ping(address: SocketAddress[IpAddress]): F[Response.Ping] =
        requestResponse.sendQuery(address, Query.Ping(selfId)).flatMap {
          case ping: Response.Ping => ping.pure
          case _                   => Concurrent[F].raiseError(InvalidResponse())
        }
      def sampleInfoHashes(nodeInfo: NodeInfo, target: NodeId): F[Either[Response.Nodes, Response.SampleInfoHashes]] =
        requestResponse.sendQuery(nodeInfo.address, Query.SampleInfoHashes(selfId, target)).flatMap {
          case response: Response.SampleInfoHashes => response.asRight[Response.Nodes].pure
          case response: Response.Nodes            => response.asLeft[Response.SampleInfoHashes].pure
          case _                                   => Concurrent[F].raiseError(InvalidResponse())
        }
    }
  }

  case class BootstrapError(message: String) extends Throwable(message)
  case class InvalidResponse() extends Throwable
}
