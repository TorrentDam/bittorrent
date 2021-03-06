package com.github.lavrov.bittorrent.wire

import cats.MonadThrow
import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.Temporal
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import fs2.Stream

import scala.concurrent.duration.*

object DownloadMetadata {

  def apply[F[_]](connections: Stream[F, Connection[F]])(using F: Temporal[F]): F[Lossless] =
    connections
      .parEvalMapUnordered(10)(connection =>
        DownloadMetadata(connection)
          .timeout(1.minute)
          .attempt
      )
      .collectFirst {
        case Right(metadata) => metadata
      }
      .compile
      .lastOrError

  def apply[F[_]](connection: Connection[F])(using F: MonadThrow[F]): F[Lossless] =
    connection.extensionApi
      .flatMap(_.utMetadata.liftTo[F](UtMetadataNotSupported()))
      .flatMap(_.fetch)

  case class UtMetadataNotSupported() extends Throwable("UtMetadata is not supported")

}
