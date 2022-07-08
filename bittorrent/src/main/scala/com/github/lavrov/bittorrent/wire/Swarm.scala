package com.github.lavrov.bittorrent.wire

import cats.*
import cats.data.ContT
import cats.effect.implicits.*
import cats.effect.kernel.{Resource, Temporal}
import cats.effect.std.Queue
import cats.implicits.*
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.wire.Swarm.Connected
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import org.legogroup.woof.{*, given}

import scala.concurrent.duration.*

trait Swarm[F[_]] {
  def connected: Connected[F]
}

object Swarm {

  def apply[F[_]](
    dhtPeers: Stream[F, PeerInfo],
    connect: PeerInfo => Resource[F, Connection[F]],
    maxConnections: Int = 10
  )(
    using
    F: Temporal[F],
    defer: Defer[F],
    logger: Logger[F]
  ): Resource[F, Swarm[F]] =
    Resource {
      for
        stateRef <- SignallingRef(Map.empty[PeerInfo, Connection[F]])
        lastConnected <- SignallingRef[F, Connection[F]](null)
        peerBuffer <- Queue.bounded[F, PeerInfo](10)
        reconnects <- Queue.unbounded[F, F[Unit]]
        fiber1 <- dhtPeers.evalTap(peerBuffer.offer).compile.drain.start
        connectionFibers <- F.replicateA(
          maxConnections,
          openConnections[F](
            peerBuffer.take,
            reconnects,
            peerInfo =>
              for
                _ <- logger.debug(s"Connecting to ${peerInfo.address}")
                _ <- connect(peerInfo).use { connection =>
                  stateRef.update(_.updated(peerInfo, connection)) >>
                  lastConnected.set(connection) >>
                  connection.disconnected >>
                  stateRef.update(_ - peerInfo)
                }
              yield ()
          )
            .foreverM[Unit]
            .start
        )
      yield
        val impl = new Impl(stateRef, lastConnected)
        val close = fiber1.cancel >> connectionFibers.traverse_(_.cancel) >> logger.info("Closed Swarm")
        (impl, close)
      end for
    }

  private class Impl[F[_]](
    stateRef: SignallingRef[F, Map[PeerInfo, Connection[F]]],
    lastConnected: SignallingRef[F, Connection[F]]
  )(using F: Monad[F])
      extends Swarm[F] {
    val connected: Connected[F] = new Connected[F] {
      def count: Signal[F, Int] = stateRef.map(_.size)
      def list: F[List[Connection[F]]] = stateRef.get.map(_.values.toList)
      def stream: Stream[F, Connection[F]] =
        Stream.evalSeq(stateRef.get.map(_.values.toList)) ++ lastConnected.discrete.tail
    }
  }

  private def openConnections[F[_]](discover: F[PeerInfo], reconnects: Queue[F, F[Unit]], connect: PeerInfo => F[Unit])(
    using
    F: Temporal[F],
    defer: Defer[F],
    logger: Logger[F]
  ): F[Unit] =
    for
      continuation <- reconnects.tryTake.flatMap {
        case Some(c) => c.pure[F]
        case None =>
          type Cont[A] = ContT[F, Unit, A]
          given logger1: RoutineLogger[Cont] = RoutineLogger(logger).mapK(ContT.liftK)
          F.race(discover, reconnects.take).map {
            _.leftMap { discovered =>
              connectRoutine[Cont](discovered)(
                connect = ContT.liftF {
                  connect(discovered).attempt
                },
                coolDown = duration =>
                  ContT { k0 =>
                    val k = k0(())
                    (F.sleep(duration) >> reconnects.offer(k)).start.void
                  }
              ).run(_ => F.unit)
            }.merge
          }
      }
      _ <- continuation
    yield ()

  private def connectRoutine[F[_]](
    peerInfo: PeerInfo
  )(connect: F[Either[Throwable, Unit]], coolDown: FiniteDuration => F[Unit])(
    using
    F: Monad[F],
    logger: RoutineLogger[F]
  ): F[Unit] = {
    val maxAttempts = 10
    def connectWithRetry(attempt: Int): F[Unit] =
      connect
        .flatMap {
          case Right(_) =>
            logger.disconnected >> connectWithRetry(1)
          case Left(e) =>
            val cause = e.getMessage
            if attempt == maxAttempts
            then logger.gaveUp
            else
              val duration = (10 * attempt).seconds
              logger.connectionFailed(attempt, cause, duration)
                >> coolDown(duration)
                >> connectWithRetry(attempt + 1)
        }
    connectWithRetry(1)
  }

  trait RoutineLogger[F[_]] {

    def gaveUp: F[Unit]

    def connectionFailed(attempt: Int, cause: String, waitDuration: FiniteDuration): F[Unit]

    def disconnected: F[Unit]
  }

  object RoutineLogger {

    def apply[F[_]](logger: Logger[F]): RoutineLogger[F] =
      new RoutineLogger[F] {

        def gaveUp: F[Unit] = logger.trace(s"Gave up and forget this peer")

        def connectionFailed(attempt: Int, cause: String, waitDuration: FiniteDuration): F[Unit] =
          logger.trace(s"Connection failed ($attempt): $cause. Retry connecting in at least $waitDuration")

        def disconnected: F[Unit] =
          logger.trace(s"Disconnected. Trying to reconnect.")
      }

    extension [F[_]](self: RoutineLogger[F])

      def mapK[G[_]](f: F ~> G): RoutineLogger[G] =
        new RoutineLogger[G]:
          def gaveUp: G[Unit] =
            f(self.gaveUp)
          def connectionFailed(attempt: Int, cause: String, waitDuration: FiniteDuration): G[Unit] =
            f(self.connectionFailed(attempt, cause, waitDuration))
          def disconnected: G[Unit] =
            f(self.disconnected)
  }

  trait Connected[F[_]] {
    def count: Signal[F, Int]
    def list: F[List[Connection[F]]]
    def stream: Stream[F, Connection[F]]
  }

  enum Error(message: String) extends Throwable(message):
    case ConnectTimeout(duration: FiniteDuration) extends Error(s"Connect timeout after $duration")
}
