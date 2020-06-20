import java.nio.file.Paths

import ServerTorrent.Phase.FetchingMetadata
import cats.syntax.all._
import cats.effect.concurrent.Deferred
import cats.effect.{Blocker, Concurrent, ContextShift, IO, Resource, Timer}
import com.github.lavrov.bittorrent.app.domain.InfoHash
import com.github.lavrov.bittorrent.wire.{DownloadMetadata, Swarm, Torrent}
import com.github.lavrov.bittorrent.{FileMapping, MetaInfo}
import fs2.Stream
import fs2.concurrent.Signal
import logstage.LogIO

trait ServerTorrent {
  def files: FileMapping
  def stats: IO[ServerTorrent.Stats]
  def piece(index: Int): IO[Stream[IO, Byte]]
  def metadata: MetaInfo
}

object ServerTorrent {

  sealed trait Phase
  object Phase {
    case class PeerDiscovery(done: IO[FetchingMetadata]) extends Phase
    case class FetchingMetadata(fromPeers: Signal[IO, Int], done: IO[Ready]) extends Phase
    case class Ready(infoHash: InfoHash, serverTorrent: ServerTorrent) extends Phase
  }

  case class Error() extends Throwable

  def create(infoHash: InfoHash, makeSwarm: InfoHash => Resource[IO, Swarm[IO]])(implicit
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO],
    blocker: Blocker
  ): Resource[IO, Phase.PeerDiscovery] =
    Resource {

      def backgroundTask(peerDiscoveryDone: FallibleDeferred[IO, Phase.FetchingMetadata]): IO[Unit] =
        makeSwarm(infoHash)
          .use { swarm =>
            swarm.connected.count.discrete.find(_ > 0).compile.drain >>
            FallibleDeferred[IO, Phase.Ready].flatMap { fetchingMetadataDone =>
              peerDiscoveryDone.complete(FetchingMetadata(swarm.connected.count, fetchingMetadataDone.get)).flatMap {
                _ =>
                  DownloadMetadata(swarm.connected.stream).flatMap { metadata =>
                    logger.info(s"Metadata downloaded") >>
                    Torrent.make(metadata, swarm).use { torrent =>
                      PieceStore.disk[IO](Paths.get(s"/tmp", s"bittorrent-${infoHash.toString}")).use { pieceStore =>
                        create(torrent, pieceStore).flatMap { serverTorrent =>
                          fetchingMetadataDone.complete(Phase.Ready(infoHash, serverTorrent)).flatMap { _ =>
                            IO.never
                          }
                        }
                      }
                    }
                  }
              }
            }
          }
          .orElse(
            peerDiscoveryDone.fail(Error())
          )

      for {
        peerDiscoveryDone <- FallibleDeferred[IO, Phase.FetchingMetadata]
        fiber <- backgroundTask(peerDiscoveryDone).start
      } yield (Phase.PeerDiscovery(peerDiscoveryDone.get), fiber.cancel)
    }

  private def create(torrent: Torrent[IO], pieceStore: PieceStore[IO])(implicit
    cs: ContextShift[IO]
  ): IO[ServerTorrent] = {

    def fetch(index: Int): IO[Stream[IO, Byte]] = {
      for {
        bytes <- pieceStore.get(index)
        bytes <- bytes match {
          case Some(bytes) => IO.pure(bytes)
          case None =>
            for {
              bytes <- torrent.piece(index)
              bytes <- pieceStore.put(index, bytes)
            } yield bytes
        }
      } yield bytes
    }

    for {
      multiplexer <- Multiplexer[IO](fetch)
    } yield {
      new ServerTorrent {
        def files: FileMapping = FileMapping.fromMetadata(torrent.getMetaInfo.parsed)
        def stats: IO[Stats] =
          for {
            stats <- torrent.stats
          } yield Stats(
            connected = stats.connected,
            availability = files.value.map { span =>
              val range = span.beginIndex.toInt to span.endIndex.toInt
              val available = range.count(stats.availability.contains)
              available.toDouble / range.size
            }
          )
        def piece(index: Int): IO[Stream[IO, Byte]] = multiplexer.get(index)
        def metadata: MetaInfo = torrent.getMetaInfo
      }
    }
  }

  class Create(createSwarm: InfoHash => Resource[IO, Swarm[IO]])(implicit
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO],
    blocker: Blocker
  ) {
    def apply(infoHash: InfoHash): Resource[IO, Phase.PeerDiscovery] = create(infoHash, createSwarm)
  }

  case class Stats(
    connected: Int,
    availability: List[Double]
  )

  trait FallibleDeferred[F[_], A] {
    def complete(a: A): F[Unit]
    def fail(e: Throwable): F[Unit]
    def get: F[A]
  }

  object FallibleDeferred {
    def apply[F[_], A](implicit F: Concurrent[F]): F[FallibleDeferred[F, A]] = {
      for {
        underlying <- Deferred[F, Either[Throwable, A]]
      } yield new FallibleDeferred[F, A] {
        def complete(a: A): F[Unit] = underlying.complete(a.asRight)
        def fail(e: Throwable): F[Unit] = underlying.complete(e.asLeft)
        def get: F[A] = underlying.get.flatMap(F.fromEither)
      }
    }
  }

}
