package com.github.torrentdam.bittorrent.net

import cats.effect.Async
import cats.effect.LiftIO
import fs2.io.net.NativeDatagramNetwork
import fs2.io.net.Network

object PlatformNetworks {

  def custom[F[_]](using async: Async[F], liftIO: LiftIO[F]): Network[F] =
    new NativeDatagramNetwork[F]
}
