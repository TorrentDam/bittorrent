package com.github.lavrov.bittorrent.wire

import cats.*
import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.{IO, Outcome, Resource}
import cats.effect.std.Queue
import com.github.lavrov.bittorrent.PeerInfo
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef, Topic}
import org.legogroup.woof.{*, given}
import org.legogroup.woof.Logger.withLogContext

import scala.concurrent.duration.*

trait Swarm {
  def connect: Resource[IO, Connection]
  def connected: Connected
}

trait Connected {
  def count: Signal[IO, Int]
  def list: IO[List[Connection]]
}

object Swarm {

  def apply(
    peers: Stream[IO, PeerInfo],
    connect: PeerInfo => Resource[IO, Connection]
  )(using
    logger: Logger[IO]
  ): Resource[IO, Swarm] =
    for
      _ <- Resource.make(logger.info("Starting swarm"))(_ => logger.info("Swarm closed"))
      stateRef <- Resource.eval(SignallingRef[IO].of(Map.empty[PeerInfo, Connection]))
      newConnections <- Resource.eval(Queue.bounded[IO, Resource[IO, Connection]](10))
      reconnects <- Resource.eval(Queue.unbounded[IO, Resource[IO, Connection]])
      scheduleReconnect = (delay: FiniteDuration) =>
        (reconnect: Resource[IO, Connection]) => (IO.sleep(delay) >> reconnects.offer(reconnect)).start.void
      allSeen <- Resource.eval(IO.ref(Set.empty[PeerInfo]))
      _ <- peers
        .evalMap(peerInfo =>
          allSeen.getAndUpdate(_ + peerInfo).flatMap { seen =>
            if seen(peerInfo)
            then IO.pure(None)
            else IO.pure(Some(peerInfo))
          }
        )
        .collect { case Some(peerInfo) => peerInfo }
        .map(peerInfo => newConnection(connect(peerInfo), scheduleReconnect))
        .evalTap(newConnections.offer)
        .compile
        .drain
        .background
      connectOrReconnect =
        for
          resource <- Resource.eval(newConnections.take race reconnects.take)
          connection <- resource.merge
        yield connection
    yield new Impl(stateRef, connectOrReconnect)
    end for

  private class Impl(stateRef: SignallingRef[IO, Map[PeerInfo, Connection]], connectOrReconnect: Resource[IO, Connection])(using
    logger: Logger[IO]
  ) extends Swarm {
    val connect: Resource[IO, Connection] = connectOrReconnect
    val connected: Connected = new {
      val count: Signal[IO, Int] = stateRef.map(_.size)
      val list: IO[List[Connection]] = stateRef.get.map(_.values.toList)
    }
  }

  private def newConnection(
    connect: Resource[IO, Connection],
    schedule: FiniteDuration => Resource[IO, Connection] => IO[Unit]
  ): Resource[IO, Connection] = {
    val maxAttempts = 24
    def connectWithRetry(attempt: Int): Resource[IO, Connection] =
      connect
        .onFinalizeCase {
          case Resource.ExitCase.Succeeded =>
            schedule(10.second)(connectWithRetry(1))
          case Resource.ExitCase.Errored(_) =>
            if attempt == maxAttempts
            then IO.unit
            else
              val duration = (10 * attempt).seconds
              schedule(duration)(connectWithRetry(attempt + 1))
          case _ =>
            IO.unit
        }
    connectWithRetry(1)
  }
}
