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
import fs2.concurrent.{Signal, SignallingRef, Topic}
import org.legogroup.woof.{*, given}
import org.legogroup.woof.Logger.withLogContext

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
    for
      _ <- Resource.make(logger.info("Starting swarm"))(_ => logger.info("Swarm closed"))
      stateRef <- Resource.eval(SignallingRef(Map.empty[PeerInfo, Connection[F]]))
      topic <- Resource.eval(Topic[F, Connection[F]])
      peerBuffer <- Resource.eval(Queue.bounded[F, PeerInfo](10))
      reconnects <- Resource.eval(Queue.unbounded[F, F[Unit]])
      _ <- F.background(dhtPeers.evalTap(peerBuffer.offer).compile.drain)
      openNewConnection = (peerInfo: PeerInfo) =>
        connectRoutine(
          (for
            _ <- logger.trace(s"Connecting")
            _ <- connect(peerInfo).use { connection =>
              logger.trace(s"Add to swarm") >>
                stateRef.update(_.updated(peerInfo, connection)) >>
                topic.publish1(connection) >>
                logger.trace(s"Published connection") >>
                connection.disconnected >>
                stateRef.update(_ - peerInfo) >>
                logger.trace(s"Remove from swarm")
            }
          yield ()).withLogContext("address", peerInfo.address.toString),
          (delay, reconnect) => F.start(F.sleep(delay) >> reconnects.offer(reconnect)).void
        )
      connectOrReconnect = F
        .race(peerBuffer.take, reconnects.take)
        .flatMap {
           case Left(peerInfo) => openNewConnection(peerInfo)
           case Right(reconnect) => reconnect
        }
        .foreverM
      _ <- F.background(
        F.parReplicateAN(maxConnections)(
          maxConnections,
          connectOrReconnect
        )
      )
    yield
      new Impl(stateRef, topic)
    end for

  private class Impl[F[_]](
    stateRef: SignallingRef[F, Map[PeerInfo, Connection[F]]],
    topic: Topic[F, Connection[F]]
  )(using F: Monad[F])
      extends Swarm[F] {
    val connected: Connected[F] = new Connected[F] {
      def count: Signal[F, Int] = stateRef.map(_.size)
      def list: F[List[Connection[F]]] = stateRef.get.map(_.values.toList)
      def stream: Stream[F, Connection[F]] =
        Stream.evalSeq(stateRef.get.map(_.values.toList)) ++ topic.subscribe(10)
    }
  }

  private def connectRoutine[F[_]](
    connect: F[Unit],
    scheduleReconnect: (FiniteDuration, F[Unit]) => F[Unit]
  )(
    using
    F: Temporal[F],
    logger: Logger[F]
  ): F[Unit] =
    given RoutineLogger[F] = RoutineLogger(logger)
    connectRoutine0(
      connect = connect.attempt,
      sleep = duration => reconnect => scheduleReconnect(duration, reconnect)
    )

  private def connectRoutine0[F[_]](
    connect: F[Either[Throwable, Unit]],
    sleep: FiniteDuration => F[Unit] => F[Unit]
  )(
    using
    F: Monad[F],
    logger: RoutineLogger[F]
  ): F[Unit] = {
    val maxAttempts = 5
    def connectWithRetry(attempt: Int): F[Unit] =
      connect
        .flatMap {
          case Right(_) =>
            logger.disconnected >> connectWithRetry(1)
          case Left(e) =>
            val cause = e.getMessage
            if attempt == 0 || attempt == maxAttempts
            then logger.gaveUp
            else
              val duration = (10 * attempt).seconds
              logger.connectionFailed(attempt, cause, duration)
                >> sleep(duration)(connectWithRetry(attempt + 1))
        }
    connectWithRetry(0)
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
