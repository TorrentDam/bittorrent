package com.github.lavrov.bittorrent.app.protocol

import com.github.lavrov.bittorrent.app.domain.InfoHash
import upickle.default.{macroRW, ReadWriter}

import scala.collection.immutable.BitSet

sealed trait Command
object Command {
  case class GetTorrent(infoHash: InfoHash) extends Command

  implicit val rw: ReadWriter[Command] = ReadWriter.merge(
    macroRW[GetTorrent]
  )
}

sealed trait Event
object Event {
  case class RequestAccepted(infoHash: InfoHash) extends Event

  case class TorrentMetadataReceived(infoHash: InfoHash, files: List[File]) extends Event
  case class File(path: List[String], size: Long)

  case class TorrentError(message: String) extends Event

  case class TorrentStats(infoHash: InfoHash, connected: Int, availability: List[Double]) extends Event

  implicit val fileRW: ReadWriter[File] = macroRW
  implicit val eventRW: ReadWriter[Event] = ReadWriter.merge(
    macroRW[RequestAccepted],
    macroRW[TorrentMetadataReceived],
    macroRW[TorrentError],
    macroRW[TorrentStats]
  )
}
