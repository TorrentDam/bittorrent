package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Temporal
import cats.effect.std.{Queue, Random}
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash

import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scodec.bits.ByteVector

trait Client {
  
  def id: NodeId

  def getPeers(nodeInfo: NodeInfo, infoHash: InfoHash): IO[Either[Response.Nodes, Response.Peers]]

  def findNodes(nodeInfo: NodeInfo, target: NodeId): IO[Response.Nodes]

  def ping(address: SocketAddress[IpAddress]): IO[Response.Ping]

  def sampleInfoHashes(nodeInfo: NodeInfo, target: NodeId): IO[Either[Response.Nodes, Response.SampleInfoHashes]]
}

object Client {

  def generateTransactionId(using random: Random[IO]): IO[ByteVector] =
    val nextChar = random.nextAlphaNumeric
    (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).toOption.get)

  def apply(
    selfId: NodeId,
    messageSocket: MessageSocket,
    queryHandler: QueryHandler[IO]
  )(using Logger[IO], Random[IO]): Resource[IO, Client] = {
    for
      responses <- Resource.eval {
        Queue.unbounded[IO, (SocketAddress[IpAddress], Message.ErrorMessage | Message.ResponseMessage)]
      }
      requestResponse <- RequestResponse.make(
        generateTransactionId,
        messageSocket.writeMessage,
        responses.take
      )
      _ <-
        messageSocket.readMessage
          .flatMap {
            case (a, m: Message.QueryMessage) =>
              Logger[IO].debug(s"Received $m") >>
                queryHandler(a, m.query).flatMap {
                  case Some(response) =>
                    val responseMessage = Message.ResponseMessage(m.transactionId, response)
                    Logger[IO].debug(s"Responding with $responseMessage") >>
                      messageSocket.writeMessage(a, responseMessage)
                  case None =>
                    Logger[IO].debug(s"No response for $m")
                }
            case (a, m: Message.ResponseMessage) => responses.offer((a, m))
            case (a, m: Message.ErrorMessage)    => responses.offer((a, m))
          }
          .recoverWith { case e: Throwable =>
            Logger[IO].debug(s"Failed to read message: $e")
          }
          .foreverM
          .background
    yield new Client {
      
      def id: NodeId = selfId

      def getPeers(
        nodeInfo: NodeInfo,
        infoHash: InfoHash
      ): IO[Either[Response.Nodes, Response.Peers]] =
        requestResponse.sendQuery(nodeInfo.address, Query.GetPeers(selfId, infoHash)).flatMap {
          case nodes: Response.Nodes => nodes.asLeft.pure
          case peers: Response.Peers => peers.asRight.pure
          case _                     => IO.raiseError(InvalidResponse())
        }

      def findNodes(nodeInfo: NodeInfo, target: NodeId): IO[Response.Nodes] =
        requestResponse.sendQuery(nodeInfo.address, Query.FindNode(selfId, target)).flatMap {
          case nodes: Response.Nodes => nodes.pure
          case _                     => IO.raiseError(InvalidResponse())
        }

      def ping(address: SocketAddress[IpAddress]): IO[Response.Ping] =
        requestResponse.sendQuery(address, Query.Ping(selfId)).flatMap {
          case ping: Response.Ping => ping.pure
          case _                   => IO.raiseError(InvalidResponse())
        }
      def sampleInfoHashes(nodeInfo: NodeInfo, target: NodeId): IO[Either[Response.Nodes, Response.SampleInfoHashes]] =
        requestResponse.sendQuery(nodeInfo.address, Query.SampleInfoHashes(selfId, target)).flatMap {
          case response: Response.SampleInfoHashes => response.asRight[Response.Nodes].pure
          case response: Response.Nodes            => response.asLeft[Response.SampleInfoHashes].pure
          case _                                   => IO.raiseError(InvalidResponse())
        }
    }
  }

  case class InvalidResponse() extends Throwable
}
