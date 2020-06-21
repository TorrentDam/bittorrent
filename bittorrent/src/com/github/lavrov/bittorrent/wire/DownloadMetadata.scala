package com.github.lavrov.bittorrent.wire

import cats.implicits._
import cats.effect.Sync
import com.github.lavrov.bittorrent.MetaInfo
import fs2.Stream

object DownloadMetadata {

  def apply[F[_]](connections: Stream[F, Connection[F]])(implicit F: Sync[F]): F[MetaInfo] =
    connections
      .evalMap(_.extensionApi)
      .map(_.utMetadata)
      .collect {
        case Some(utMetadata) => utMetadata
      }
      .evalMap(_.fetch.attempt)
      .collectFirst {
        case Right(metadata) => metadata
      }
      .compile
      .lastOrError
}
