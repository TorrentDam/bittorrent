import cats.implicits._
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, IO, Resource, Timer}
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import com.github.lavrov.bittorrent.dht.{Node, PeerDiscovery}
import com.github.lavrov.bittorrent.wire.{Connection, DownloadMetadata}
import fs2.Stream
import logstage.LogIO

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

object MetadataDiscovery {

  def apply(
    infoHashes: Stream[IO, InfoHash],
    peerDiscovery: PeerDiscovery[IO],
    connect: (InfoHash, PeerInfo) => Resource[IO, Connection[IO]]
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
        .evalMap { infoHash =>
          val connections =
            peerDiscovery
              .discover(infoHash)
              .flatMap { peerInfo =>
                Stream
                  .eval(logger.info(s"Discovered $peerInfo")) >>
                Stream
                  .resource(connect(infoHash, peerInfo))
                  .attempt
              }
              .collect { case Right(connection) => connection }
          DownloadMetadata(connections)
            .timeoutTo(
              5.minute,
              logger.info(s"Could not download metadata for $infoHashes")
            )
            .flatTap { metadata =>
              logger.info(s"Discovered $metadata")
            }
            .attempt
        }
        .compile
        .drain
        .onError {
          case e: Throwable =>
            logger.error(s"Failed with $e")
        }
    }
  }
}
