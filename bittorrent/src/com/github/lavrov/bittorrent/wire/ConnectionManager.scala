package com.github.lavrov.bittorrent.wire

import cats._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.PeerInfo
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.tcp.SocketGroup
import logstage.LogIO

import scala.concurrent.duration._

trait ConnectionManager[F[_]] {
  def connected: F[Int]
}

object ConnectionManager {

  def apply[F[_]](
    dhtPeers: Stream[F, PeerInfo],
    connect: PeerInfo => F[Connection[F]],
    maxConnections: Int = 10
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, ConnectionManager[F]] = Resource {
    for {
      semaphore <- Semaphore(maxConnections)
      stateRef <- Ref.of(Map.empty[PeerInfo, Connection[F]])
      peerBuffer <- Queue.bounded[F, PeerInfo](10)
      fiber1 <- dhtPeers.through(peerBuffer.enqueue).compile.drain.start
      fiber2 <- {
        def spawn(peerInfo: PeerInfo) = connectRoutine(peerInfo) {
          semaphore.withPermit {
            for {
              _ <- logger.info(s"Connecting to ${peerInfo.address}")
              connection <- connect(peerInfo)
              _ <- stateRef.update(_.updated(peerInfo, connection))
              _ <- connection.disconnected
              _ <- stateRef.update(_ - peerInfo)
            } yield ()
          }
        }.start
        semaphore.available
          .flatMap {
            case 0L => F.unit
            case n =>
              val spawnOne = peerBuffer.dequeue1 >>= spawn
              F.replicateA(n.toInt, spawnOne).void
          }
          .flatMap { _ => timer.sleep(1.second) }
          .foreverM[Unit]
          .start
      }
    } yield (new Impl(stateRef), fiber1.cancel >> fiber2.cancel)
  }

  private class Impl[F[_]](
    stateRef: Ref[F, Map[PeerInfo, Connection[F]]]
  )(implicit F: Monad[F])
    extends ConnectionManager[F] {

    def connected: F[Int] = stateRef.get.map(_.size)
  }

  private def connectRoutine[F[_]](peerInfo: PeerInfo)(connect: F[Unit])(
    implicit F: MonadError[F, Throwable],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[Unit] = {
    val reconnect = logger.info(s"Reconnecting to ${peerInfo.address}") >> connect
    val gaveUp = logger.info(s"Gave up on ${peerInfo.address}")
    for {
      _ <- connect.handleErrorWith {
        _ => reconnect.handleErrorWith {
          e => gaveUp >> F.raiseError(e)
        }
      }
      _ <- logger.info(s"Disconnected from ${peerInfo.address}. Reconnect in 10 seconds")
      _ <- timer.sleep(10.seconds)
      _ <- connectRoutine(peerInfo)(connect)
    } yield ()
  }
}
