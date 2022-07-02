package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress
import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.{Async, Resource, Sync}
import cats.effect.std.{Queue, Random}
import fs2.io.net.DatagramSocketGroup
import com.comcast.ip4s.*
import org.legogroup.woof.{Logger, given}
import scodec.bits.ByteVector

trait Node[F[_]] {

  def client: Client[F]
}

object Node {

  def apply[F[_]](
    selfId: NodeId,
    queryHandler: QueryHandler[F]
  )(
    using
    F: Async[F],
    random: Random[F],
    socketGroup: DatagramSocketGroup[F],
    logger: Logger[F]
  ): Resource[F, Node[F]] = {


    def generateTransactionId: F[ByteVector] = {
      val nextChar = random.nextAlphaNumeric
      (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).toOption.get)
    }

    for {
      messageSocket <- MessageSocket[F]()
      responses <- Resource.eval {
        Queue
          .unbounded[F, (SocketAddress[IpAddress], Either[Message.ErrorMessage, Message.ResponseMessage])]
      }
      client0 <- Client(selfId, messageSocket.writeMessage, responses.take, generateTransactionId)
      _ <-
        Resource
          .make(
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
                case (a, m: Message.ErrorMessage) => responses.offer((a, m.asLeft))
              }
              .recoverWith {
                case e: Throwable =>
                  logger.error(s"Failed to read message: $e")
              }
              .foreverM
              .start
          )(_.cancel)
    } yield new Node[F] {

      def client: Client[F] = client0
    }
  }

}
