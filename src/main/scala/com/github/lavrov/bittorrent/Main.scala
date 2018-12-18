package com.github.lavrov.bittorrent

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.github.lavrov.bencode.decode
import com.github.lavrov.bittorrent.dht.{NodeId, Client => DHTClient}
import com.github.lavrov.bittorrent.protocol.{Client => BTClient}
import fs2.io.tcp.{Socket => TCPSocket}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}

import scala.util.Random

object Main extends IOApp {

  val rnd = new Random

  def run(args: List[String]): IO[ExitCode] = {
    implicit val ASG = AsynchronousSocketGroup()
    val asyncChannelGroupResource =
      Resource.make(
        IO.pure(
          AsynchronousChannelProvider
            .provider()
            .openAsynchronousChannelGroup(1, Executors.defaultThreadFactory())
        )
      )(g => IO(g.shutdown()))
    Socket[IO](address = new InetSocketAddress(6881)).use { dhtSocket =>
      val selfId: PeerId = PeerId.generate(rnd)
      val dhtClient = new DHTClient[IO](NodeId.generate(rnd), dhtSocket)
      for {
        bytes <- IO.pure(
          Files.readAllBytes(
            Paths.get("src/test/resources/bencode/ubuntu-18.10-live-server-amd64.iso.torrent")))
        bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
        infoDict <- IO.fromEither(MetaInfo.RawInfoFormat.read(bc).left.map(new Exception(_)))
        infoHash = InfoHash(util.sha1Hash(infoDict))
        _ = println(s"Searching for peers on $infoHash")
        addressList <- dhtClient.getPeersAlgo(infoHash)
        firstPeer = addressList.head
        _ <- IO(println(s"Found peers: $addressList"))
        _ <- asyncChannelGroupResource
          .flatMap { implicit g => TCPSocket.client[IO](to = firstPeer.address) }
          .use { peerSocket =>
          val btClient = new BTClient[IO](selfId, peerSocket)
          for {
            hs <- btClient.handshake(infoHash)
            _ <- IO(println(s"Successful handshake: '$hs'"))
          } yield hs
        }
      } yield ExitCode.Success
    }
  }

}
