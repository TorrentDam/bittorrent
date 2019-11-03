package com.github.lavrov.bittorrent.wire

import cats.effect.{Concurrent, Timer}
import com.github.lavrov.bittorrent.PeerInfo
import fs2.Stream
import fs2.io.tcp.SocketGroup
import logstage.LogIO

trait ConnectionManager[F[_]] {
  def connections: Stream[F, Connection[F]]
}

object ConnectionManager {

  def apply[F[_]](
    dhtPeers: Stream[F, PeerInfo],
    connect: PeerInfo => F[Connection[F]],
    maxConnections: Int = 50
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): ConnectionManager[F] =
    new ConnectionManager[F] {
      def connections: Stream[F, Connection[F]] =
        dhtPeers.evalMap { peerInfo =>
          connect(peerInfo)
        }
    }
}
