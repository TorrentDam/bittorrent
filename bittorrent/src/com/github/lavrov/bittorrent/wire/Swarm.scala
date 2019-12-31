package com.github.lavrov.bittorrent.wire

import cats._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.wire.Swarm.Connected
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import logstage.LogIO

import scala.concurrent.duration._

trait Swarm[F[_]] {
  def connected: Connected[F]
}

object Swarm {

  def apply[F[_]](
    dhtPeers: Stream[F, PeerInfo],
    connect: PeerInfo => F[Connection[F]],
    maxConnections: Int = 10
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    logger: LogIO[F]
  ): Resource[F, Swarm[F]] = Resource {
    for {
      semaphore <- Semaphore(maxConnections)
      stateRef <- Ref.of(Map.empty[PeerInfo, Connection[F]])
      lastConnected <- SignallingRef[F, Connection[F]](null)
      peerBuffer <- Queue.bounded[F, PeerInfo](10)
      fiber1 <- dhtPeers.through(peerBuffer.enqueue).compile.drain.start
      connectionFibers <- Ref.of[F, List[Fiber[F, Unit]]](List.empty)
      fiber2 <- {
        def spawn(peerInfo: PeerInfo): F[Fiber[F, Unit]] =
          connectRoutine(peerInfo) {
            semaphore.withPermit {
              for {
                _ <- logger.info(s"Connecting to ${peerInfo.address}")
                connection <- connect(peerInfo)
                _ <- stateRef.update(_.updated(peerInfo, connection))
                _ <- lastConnected.set(connection)
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
              F.replicateA(n.toInt, spawnOne)
                .flatMap(fibers => connectionFibers.update(fibers ++ _))
                .void
          }
          .flatMap { _ =>
            timer.sleep(1.second)
          }
          .foreverM[Unit]
          .start
      }
      cancelConnectionFibers = connectionFibers.get.flatMap(_.traverse_(_.cancel))
      closeConnections = stateRef.get.flatMap(_.values.toList.traverse_(_.close))
    } yield {
      val impl = new Impl(stateRef, lastConnected)
      val close = fiber1.cancel >> fiber2.cancel >> cancelConnectionFibers >> closeConnections >> logger
          .info("Closed CM")
      (impl, close)
    }
  }

  private class Impl[F[_]](
    stateRef: Ref[F, Map[PeerInfo, Connection[F]]],
    lastConnected: SignallingRef[F, Connection[F]]
  )(implicit F: Monad[F])
      extends Swarm[F] {
    val connected: Connected[F] = new Connected[F] {
      def count: F[Int] = stateRef.get.map(_.size)
      def stream: Stream[F, Connection[F]] =
        Stream.evalSeq(stateRef.get.map(_.values.toList)) ++ lastConnected.discrete.tail
    }
  }

  private def connectRoutine[F[_]](peerInfo: PeerInfo)(connect: F[Unit])(
    implicit F: MonadError[F, Throwable],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[Unit] = {
    val maxAttempts = 5
    val gaveUp = logger.info(s"Gave up on ${peerInfo.address}")
    def reconnect(attempt: Int): F[Unit] = {
      val waitDuration = (10 * attempt).seconds
      logger.info(s"Reconnecting to ${peerInfo.address} in $waitDuration ($attempt)") >>
      timer.sleep(waitDuration) >>
      connect
        .handleErrorWith { e =>
          if (attempt == maxAttempts) gaveUp >> F.raiseError(e)
          else reconnect(attempt + 1)
        }
    }
    for {
      _ <- connect.handleErrorWith { _ =>
        reconnect(1)
      }
      _ <- logger.info(s"Disconnected from ${peerInfo.address}. Reconnect in 10 seconds")
      _ <- timer.sleep(10.seconds)
      _ <- connectRoutine(peerInfo)(connect)
    } yield ()
  }

  trait Connected[F[_]] {
    def count: F[Int]
    def stream: Stream[F, Connection[F]]
  }
}
