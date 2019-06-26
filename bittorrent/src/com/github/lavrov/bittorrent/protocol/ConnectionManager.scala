package com.github.lavrov.bittorrent.protocol

import cats.syntax.all._
import com.github.lavrov.bittorrent.InfoHash
import cats.effect.Effect
import fs2.Stream
import java.nio.channels.AsynchronousChannelGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.timwspence.cats.stm.TVar
import com.github.lavrov.bittorrent.PeerInfo
import io.github.timwspence.cats.stm.STM
import cats.effect.Resource
import cats.effect.ConcurrentEffect
import cats.effect.Timer

import scala.concurrent.duration._
import fs2.concurrent.Queue
import cats.effect.concurrent.Ref

object ConnectionManager {

  val maxConnections = 50

  def make[F[_]](
      dhtPeers: Stream[F, PeerInfo],
      connectToPeer: PeerInfo => Resource[F, Connection[F]]
  )(
      implicit F: ConcurrentEffect[F],
      timer: Timer[F],
      acg: AsynchronousChannelGroup
  ): F[Stream[F, Connection[F]]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      connecting <- TVar.of(0).commit[F]
      connected <- TVar.of(0).commit[F]
      goodPeersQueue <- Queue.unbounded[F, PeerInfo]
      peers = dhtPeers merge goodPeersQueue.dequeue
      loggingLoop = Stream
        .repeatEval(
          (connecting.get, connected.get).tupled.commit[F].flatMap {
            case (numConnecting, numConnected) =>
              logger.debug(s"Connecting: $numConnecting, Connected: $numConnected")
          } *>
            timer.sleep(5.seconds)
        )
      connectionLoop = Stream
        .repeatEval(
          STM.atomically[F] {
            for {
              numConnecting <- connecting.get
              numConnected <- connected.get
              demand = maxConnections - (numConnecting + numConnected)
              _ <- STM.check(demand > 0)
              _ <- connecting.modify(_ + 1)
            } yield ()
          }
        )
        .zipRight(peers)
        .parEvalMapUnordered[F, Stream[F, Connection[F]]](maxConnections) { peer =>
          for {
            _ <- logger.debug(s"Connecting to $peer")
            connectionResult <- connectToPeer(peer).allocated.attempt
            stream <- connectionResult match {
              case Left(_) =>
                logger.debug(s"Filed to connect $peer") *>
                  connecting.modify(_ - 1).commit[F] *>
                  F.pure(Stream.empty)
              case Right((connection, closeConnection)) =>
                logger.debug(s"Successfully connected $peer") *>
                  STM.atomically[F] {
                    connecting.modify(_ - 1) *> connected.modify(_ + 1)
                  } *>
                  F.pure {
                    val onDisconnect = Stream
                      .eval(
                        connection.disconnected *>
                          closeConnection *>
                          logger.debug(s"Disconnected ${connection.info}") *>
                          connected.modify(_ - 1).commit[F]
                      ) *>
                      Stream.fixedDelay(20.seconds) *>
                      Stream.eval(goodPeersQueue.enqueue1(connection.info))

                    onDisconnect.spawn >> Stream.emit(connection)
                  }
            }
          } yield stream
        }
        .flatten
    } yield loggingLoop.spawn >> connectionLoop
  }
}
