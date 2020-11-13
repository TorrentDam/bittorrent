import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.effect.concurrent.Ref
import cats.syntax.all._
import logstage.LogIO

import scala.concurrent.duration._
import scala.util.chaining.scalaUtilChainingOps

trait TorrentIndex {
  import TorrentIndex.Entry

  def search(text: String): IO[List[Entry]]
}

object TorrentIndex {

  def apply()(implicit timer: Timer[IO], cs: ContextShift[IO], logger: LogIO[IO]): Resource[IO, TorrentIndex] = {

    val ref = Ref.unsafe[IO, Index](Index())

    refresh(ref)
      .background
      .map { _ =>
        impl(ref.get)
      }
  }

  private def impl(entries: IO[Index]): TorrentIndex = {
    new TorrentIndex {
      def search(text: String): IO[List[Entry]] = {
        val words = text.toLowerCase.split(' ')
        for (index <- entries)
        yield
          index.entries
            .view
            .map {
              case (searchField, entry) =>
                val score = words.map(word => if (searchField.contains(word)) word.length else 0).sum
                (entry, score)
            }
            .filter(_._2 > 0)
            .toList
            .sortBy(_._2)(Ordering[Int].reverse)
            .take(100)
            .map(_._1)
      }
    }
  }

  private def refresh(ref: Ref[IO, Index])(implicit timer: Timer[IO], logger: LogIO[IO]): IO[Nothing] = {
    IO { requests.get("https://raw.githubusercontent.com/TorrentDam/torrents/master/index/index.json") }
      .map { response =>
        upickle.default.read[List[Entry]](response.bytes)
      }
      .map { entries =>
        Index(entries.map(e => (e.name.toLowerCase, e)))
      }
      .flatMap(ref.set)
      .flatTap { _ =>
        logger.info("Index refreshed")
      }
      .attempt
      .flatMap(_ => IO.sleep(10.minutes))
      .foreverM
  }

  case class Index(entries: List[(String, Entry)] = List.empty)
  case class Entry(name: String, infoHash: String, size: Long, ext: List[String])
  object Entry {
    implicit val jsonRW: upickle.default.ReadWriter[Entry] = upickle.default.macroRW
  }
}
