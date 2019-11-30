package com.github.lavrov.bittorrent.app.protocol

import upickle.default.{macroRW, ReadWriter}

sealed trait Command
object Command {
  case class AddTorrent(infoHash: String) extends Command

  implicit val rw: ReadWriter[Command] = ReadWriter.merge(
    macroRW[AddTorrent]
  )
}

sealed trait Event
object Event {
  case class NewTorrent(infoHash: String) extends Event
  implicit val rw: ReadWriter[Event] = ReadWriter.merge(
    macroRW[NewTorrent]
  )
}
