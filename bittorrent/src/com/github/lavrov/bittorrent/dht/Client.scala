package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.MonadError
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.Response
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.Random

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
      selfId: NodeId,
      port: Int
  )(
      implicit F: Concurrent[F],
      timer: Timer[F],
      cs: ContextShift[F],
      socketGroup: SocketGroup
  ): Resource[F, Client[F]] = {
    for {
      messageSocket <- MessageSocket(port)
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
