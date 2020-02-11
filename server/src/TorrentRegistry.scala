import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import com.github.lavrov.bittorrent.app.domain.InfoHash
import com.github.lavrov.bittorrent.wire.{Swarm, Torrent, UtMetadata}
import logstage.LogIO

import scala.concurrent.duration._

trait TorrentRegistry {
  def get(infoHash: InfoHash): Resource[IO, IO[Torrent[IO]]]
  def tryGet(infoHash: InfoHash): Resource[IO, Option[Torrent[IO]]]
}

object TorrentRegistry {

  def make(
    makeSwarm: InfoHash => Resource[IO, Swarm[IO]]
  )(implicit cs: ContextShift[IO], timer: Timer[IO], logger: LogIO[IO]): IO[TorrentRegistry] =
    for {
      ref <- Ref.of[IO, Registry](emptyRegistry)
    } yield new Impl(ref, makeSwarm)

  case class UsageCountingCell(
    get: IO[Torrent[IO]],
    resolved: Option[Torrent[IO]],
    close: IO[Unit],
    count: Int,
    usedCount: Int
  )

  private type Registry = Map[InfoHash, UsageCountingCell]
  private val emptyRegistry: Registry = Map.empty

  private class Impl(ref: Ref[IO, Registry], makeSwarm: InfoHash => Resource[IO, Swarm[IO]])(
    implicit cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ) extends TorrentRegistry {
    def get(infoHash: InfoHash): Resource[IO, IO[Torrent[IO]]] = Resource {
      ref
        .modify { registry =>
          registry.get(infoHash) match {
            case Some(cell) =>
              val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
              val updatedRegistry = registry.updated(infoHash, updatedCell)
              (updatedRegistry, Right(updatedCell.get))
            case None =>
              val completeDeferred = Deferred.unsafe[IO, Either[Throwable, Torrent[IO]]]
              val closeDeferred = Deferred.unsafe[IO, Unit]
              val getTorrent = completeDeferred.get.flatMap(IO.fromEither)
              val closeTorrent = closeDeferred.complete(())
              val createdCell = UsageCountingCell(getTorrent, none, closeTorrent, 1, 1)
              val updatedRegistry = registry.updated(infoHash, createdCell)
              (updatedRegistry, Left((createdCell.get, completeDeferred.complete _, closeDeferred.get)))
          }
        }
        .flatMap {
          case Right(get) =>
            logger.info(s"Found existing torrent $infoHash") >>
            IO.pure((get, release(infoHash)))
          case Left((get, complete, cancel)) =>
            logger.info(s"Make new torrent $infoHash") >>
            make(infoHash, complete, cancel).as((get, release(infoHash)))
        }
    }

    def tryGet(infoHash: InfoHash): Resource[IO, Option[Torrent[IO]]] = Resource {
      ref
        .modify { registry =>
          registry.get(infoHash) match {
            case Some(cell) if cell.resolved.isDefined =>
              val updatedCell = cell.copy(count = cell.count + 1)
              val updatedRegistry = registry.updated(infoHash, updatedCell)
              (updatedRegistry, cell.resolved)
            case _ =>
              (registry, none)
          }
        }
        .flatMap {
          case Some(torrent) =>
            logger.info(s"Found existing torrent $infoHash") >>
            IO.pure((torrent.some, release(infoHash)))
          case None =>
            logger.info(s"Torrent not found $infoHash") >>
            IO.pure((none, IO.unit))
        }
    }

    private def make(
      infoHash: InfoHash,
      complete: Either[Throwable, Torrent[IO]] => IO[Unit],
      waitCancel: IO[Unit]
    ): IO[Unit] = {
      val makeTorrent =
        for {
          (swarm, metadata) <- makeSwarm(infoHash).evalMap { swarm =>
            UtMetadata.download(swarm).tupleLeft(swarm)
          }
          torrent <- Torrent.make(metadata, swarm)
        } yield torrent
      makeTorrent
        .use { torrent =>
          ref.update { registry =>
            val cell = registry(infoHash)
            val updatedCell = cell.copy(get = IO.pure(torrent), resolved = torrent.some)
            registry.updated(infoHash, updatedCell)
          } >>
          complete(torrent.asRight) >>
          waitCancel
        }
        .handleErrorWith { e =>
          complete(e.asLeft)
        }
        .start
        .void
    }

    private def release(infoHash: InfoHash): IO[Unit] =
      logger.info(s"Release torrent $infoHash") >>
      ref
        .modify { registry =>
          val cell = registry(infoHash)
          val updatedCell = cell.copy(count = cell.count - 1)
          (registry.updated(infoHash, updatedCell), updatedCell)
        }
        .flatMap { cell =>
          if (cell.count == 0)
            logger.info(s"Schedule torrent closure in 10 minutes ${cell.count} ${cell.usedCount} $infoHash") >>
            (
              timer.sleep(2.minutes) >>
              ref.modify { registry =>
                if (registry.get(infoHash).exists(_.usedCount == cell.usedCount))
                  (
                    registry.removed(infoHash),
                    cell.close >> logger.info(s"Closed torrent $infoHash")
                  )
                else
                  (
                    registry,
                    logger.info(s"Keep this torrent running $infoHash")
                  )
              }.flatten
            ).start.void
          else
            logger.info(s"Torrent is still in use ${cell.count} $infoHash")
        }
  }
}
