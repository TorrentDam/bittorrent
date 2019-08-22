package com.github.lavrov.bittorrent.dht

import cats.syntax.all._
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import fs2.io.udp.AsynchronousSocketGroup
import fs2.Stream
import java.net.InetSocketAddress
import com.github.lavrov.bittorrent.dht.message.Message
import cats.effect.Fiber
import com.github.lavrov.bittorrent.dht.message.Response
import com.github.lavrov.bittorrent.InfoHash
import fs2.concurrent.Queue
import cats.effect.concurrent.Deferred
import scala.concurrent.duration.FiniteDuration
import cats.effect.Timer
import scodec.bits.ByteVector
import scala.util.Random
import com.github.lavrov.bittorrent.dht.message.Query
import cats.MonadError

import scala.concurrent.duration._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.log4cats.Logger

trait Client[F[_]] {
  def getTable: F[List[NodeInfo]]
  def getPeers(nodeInfo: NodeInfo, infoHash: InfoHash): F[Either[Response.Nodes, Response.Peers]]
}

object Client {

  val BootstrapNodeAddress = new InetSocketAddress("router.bittorrent.com", 6881)

  def generateTransactionId[F[_]: Sync]: F[ByteVector] = {
    val nextChar = Sync[F].delay(Random.nextPrintableChar())
    (nextChar, nextChar).mapN((a, b) => ByteVector.encodeAscii(List(a, b).mkString).right.get)
  }

  def start[F[_]](
      selfId: NodeId
  )(
      implicit F: Concurrent[F],
      timer: Timer[F],
      cs: ContextShift[F],
      asg: AsynchronousSocketGroup
  ): Resource[F, Client[F]] = {
    for {
      messageSocket <- MessageSocket()
      logger <- Resource.liftF(Slf4jLogger.fromClass(Client.getClass))
      requestResponse <- Resource.liftF {
        RequestResponse.make(
          selfId,
          generateTransactionId,
          messageSocket.writeMessage
        )
      }
      dhtBehaviour <- Resource.liftF(DhtBehaviour.make(selfId, messageSocket.writeMessage))
      messages = Stream
        .repeatEval(messageSocket.readMessage)
      processor = messages.broadcastTo(requestResponse.pipe, dhtBehaviour.pipe)
      fiber <- Resource.make(Concurrent[F].start(processor.compile.drain))(_.cancel)
      _ <- Resource.liftF(boostrap(selfId, requestResponse, logger))
    } yield new Client[F] {

      def getTable: F[List[NodeInfo]] = dhtBehaviour.getTable

      def getPeers(
          nodeInfo: NodeInfo,
          infoHash: InfoHash
      ): F[Either[Response.Nodes, Response.Peers]] = 
        requestResponse.getPeers(nodeInfo.address, infoHash)
    }
  }

  private def boostrap[F[_]](selfId: NodeId, requestResponse: RequestResponse[F], logger: Logger[F])(
    implicit F: MonadError[F, Throwable], timer: Timer[F]
  ): F[Unit] = {
    def loop: F[Unit] =
      requestResponse.ping(BootstrapNodeAddress).void
      .recoverWith {
        case e =>
          val msg = e.getMessage()
          logger.info(e)(s"Bootstrap failed $msg") >> timer.sleep(5.seconds) >> loop
      }
    logger.info("Boostrapping") >> loop >> logger.info("Bootstrap complete")
  }

  case class BootstrapError(message: String) extends Throwable(message)
  case class InvalidResponse() extends Throwable
}
