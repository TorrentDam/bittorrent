import java.nio.file.Paths

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import com.github.lavrov.bittorrent.app.domain.InfoHash
import com.github.lavrov.bittorrent.wire.{PieceStore, Swarm, Torrent, UtMetadata}
import logstage.LogIO

import scala.concurrent.duration._

trait TorrentRegistry {
  def get(infoHash: InfoHash): Resource[IO, IO[ServerTorrent]]
  def tryGet(infoHash: InfoHash): Resource[IO, Option[ServerTorrent]]
}

object TorrentRegistry {

  def make(
    makeSwarm: InfoHash => Resource[IO, Swarm[IO]]
  )(implicit cs: ContextShift[IO], blocker: Blocker, timer: Timer[IO], logger: LogIO[IO]): IO[TorrentRegistry] =
    for {
      ref <- Ref.of[IO, Registry](emptyRegistry)
    } yield new Impl(ref, makeSwarm)

  case class UsageCountingCell(
    get: IO[ServerTorrent],
    resolved: Option[ServerTorrent],
    close: IO[Unit],
    count: Int,
    usedCount: Int
  )

  private type Registry = Map[InfoHash, UsageCountingCell]
  private val emptyRegistry: Registry = Map.empty

  private class Impl(ref: Ref[IO, Registry], makeSwarm: InfoHash => Resource[IO, Swarm[IO]])(
    implicit cs: ContextShift[IO],
    blocker: Blocker,
    timer: Timer[IO],
    logger: LogIO[IO]
  ) extends TorrentRegistry {

    def get(infoHash: InfoHash): Resource[IO, IO[ServerTorrent]] = Resource {
      implicit val logger: LogIO[IO] = loggerWithContext(infoHash)
      ref
        .modify { registry =>
          registry.get(infoHash) match {
            case Some(cell) =>
              val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
              val updatedRegistry = registry.updated(infoHash, updatedCell)
              (updatedRegistry, Right(updatedCell.get))
            case None =>
              val completeDeferred = Deferred.unsafe[IO, Either[Throwable, ServerTorrent]]
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
            logger.debug(s"Found existing torrent") >>
            IO.pure((get, release(infoHash)))
          case Left((get, complete, cancel)) =>
            logger.info(s"Make new torrent") >>
            make(infoHash, complete, cancel).as((get, release(infoHash)))
        }
    }

    def tryGet(infoHash: InfoHash): Resource[IO, Option[ServerTorrent]] = Resource {
      implicit val logger: LogIO[IO] = loggerWithContext(infoHash)
      ref
        .modify { registry =>
          registry.get(infoHash) match {
            case Some(cell) if cell.resolved.isDefined =>
              val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
              val updatedRegistry = registry.updated(infoHash, updatedCell)
              (updatedRegistry, cell.resolved)
            case _ =>
              (registry, none)
          }
        }
        .flatMap {
          case Some(torrent) =>
            logger.debug(s"Found existing torrent") >>
            IO.pure((torrent.some, release(infoHash)))
          case None =>
            logger.debug(s"Torrent not found") >>
            IO.pure((none, IO.unit))
        }
    }

    private def make(
      infoHash: InfoHash,
      complete: Either[Throwable, ServerTorrent] => IO[Unit],
      waitCancel: IO[Unit]
    )(implicit logger: LogIO[IO]): IO[Unit] = {
      val makeTorrent =
        for {
          (swarm, metadata) <- makeSwarm(infoHash).evalMap { swarm =>
            UtMetadata
              .download(swarm)
              .timeout(1.minute)
              .tupleLeft(swarm)
              .flatTap { _ =>
                logger.info(s"Metadata downloaded")
              }
          }
          pieceStore <- PieceStore.disk[IO](Paths.get(s"/tmp", s"bittorrent-${infoHash.toString}"))
          torrent <- Torrent.make(metadata, swarm)
          torrent <- ServerTorrent.make(torrent, pieceStore)
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
          logger.error(s"Could not create torrent $e") >>
          complete(e.asLeft)
        }
        .start
        .void
    }

    private def release(infoHash: InfoHash)(implicit logger: LogIO[IO]): IO[Unit] =
      logger.debug(s"Release torrent") >>
      ref
        .modify { registry =>
          val cell = registry(infoHash)
          val updatedCell = cell.copy(count = cell.count - 1)
          (registry.updated(infoHash, updatedCell), updatedCell)
        }
        .flatMap { cell =>
          if (cell.count == 0)
            scheduleClose(infoHash, _.usedCount == cell.usedCount)
          else
            logger.debug(s"Torrent is still in use ${cell.count}")
        }

    private def scheduleClose(infoHash: InfoHash, closeIf: UsageCountingCell => Boolean)(
      implicit logger: LogIO[IO]
    ): IO[Unit] = {
      val idleTimeout = 5.minutes
      val waitAndTry =
        logger.debug(s"Schedule torrent closure in $idleTimeout") >>
        timer.sleep(idleTimeout) >>
        tryClose(infoHash, closeIf)
      waitAndTry.start.void
    }

    private def tryClose(infoHash: InfoHash, closeIf: UsageCountingCell => Boolean)(
      implicit logger: LogIO[IO]
    ): IO[Unit] = {
      ref.modify { registry =>
        registry.get(infoHash) match {
          case Some(cell) if closeIf(cell) =>
            (
              registry.removed(infoHash),
              cell.close >> logger.info(s"Closed torrent")
            )
          case _ =>
            (
              registry,
              IO.unit
            )
        }
      }.flatten
    }

    private def loggerWithContext(infoHash: InfoHash): LogIO[IO] =
      logger.withCustomContext(("infoHash", infoHash.toString))
  }
}
