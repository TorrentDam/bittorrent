package com.github.lavrov.bittorrent
import java.net.InetSocketAddress

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.apply._
import com.github.lavrov.bittorrent.dht.{Client, NodeId}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}

import scala.util.Random

object Main extends IOApp {

  val rnd = new Random

  def run(args: List[String]): IO[ExitCode] = {
    implicit val AG = AsynchronousSocketGroup()
    Socket[IO](address = new InetSocketAddress(6881))
      .use { socket =>
        val client = new Client[IO](NodeId.generate(rnd), socket)
        for {
          m <- client.main
          _ <- IO(println(s"Message: $m"))
        }
        yield ExitCode.Success
      }

  }
}
