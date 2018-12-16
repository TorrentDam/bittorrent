package com.github.lavrov.bittorrent

import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import com.github.lavrov.bencode.decode
import com.github.lavrov.bittorrent.dht.{NodeId, Client => DHTClient}
import com.github.lavrov.bittorrent.protocol.message.Handshake
import com.github.lavrov.bittorrent.protocol.{Client => BTClient}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}

import scala.util.Random

object Main extends IOApp {

  val rnd = new Random

  def run(args: List[String]): IO[ExitCode] = {
    implicit val AG = AsynchronousSocketGroup()
    Socket[IO](address = new InetSocketAddress(6881))
      .use { socket =>
        val selfId: PeerId = PeerId.generate(rnd)
        val dhtClient = new DHTClient[IO](NodeId.generate(rnd), socket)
        val btClient = new BTClient[IO](selfId, socket)
        for {
          bytes <- IO.pure(Files.readAllBytes(Paths.get("src/test/resources/bencode/ubuntu-18.10-live-server-amd64.iso.torrent")))
          bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
          infoDict <- IO.fromEither(MetaInfo.RawInfoFormat.read(bc).left.map(new Exception(_)))
          infoHash = InfoHash(util.sha1Hash(infoDict))
          _ = println(s"Searching for peers on $infoHash")
          r <- dhtClient.getPeersAlgo(infoHash)
          _ <- IO(println(s"Found peers: $r"))
          _ <- btClient.handshake(r.head, infoHash)
        }
        yield ExitCode.Success
      }
  }

}
