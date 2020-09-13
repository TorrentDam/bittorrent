package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import com.github.lavrov.bittorrent.InfoHash
import fs2.concurrent.Queue
import fs2.io.udp.SocketGroup
import logstage.LogIO

trait Node[F[_]] {

  def client: Client[F]
}

object Node {

  def apply[F[_]](
    selfId: NodeId,
    port: Int,
    queryHandler: QueryHandler[F],
  )(implicit
    F: Concurrent[F],
    timer: Timer[F],
    cs: ContextShift[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, Node[F]] = {
    for {
      messageSocket <- MessageSocket(port)
      responses <- Resource.liftF {
        Queue
          .unbounded[F, (InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])]
      }
      client0 <- Client(selfId, messageSocket.writeMessage, responses.dequeue1)
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
                case (a, m: Message.ResponseMessage) => responses.enqueue1((a, m.asRight))
                case (a, m: Message.ErrorMessage) => responses.enqueue1((a, m.asLeft))
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
