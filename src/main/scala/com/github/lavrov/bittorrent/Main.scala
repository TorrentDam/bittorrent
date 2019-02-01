package com.github.lavrov.bittorrent

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import cats._
import cats.implicits._
import cats.syntax.monad._
import cats.effect._
import com.github.lavrov.bencode.decode
import com.github.lavrov.bittorrent.dht.{NodeId, Client => DHTClient}
import com.github.lavrov.bittorrent.protocol.{Connection, Downloading, PeerConnection}
import fs2.io.tcp.{Socket => TCPSocket}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}
import fs2.Stream

import scala.concurrent.duration._
import scala.util.Random

object Main extends IOApp {

  val rnd = new Random

  def run(args: List[String]): IO[ExitCode] = {
    val selfId = PeerId.generate(rnd)
    val resources = for {
      a <- Resource.make(IO(AsynchronousSocketGroup()))(r => IO(r.close()))
      b <-
        Resource.make(
          IO(
            AsynchronousChannelProvider
              .provider()
              .openAsynchronousChannelGroup(2, Executors.defaultThreadFactory())
          )
        )(g => IO(g.shutdown()))
    }
    yield (a, b)

    resources.use {
      case (asg, acg) =>
        implicit val asyncSocketGroup: AsynchronousSocketGroup = asg
        implicit val asyncChannelGroup: AsynchronousChannelGroup = acg

        for {
          torrentInfo <- getMetaInfo
          (infoHash, metaInfo) = torrentInfo
          Info.SingleFile(pieceLength, _, _, _) = metaInfo.info
//          _ = println(s"Searching for peers on $infoHash")
//          addressList <- getPeers(infoHash)
//          firstPeer = addressList.head
          firstPeer = PeerInfo(new InetSocketAddress(57491))
          _ = println(s"Connecting to $firstPeer")
          _ <- connectToPeer(firstPeer).use { connection =>
            println(s"Connected to $firstPeer")
            val peerConnection = new PeerConnection[IO]
            val downloading = new Downloading[IO](peerConnection)
            for {
              _ <- downloading.start(selfId, infoHash, metaInfo, connection)
            }
            yield ()
          }
        }
        yield ExitCode.Success
    }
  }

  def getMetaInfo: IO[(InfoHash, MetaInfo)] = {
    for {
      bytes <- IO(
        Files.readAllBytes(
          Paths.get("src/test/resources/bencode/ubuntu-18.10-live-server-amd64.iso.torrent")))
      bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
      infoDict <- IO.fromEither(MetaInfo.RawInfoFormat.read(bc).left.map(new Exception(_)))
      metaInfo <- IO.fromEither(MetaInfo.MetaInfoFormat.read(bc).left.map(new Exception(_)))
    }
    yield (InfoHash(util.sha1Hash(infoDict)), metaInfo)
  }

  def getPeers(infoHash: InfoHash)(implicit asynchronousSocketGroup: AsynchronousSocketGroup): IO[List[PeerInfo]] =
    Socket[IO](address = new InetSocketAddress(6881)).use { socket =>
      val dhtClient = new DHTClient[IO](NodeId.generate(rnd), socket)
      dhtClient.getPeersAlgo(infoHash)
    }

  def connectToPeer(peerInfo: PeerInfo)(implicit asynchronousChannelGroup: AsynchronousChannelGroup): Resource[IO, Connection[IO]] =
    TCPSocket.client[IO](to = peerInfo.address).map { socket =>
      new Connection(socket)
    }

}
