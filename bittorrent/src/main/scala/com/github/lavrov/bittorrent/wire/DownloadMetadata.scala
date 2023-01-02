package com.github.lavrov.bittorrent.wire

import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.IO
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import fs2.Stream

import scala.concurrent.duration.*

object DownloadMetadata {

  def apply(connections: Stream[IO, Connection[IO]]): IO[Lossless] =
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

  def apply(connection: Connection[IO]): IO[Lossless] =
    connection.extensionApi
      .flatMap(_.utMetadata.liftTo[IO](UtMetadataNotSupported()))
      .flatMap(_.fetch)

  case class UtMetadataNotSupported() extends Throwable("UtMetadata is not supported")

}
