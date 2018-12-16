package com.github.lavrov.bittorrent

import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import com.github.lavrov.bencode.decode
import com.github.lavrov.bittorrent.dht.{Client, InfoHash, NodeId}
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
          bytes <- IO.pure(Files.readAllBytes(Paths.get("src/test/resources/bencode/ubuntu-18.10-live-server-amd64.iso.torrent")))
          bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
          infoDict <- IO.fromEither(MetaInfo.RawInfoFormat.read(bc).left.map(new Exception(_)))
          infoHash = InfoHash(util.sha1Hash(infoDict))
          _ = println(s"Searching for peers on $infoHash")
          r <- client.getPeersAlgo(infoHash)
          _ <- IO(println(s"Found peers: $r"))
        }
        yield ExitCode.Success
      }

  }
}
