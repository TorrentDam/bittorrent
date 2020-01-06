package com.github.lavrov.bittorrent.app.protocol

import upickle.default.{macroRW, ReadWriter}

sealed trait Command
object Command {
  case class GetTorrent(infoHash: String) extends Command

  implicit val rw: ReadWriter[Command] = ReadWriter.merge(
    macroRW[GetTorrent]
  )
}

sealed trait Event
object Event {
  case class RequestAccepted(infoHash: String) extends Event

  case class TorrentMetadataReceived(files: List[File]) extends Event
  case class File(path: List[String], size: Long)

  case class TorrentStats(infoHash: String, connected: Int) extends Event

  implicit val fileRW: ReadWriter[File] = macroRW
  implicit val eventRW: ReadWriter[Event] = ReadWriter.merge(
    macroRW[RequestAccepted],
    macroRW[TorrentMetadataReceived],
    macroRW[TorrentStats]
  )
}
