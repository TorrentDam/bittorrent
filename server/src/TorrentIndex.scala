import cats.effect.IO

import scala.util.chaining.scalaUtilChainingOps

trait TorrentIndex {
  import TorrentIndex.Entry

  def byName(name: String): List[Entry]
}

object TorrentIndex {

  def apply(): IO[TorrentIndex] = {

    for {
      response <- IO { requests.get("https://raw.githubusercontent.com/TorrentDam/torrents/master/index/index.json") }
      entries <- IO { upickle.default.read[List[Entry]](response.bytes) }
    } yield impl(entries)
  }

  private def impl(entries: List[Entry]): TorrentIndex = {
    entries
      .map(e => (e.name.toLowerCase, e))
      .pipe { entries =>
        new TorrentIndex {
          def byName(name: String): List[Entry] =
            entries.collect {
              case (searchField, entry) if searchField.contains(name.toLowerCase) => entry
            }
        }
      }

  }

  case class Index(entries: List[(String, Entry)])
  case class Entry(name: String, infoHash: String, size: Long, ext: List[String])
  object Entry {
    implicit val jsonRW: upickle.default.ReadWriter[Entry] = upickle.default.macroRW
  }
}
