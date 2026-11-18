package com.github.torrentdam.bittorrent.dht

import cats.effect.kernel.Temporal
import cats.effect.std.{Queue, Random}
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.effect.direct.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash

import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scodec.bits.ByteVector

trait Client {
  
  def id: NodeId

  def getPeers(address: SocketAddress[IpAddress], infoHash: InfoHash): IO[Either[Response.Nodes, Response.Peers]]

  def findNodes(address: SocketAddress[IpAddress], target: NodeId): IO[Response.Nodes]

  def ping(address: SocketAddress[IpAddress]): IO[Response.Ping]

  def sampleInfoHashes(address: SocketAddress[IpAddress], target: NodeId): IO[Either[Response.Nodes, Response.SampleInfoHashes]]
}

object Client {

  def generateTransactionId(using random: Random[IO]): IO[ByteVector] =
    val nextChar = random.nextAlphaNumeric
    (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).toOption.get)

  def apply(
    selfId: NodeId,
    messageSocket: MessageSocket,
    queryHandler: QueryHandler
  )(using Logger[IO], Random[IO]): Resource[IO, Client] = {
    for
      responses <- Resource.eval {
        Queue.unbounded[IO, Message.ErrorMessage | Message.ResponseMessage]
      }
      rpcClient <- RpcClient.make(
        generateTransactionId,
        messageSocket.writeMessage,
        responses.take
      )
      _ <-
        def handleMessage: IO[Unit] = async[IO] {
          val (address, message) = messageSocket.readMessage.await
          message match
            case m: Message.QueryMessage =>
              Logger[IO].debug(s"Received $m").await
              queryHandler(address, m.query).await match
                case Some(response) =>
                  val responseMessage = Message.ResponseMessage(m.transactionId, response)
                  Logger[IO].debug(s"Responding with $responseMessage").await
                  messageSocket.writeMessage(address, responseMessage).await
                case None =>
                  Logger[IO].debug(s"No response for $m").await
            case m: Message.ResponseMessage => responses.offer(m).await
            case m: Message.ErrorMessage    => responses.offer(m).await
        }
        handleMessage
          .recoverWith { case e: Throwable =>
            Logger[IO].debug(s"Failed to read message: $e")
          }
          .foreverM
          .background
    yield new Client {
      
      def id: NodeId = selfId

      def getPeers(
        address: SocketAddress[IpAddress],
        infoHash: InfoHash
      ): IO[Either[Response.Nodes, Response.Peers]] =
        rpcClient.call(address, Query.GetPeers(selfId, infoHash)).flatMap {
          case nodes: Response.Nodes => nodes.asLeft.pure
          case peers: Response.Peers => peers.asRight.pure
          case _                     => IO.raiseError(InvalidResponse())
        }

      def findNodes(address: SocketAddress[IpAddress], target: NodeId): IO[Response.Nodes] =
        rpcClient.call(address, Query.FindNode(selfId, target)).flatMap {
          case nodes: Response.Nodes => nodes.pure
          case _                     => IO.raiseError(InvalidResponse())
        }

      def ping(address: SocketAddress[IpAddress]): IO[Response.Ping] =
        rpcClient.call(address, Query.Ping(selfId)).flatMap {
          case ping: Response.Ping => ping.pure
          case _                   => IO.raiseError(InvalidResponse())
        }
      def sampleInfoHashes(address: SocketAddress[IpAddress], target: NodeId): IO[Either[Response.Nodes, Response.SampleInfoHashes]] =
        rpcClient.call(address, Query.SampleInfoHashes(selfId, target)).flatMap {
          case response: Response.SampleInfoHashes => response.asRight[Response.Nodes].pure
          case response: Response.Nodes            => response.asLeft[Response.SampleInfoHashes].pure
          case _                                   => IO.raiseError(InvalidResponse())
        }
    }
  }

  case class InvalidResponse() extends Throwable
}
