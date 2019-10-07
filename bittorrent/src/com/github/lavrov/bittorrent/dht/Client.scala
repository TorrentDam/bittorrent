package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.effect.syntax.all._
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}
import fs2.concurrent.Queue
import fs2.io.udp.SocketGroup
import scodec.bits.ByteVector

import scala.util.Random
import logstage.LogIO

trait Client[F[_]] {
  def getPeers(nodeInfo: NodeInfo, infoHash: InfoHash): F[Either[Response.Nodes, Response.Peers]]
  def ping(address: InetSocketAddress): F[Response.Ping]
}

object Client {

  def generateTransactionId[F[_]: Sync]: F[ByteVector] = {
    val nextChar = Sync[F].delay(Random.nextPrintableChar())
    (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).right.get)
  }

  def start[F[_]](
      selfId: NodeId,
      port: Int
  )(
      implicit F: Concurrent[F],
      timer: Timer[F],
      cs: ContextShift[F],
      socketGroup: SocketGroup,
      logger: LogIO[F]
  ): Resource[F, Client[F]] = {
    for {
      messageSocket <- MessageSocket(port)
      responses <- Resource.liftF {
        Queue
          .unbounded[F, (InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])]
      }
      queryies <- Resource.liftF { Queue.unbounded[F, (InetSocketAddress, Message)] }
      _ <- Resource
        .make(
          messageSocket.readMessage
            .flatMap {
              case (a, m: Message.QueryMessage) => queryies.enqueue1((a, m))
              case (a, m: Message.ResponseMessage) => responses.enqueue1((a, m.asRight))
              case (a, m: Message.ErrorMessage) => responses.enqueue1((a, m.asLeft))
            }
            .foreverM
            .start
        )(_.cancel)
      requestResponse <- RequestResponse.make(
        generateTransactionId,
        messageSocket.writeMessage,
        responses.dequeue1
      )
    } yield new Client[F] {
      import requestResponse.sendQuery

      def getPeers(
          nodeInfo: NodeInfo,
          infoHash: InfoHash
      ): F[Either[Response.Nodes, Response.Peers]] =
        sendQuery(nodeInfo.address, Query.GetPeers(selfId, infoHash)).flatMap {
          case nodes: Response.Nodes => nodes.asLeft.pure
          case peers: Response.Peers => peers.asRight.pure
          case _ => Concurrent[F].raiseError(InvalidResponse())
        }

      def ping(address: InetSocketAddress): F[Response.Ping] =
        sendQuery(address, Query.Ping(selfId)).flatMap {
          case ping: Response.Ping => ping.pure
          case _ => Concurrent[F].raiseError(InvalidResponse())
        }
    }
  }

  case class BootstrapError(message: String) extends Throwable(message)
  case class InvalidResponse() extends Throwable
}
