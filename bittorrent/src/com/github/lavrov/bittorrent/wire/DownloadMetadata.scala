package com.github.lavrov.bittorrent.wire

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync, Timer}
import com.github.lavrov.bittorrent.MetaInfo
import fs2.Stream

import scala.concurrent.duration._

object DownloadMetadata {

  def apply[F[_]](connections: Stream[F, Connection[F]])(implicit F: Concurrent[F], timer: Timer[F]): F[MetaInfo] =
    connections
      .evalMap(connection =>
        DownloadMetadata(connection)
          .timeout(1.minute)
          .attempt
      )
      .collectFirst {
        case Right(metadata) => metadata
      }
      .compile
      .lastOrError

  def apply[F[_]](connection: Connection[F])(implicit F: Sync[F]): F[MetaInfo] =
    connection.extensionApi
      .flatMap(_.utMetadata.liftTo[F](UtMetadataNotSupported()))
      .flatMap(_.fetch)

  case class UtMetadataNotSupported() extends Throwable("UtMetadata is not supported")

}
