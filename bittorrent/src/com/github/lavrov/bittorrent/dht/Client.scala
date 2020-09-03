package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.effect.syntax.all._
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}
import fs2.concurrent.Queue
import scodec.bits.ByteVector

import scala.util.Random
import logstage.LogIO

trait Client[F[_]] {

  def getPeers(nodeInfo: NodeInfo, infoHash: InfoHash): F[Either[Response.Nodes, Response.Peers]]

  def findNodes(nodeInfo: NodeInfo): F[Response.Nodes]

  def ping(address: InetSocketAddress): F[Response.Ping]
}

object Client {

  def generateTransactionId[F[_]: Sync]: F[ByteVector] = {
    val nextChar = Sync[F].delay(Random.nextPrintableChar())
    (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).right.get)
  }

  def apply[F[_]](
    selfId: NodeId,
    sendQueryMessage: (InetSocketAddress, Message.QueryMessage) => F[Unit],
    receiveResponse: F[(InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])]
  )(implicit
    F: Concurrent[F],
    timer: Timer[F],
    cs: ContextShift[F],
    logger: LogIO[F]
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
          case _ => Concurrent[F].raiseError(InvalidResponse())
        }

      def findNodes(nodeInfo: NodeInfo): F[Response.Nodes] =
        requestResponse.sendQuery(nodeInfo.address, Query.FindNode(selfId, nodeInfo.id)).flatMap {
          case nodes: Response.Nodes => nodes.pure
          case _ => Concurrent[F].raiseError(InvalidResponse())
        }

      def ping(address: InetSocketAddress): F[Response.Ping] =
        requestResponse.sendQuery(address, Query.Ping(selfId)).flatMap {
          case ping: Response.Ping => ping.pure
          case _ => Concurrent[F].raiseError(InvalidResponse())
        }
    }
  }

  case class BootstrapError(message: String) extends Throwable(message)
  case class InvalidResponse() extends Throwable
}
