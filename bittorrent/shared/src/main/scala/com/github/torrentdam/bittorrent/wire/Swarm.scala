package com.github.torrentdam.bittorrent.wire

import cats.*
import cats.effect.implicits.*
import cats.effect.std.{Queue, Supervisor}
import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*
import com.github.torrentdam.bittorrent.PeerInfo
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.Stream
import org.legogroup.woof.*
import org.legogroup.woof.given

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

  private case class SwarmConnection(connect: Resource[IO, Connection], retries: Int):
    def retried = copy(retries = retries + 1)

  def apply(
    peers: Stream[IO, PeerInfo],
    connect: PeerInfo => Resource[IO, Connection]
  )(using
    logger: Logger[IO]
  ): Resource[IO, Swarm] =
    for
      supervisor <- Supervisor[IO]
      _ <- Resource.make(logger.info("Starting swarm"))(_ => logger.info("Swarm closed"))
      stateRef <- SignallingRef[IO].of(Map.empty[PeerInfo, Connection]).toResource
      newConnections <- Queue.bounded[IO, SwarmConnection](10).toResource
      reconnects <- Queue.unbounded[IO, SwarmConnection].toResource
      scheduleReconnect = (delay: FiniteDuration, connection: SwarmConnection) =>
        supervisor.supervise(IO.sleep(delay) >> reconnects.offer(connection)).void
      allSeen <- IO.ref(Set.empty[PeerInfo]).toResource
      _ <- peers
        .evalMap(peerInfo =>
          allSeen.getAndUpdate(_ + peerInfo).flatMap { seen =>
            if seen(peerInfo)
            then IO.pure(None)
            else IO.pure(Some(peerInfo))
          }
        )
        .collect { case Some(peerInfo) => peerInfo }
        .map(peerInfo => SwarmConnection(connect(peerInfo), 0))
        .evalTap(newConnections.offer)
        .compile
        .drain
        .background
      connectOrReconnect =
        for
          swarmConnection <- Resource.eval(newConnections.take race reconnects.take).map(_.merge)
          connection <- swarmConnection.connect.onError(_ =>
            if swarmConnection.retries >= 24
            then Resource.unit[IO]
            else
              val delay = (10 * swarmConnection.retries).seconds
              scheduleReconnect(delay, swarmConnection.retried).toResource
          )
        yield connection
    yield new Impl(stateRef, connectOrReconnect)
    end for

  private class Impl(
    stateRef: SignallingRef[IO, Map[PeerInfo, Connection]],
    connectOrReconnect: Resource[IO, Connection]
  ) extends Swarm {
    val connect: Resource[IO, Connection] =
      connectOrReconnect.flatTap(connection =>
        Resource.make {
          stateRef.update(_ + (connection.info -> connection))
        } { _ =>
          stateRef.update(_ - connection.info)
        }
      )
    val connected: Connected = new {
      val count: Signal[IO, Int] = stateRef.map(_.size)
      val list: IO[List[Connection]] = stateRef.get.map(_.values.toList)
    }
  }
}
