package com.github.lavrov.bittorrent.wire

import cats.implicits._
import cats.effect.Sync
import com.github.lavrov.bittorrent.MetaInfo

object DownloadMetadata {

  def apply[F[_]](swarm: Swarm[F])(implicit F: Sync[F]): F[MetaInfo] =
    swarm.connected.stream
      .evalMap(_.downloadMetadata.attempt)
      .collectFirst {
        case Right(Some(metadata)) => metadata
      }
      .evalMap { bytes =>
        F.fromEither(MetaInfo.fromBytes(bytes))
      }
      .compile
      .lastOrError
}
