package com.github.lavrov.bittorrent.wire

import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.IO
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import fs2.Stream
import org.legogroup.woof.{Logger, given}

import scala.concurrent.duration.*

object DownloadMetadata {

  def apply(swarm: Swarm)(using logger: Logger[IO]): IO[Lossless] =
    logger.info("Downloading metadata") >>
    Stream
      .unit
      .repeat
      .parEvalMapUnordered(10)(_ =>
        swarm
          .connect
          .use(connection =>
            DownloadMetadata(connection).timeout(1.minute)
          )
          .attempt
      )
      .collectFirst {
        case Right(metadata) => metadata
      }
      .compile
      .lastOrError
      .flatTap(_ =>
        logger.info("Metadata downloaded")
      )

  def apply(connection: Connection)(using logger: Logger[IO]): IO[Lossless] =
    connection.extensionApi
      .flatMap(_.utMetadata.liftTo[IO](UtMetadataNotSupported()))
      .flatMap(_.fetch)

  case class UtMetadataNotSupported() extends Throwable("UtMetadata is not supported")

}
