import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import com.github.lavrov.bittorrent.InfoHash
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
      mutex <- Semaphore[IO](1)
    } yield new Impl(ref, mutex, makeSwarm)

  case class UsageCountingCell(get: IO[Torrent[IO]], resolved: Option[Torrent[IO]], close: IO[Unit], count: Int)

  private type Registry = Map[InfoHash, UsageCountingCell]
  private val emptyRegistry: Registry = Map.empty

  private class Impl(ref: Ref[IO, Registry], mutex: Semaphore[IO], makeSwarm: InfoHash => Resource[IO, Swarm[IO]])(
    implicit cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ) extends TorrentRegistry {
    def get(infoHash: InfoHash): Resource[IO, IO[Torrent[IO]]] = Resource {
      mutex.withPermit {
        for {
          registry <- ref.get
          cell <- registry.get(infoHash) match {
            case Some(cell) =>
              val updatedCell = cell.copy(count = cell.count + 1)
              logger.info(s"Found existing torrent $infoHash") >>
              IO.pure(updatedCell)
            case None =>
              make(infoHash)
          }
          updatedRegistry = registry.updated(infoHash, cell)
          _ <- ref.set(updatedRegistry)
        } yield (cell.get, release(infoHash))
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

    private def make(infoHash: InfoHash): IO[UsageCountingCell] = {
      val makeTorrent =
        for {
          (swarm, metadata) <- makeSwarm(infoHash).evalMap { swarm =>
            UtMetadata.download(swarm).tupleLeft(swarm)
          }
          torrent <- Torrent.make(metadata, swarm)
        } yield torrent
      for {
        _ <- logger.info(s"Make new torrent $infoHash")
        fiber <- makeTorrent.allocated.flatTap {
          case (torrent, close) =>
            ref.update { registry =>
              val cell = registry(infoHash)
              val updatedCell = cell.copy(get = IO.pure(torrent), resolved = torrent.some, close = close)
              registry.updated(infoHash, updatedCell)
            }
        }.start
      } yield UsageCountingCell(fiber.join.map(_._1), none, fiber.cancel, 1)
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
            logger.info(s"Schedule torrent closure in 10 minutes $infoHash") >>
            timer.sleep(10.minutes) >>
            ref
              .modify { registry =>
                if (registry.get(infoHash).exists(_.count == 0))
                  (
                    registry.removed(infoHash),
                    cell.close >> logger.info(s"Closed torrent $infoHash")
                  )
                else
                  (registry, IO.unit)
              }
              .flatten
              .start
              .void
          else
            logger.info(s"Keep this torrent running ${cell.count} $infoHash")
        }
  }
}
