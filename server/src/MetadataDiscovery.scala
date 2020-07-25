import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, IO, Resource, Timer}
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import com.github.lavrov.bittorrent.dht.PeerDiscovery
import com.github.lavrov.bittorrent.wire.{Connection, DownloadMetadata}
import com.github.lavrov.bittorrent.app.domain
import fs2.Stream
import logstage.LogIO

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

object MetadataDiscovery {

  def apply(
    infoHashes: Stream[IO, InfoHash],
    peerDiscovery: PeerDiscovery[IO],
    connect: (InfoHash, PeerInfo) => Resource[IO, Connection[IO]],
    metadataRegistry: MetadataRegistry[IO]
  )(implicit
    concurrent: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ): IO[Unit] = {

    Ref.of[IO, ListSet[InfoHash]](ListSet.empty).flatMap { ref =>
      infoHashes
        .evalFilter { infoHash =>
          ref.get.map(!_.contains(infoHash))
        }
        .evalTap { infoHash =>
          logger.info(s"Try download metadata for $infoHash") >>
          ref.update(_ incl infoHash)
        }
        .parEvalMapUnordered(100) { infoHash =>
          peerDiscovery
            .discover(infoHash)
            .flatMap { peerInfo =>
              Stream
                .eval(logger.info(s"Discovered $peerInfo")) >>
              Stream
                .resource(
                  connect(infoHash, peerInfo)
                    .timeout(2.second)
                )
                .attempt
                .evalTap {
                  case Right(_) =>
                    logger.info(s"Connected to $peerInfo")
                  case Left(e) =>
                    logger.error(s"Could not connect ${e.getMessage}")
                }
            }
            .collect { case Right(connection) => connection }
            .parEvalMapUnordered(100) { connection =>
              DownloadMetadata(connection)
                .timeout(1.minute)
                .attempt
            }
            .collectFirst { case Right(metadata) => metadata }
            .compile
            .lastOrError
            .timeout(5.minutes)
            .attempt
            .flatMap {
              case Right(metadata) =>
                logger.info(s"Metadata discovered $metadata") >>
                metadataRegistry.put(domain.InfoHash(infoHash.bytes), metadata)
              case Left(e) =>
                logger.error(s"Could download metadata: $e")
            }
        }
        .compile
        .drain
        .onError {
          case e: Throwable =>
            logger.error(s"Failed with $e")
        }
    }
  }

  implicit class ResourceOps[F[_], A](self: Resource[F, A]) {

    def timeout(duration: FiniteDuration)(implicit F: Concurrent[F], timer: Timer[F]): Resource[F, A] =
      Resource.make(self.allocated.timeout(duration))(_._2).map(_._1)
  }
}
