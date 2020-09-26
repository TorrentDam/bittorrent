package com.github.lavrov.bittorrent.app.protocol

import com.github.lavrov.bittorrent.InfoHash
import scodec.bits.ByteVector
import upickle.default.{macroRW, ReadWriter}

sealed trait Command
object Command {
  case class GetTorrent(infoHash: InfoHash) extends Command

  case class GetDiscovered() extends Command

  case class Search(query: String) extends Command

  import CommonFormats._
  implicit val rw: ReadWriter[Command] = ReadWriter.merge(
    macroRW[GetTorrent],
    macroRW[GetDiscovered],
    macroRW[Search]
  )
}

sealed trait Event
object Event {
  case class RequestAccepted(infoHash: InfoHash) extends Event

  case class Discovered(torrents: Iterable[(InfoHash, String)]) extends Event

  case class TorrentPeersDiscovered(infoHash: InfoHash, connected: Int) extends Event
  case class TorrentMetadataReceived(infoHash: InfoHash, name: String, files: List[File]) extends Event
  case class File(path: List[String], size: Long)

  case class TorrentError(infoHash: InfoHash, message: String) extends Event

  case class TorrentStats(infoHash: InfoHash, connected: Int, availability: List[Double]) extends Event

  case class SearchResults(query: String, entries: List[SearchResults.Entry]) extends Event
  object SearchResults {
    case class Entry(name: String, infoHash: InfoHash, size: Long)
    object Entry {
      import CommonFormats._
      implicit val entryRW: ReadWriter[Entry] = macroRW
    }
  }

  import CommonFormats._
  implicit val fileRW: ReadWriter[File] = macroRW
  implicit val eventRW: ReadWriter[Event] = ReadWriter.merge(
    macroRW[RequestAccepted],
    macroRW[TorrentPeersDiscovered],
    macroRW[TorrentMetadataReceived],
    macroRW[TorrentError],
    macroRW[TorrentStats],
    macroRW[Discovered],
    macroRW[SearchResults]
  )
}

object CommonFormats {

  implicit val infoHashRW: ReadWriter[InfoHash] =
    implicitly[ReadWriter[String]].bimap(
      infoHash => infoHash.bytes.toHex,
      string => InfoHash(ByteVector.fromValidHex(string))
    )
}
