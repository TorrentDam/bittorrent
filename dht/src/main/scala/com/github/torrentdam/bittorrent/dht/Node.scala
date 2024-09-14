package com.github.torrentdam.bittorrent.dht

import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import cats.implicits.*
import com.comcast.ip4s.*
import fs2.io.net.DatagramSocketGroup
import java.net.InetSocketAddress
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scodec.bits.ByteVector

trait Node {
  def client: Client[IO]
}

object Node {

  def apply(
    selfId: NodeId,
    port: Option[Port],
    queryHandler: QueryHandler[IO]
  )(using
    random: Random[IO],
    logger: Logger[IO]
  ): Resource[IO, Node] =

    def generateTransactionId: IO[ByteVector] =
      val nextChar = random.nextAlphaNumeric
      (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).toOption.get)

    for
      messageSocket <- MessageSocket(port)
      responses <- Resource.eval {
        Queue.unbounded[IO, (SocketAddress[IpAddress], Either[Message.ErrorMessage, Message.ResponseMessage])]
      }
      client0 <- Client(selfId, messageSocket.writeMessage, responses.take, generateTransactionId)
      _ <-
        messageSocket.readMessage
          .flatMap {
            case (a, m: Message.QueryMessage) =>
              logger.debug(s"Received $m") >>
              queryHandler(a, m.query).flatMap { response =>
                val responseMessage = Message.ResponseMessage(m.transactionId, response)
                logger.debug(s"Responding with $responseMessage") >>
                messageSocket.writeMessage(a, responseMessage)
              }
            case (a, m: Message.ResponseMessage) => responses.offer((a, m.asRight))
            case (a, m: Message.ErrorMessage)    => responses.offer((a, m.asLeft))
          }
          .recoverWith { case e: Throwable =>
            logger.trace(s"Failed to read message: $e")
          }
          .foreverM
          .background
        
    yield new Node {
      def client: Client[IO] = client0
    }
}
