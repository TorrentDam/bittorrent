package com.github.lavrov.bittorrent

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import cats.effect._
import cats.implicits._
import com.github.lavrov.bencode.decode
import com.github.lavrov.bittorrent.dht.{NodeId, Client => DHTClient}
import com.github.lavrov.bittorrent.protocol.{Connection, Downloading, FileSink}
import fs2.io.tcp.{Socket => TCPSocket}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}

import scala.util.Random

object Main extends IOApp {

  val rnd = new Random

  def run(args: List[String]): IO[ExitCode] = {
    val selfId = PeerId.generate(rnd)
    val resources = for {
      a <- Resource.make(IO(AsynchronousSocketGroup()))(r => IO(r.close()))
      b <- Resource.make(
        IO(
          AsynchronousChannelProvider
            .provider()
            .openAsynchronousChannelGroup(2, Executors.defaultThreadFactory())
        )
      )(g => IO(g.shutdown()))
    } yield (a, b)

    resources.use {
      case (asg, acg) =>
        implicit val asyncSocketGroup: AsynchronousSocketGroup = asg
        implicit val asyncChannelGroup: AsynchronousChannelGroup = acg

        for {
          torrentInfo <- getMetaInfo
          (infoHash, metaInfo) = torrentInfo
          Info.SingleFile(_, pieceLength, _, _, _) = metaInfo.info
          firstPeer = PeerInfo(new InetSocketAddress(57491))
          _ <- IO(println(s"Start downloading"))
          download <- Downloading.start[IO](metaInfo)
          _ <- IO(println(s"Connecting to $firstPeer"))
          _ <- connectToPeer(firstPeer, selfId, infoHash).use { connection =>
            Resource
              .fromAutoCloseable(
                IO apply FileSink(metaInfo, Paths.get("/Users/vitaly/Downloads/my_torrent"))
              )
              .use { fileSink =>
                for {
                  _ <- download.send(Downloading.Command.AddPeer(connection))
                  _ <- download.completePieces
                    .evalTap(p => IO(println(s"Complete: $p")))
                    .evalTap(p => IO(fileSink.write(p.index, p.begin, p.bytes)))
                    .compile
                    .drain
                  _ <- IO(println("The End"))
                } yield ()
              }
          }
        } yield ExitCode.Success
    }
  }

  def getMetaInfo: IO[(InfoHash, MetaInfo)] = {
    for {
      bytes <- IO(
        Files.readAllBytes(
//          Paths.get("src/test/resources/bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
          Paths.get("/Users/vitaly/Downloads/my_torrent/The.Expanse.S03E12-E13.torrent")
        )
      )
      bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
      infoDict <- IO.fromEither(MetaInfo.RawInfoFormat.read(bc).left.map(new Exception(_)))
      metaInfo <- IO.fromEither(MetaInfo.MetaInfoFormat.read(bc).left.map(new Exception(_)))
    } yield (InfoHash(util.sha1Hash(infoDict)), metaInfo)
  }

  def getPeers(
      infoHash: InfoHash
  )(implicit asynchronousSocketGroup: AsynchronousSocketGroup): IO[List[PeerInfo]] =
    Socket[IO](address = new InetSocketAddress(6881)).use { socket =>
      val dhtClient = new DHTClient[IO](NodeId.generate(rnd), socket)
      dhtClient.getPeersAlgo(infoHash)
    }

  def connectToPeer(peerInfo: PeerInfo, selfId: PeerId, infoHash: InfoHash)(
      implicit asynchronousChannelGroup: AsynchronousChannelGroup
  ): Resource[IO, Connection[IO]] =
    TCPSocket.client[IO](to = peerInfo.address).flatMap { socket =>
      Resource.make(Connection.connect(selfId, infoHash, socket, timer))(_ => IO.unit)
    }

}
