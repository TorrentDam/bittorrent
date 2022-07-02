package com.github.lavrov.bittorrent.dht

import cats.syntax.all.given
import cats.effect.IO
import org.legogroup.woof.Logger.StringLocal
import org.legogroup.woof.{LogInfo, LogLevel, Logger}

class NoOpLogger extends Logger[IO] {
  val stringLocal: StringLocal[IO] = NoOpLocal()
  def doLog(level: LogLevel, message: String)(using LogInfo): IO[Unit] = IO.unit
}

class NoOpLocal extends Logger.StringLocal[IO] {
  def ask = List.empty[(String, String)].pure[IO]
  def local[A](fa: IO[A])(f: List[(String, String)] => List[(String, String)]) = fa
}
