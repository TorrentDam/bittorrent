package com.github.torrentdam.bittorrent.net

import cats.effect.Async
import cats.effect.LiftIO
import fs2.io.net.Network

object Networks {

  def custom[F[_]](using async: Async[F], liftIO: LiftIO[F]): Network[F] =
    PlatformNetworks.custom[F]
}
