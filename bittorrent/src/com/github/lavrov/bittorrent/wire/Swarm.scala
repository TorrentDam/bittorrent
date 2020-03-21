package com.github.lavrov.bittorrent.wire

import cats._
import cats.data.ContT
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Timer}
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
      stateRef <- Ref.of(Map.empty[PeerInfo, Connection[F]])
      lastConnected <- SignallingRef[F, Connection[F]](null)
      peerBuffer <- Queue.bounded[F, PeerInfo](10)
      reconnects <- Queue.unbounded[F, F[Unit]]
      fiber1 <- dhtPeers.through(peerBuffer.enqueue).compile.drain.start
      connectionFibers <- F
        .replicateA(
          maxConnections,
          openConnections[F](
            peerBuffer.dequeue1,
            reconnects,
            peerInfo =>
              for {
                _ <- logger.debug(s"Connecting to ${peerInfo.address}")
                connection <- connect(peerInfo)
                  .timeoutTo(1.second, F raiseError Error.ConnectTimeout(1.second))
                _ <- stateRef.update(_.updated(peerInfo, connection))
                _ <- lastConnected.set(connection)
                _ <- connection.disconnected
                _ <- stateRef.update(_ - peerInfo)
              } yield ()
          ).foreverM[Unit].start
        )
    } yield {
      val impl = new Impl(stateRef, lastConnected)
      val close = fiber1.cancel >> connectionFibers.traverse_(_.cancel) >> logger.info("Closed Swarm")
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
      def list: F[List[Connection[F]]] = stateRef.get.map(_.values.toList)
      def stream: Stream[F, Connection[F]] =
        Stream.evalSeq(stateRef.get.map(_.values.toList)) ++ lastConnected.discrete.tail
    }
  }

  private def openConnections[F[_]](discover: F[PeerInfo], reconnects: Queue[F, F[Unit]], connect: PeerInfo => F[Unit])(
    implicit F: Concurrent[F],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[Unit] =
    for {
      continuation <- reconnects.tryDequeue1.flatMap {
        case Some(c) => c.pure[F]
        case None =>
          type Cont[A] = ContT[F, Unit, A]
          implicit val liftF: F ~> Cont = λ[F ~> Cont] { fa =>
            ContT { k =>
              fa >>= k
            }
          }
          F.race(discover, reconnects.dequeue1).map {
            _.leftMap { discovered =>
              connectRoutine[Cont, F](discovered)(
                connect = liftF {
                  connect(discovered).attempt
                },
                coolDown = duration =>
                  ContT { k0 =>
                    val k = k0(())
                    (timer.sleep(duration) >> reconnects.enqueue1(k)).start.void
                  }
              ).run(_ => F.unit)
            }.merge
          }
      }
      _ <- continuation
    } yield ()

  private def connectRoutine[F[_], F0[_]](
    peerInfo: PeerInfo
  )(connect: F[Either[Throwable, Unit]], coolDown: FiniteDuration => F[Unit])(
    implicit F: Monad[F],
    logger: LogIO[F0],
    lift: F0 ~> F
  ): F[Unit] = {
    val maxAttempts = 5
    val gaveUp = lift {
      logger.debug(s"Gave up on ${peerInfo.address}")
    }
    def logRetry(cause: String, attempt: Int, waitDuration: FiniteDuration) = lift {
      logger.debug(
        s"Connection failed $attempt $cause. Retry connecting to ${peerInfo.address} in at least $waitDuration"
      )
    }
    def connectWithRetry(attempt: Int): F[Unit] = {
      connect
        .flatMap {
          case Right(_) => F.unit
          case Left(e) =>
            val cause = e.getMessage
            if (attempt == maxAttempts) gaveUp
            else {
              val duration = (10 * attempt).seconds
              logRetry(cause, attempt, duration) >> coolDown(duration) >> connectWithRetry(attempt + 1)
            }
        }
    }
    for {
      _ <- connectWithRetry(1)
      _ <- lift { logger.debug(s"Disconnected from ${peerInfo.address}. Reconnect.") }
      _ <- connectRoutine(peerInfo)(connect, coolDown)
    } yield ()
  }

  trait Connected[F[_]] {
    def count: F[Int]
    def list: F[List[Connection[F]]]
    def stream: Stream[F, Connection[F]]
  }

  sealed class Error(message: String) extends Throwable(message)

  object Error {
    case class ConnectTimeout(duration: FiniteDuration) extends Error(s"Connect timeout after $duration")
  }
}
