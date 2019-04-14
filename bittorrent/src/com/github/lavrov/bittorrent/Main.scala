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
import fs2.Stream
import fs2.io.tcp.{Socket => TCPSocket}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.util.Random
import scala.concurrent.duration._

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
          logger <- Slf4jLogger.fromClass[IO](getClass)
          torrentInfo <- getMetaInfo
          (infoHash, metaInfo) = torrentInfo
          Info.SingleFile(_, pieceLength, _, _, _) = metaInfo.info
          peers <- getPeers(infoHash)
          _ <- logger.info(s"Start downloading")
          downloading <- Downloading.start[IO](metaInfo)
          _ <- peers
            .evalTap[IO] { peer =>
              logger.info(s"Connecting to $peer") *>
              connectToPeer(peer, selfId, infoHash).allocated
              .flatMap {
                case (connection, _) =>
                  downloading.send(Downloading.Command.AddPeer(connection))
              }
              .start.flatMap(_.join.timeout(1.seconds))
              .recoverWith {
                case e =>
                  logger.error(e)(s"Failed to connect to [$peer]")
              }
              .void
            }
            .compile.drain.start
          _ <- saveToFile(downloading, metaInfo, logger)
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
  )(implicit asynchronousSocketGroup: AsynchronousSocketGroup): IO[Stream[IO, PeerInfo]] =
    Socket[IO](address = new InetSocketAddress(6881)).allocated.flatMap { case (socket, _) =>
      DHTClient[IO](NodeId.generate(rnd), socket).flatMap { dhtClient =>
        dhtClient.getPeersAlgo(infoHash)
      }
    }

  def connectToPeer(peerInfo: PeerInfo, selfId: PeerId, infoHash: InfoHash)(
      implicit asynchronousChannelGroup: AsynchronousChannelGroup
  ): Resource[IO, Connection[IO]] = {
    TCPSocket.client[IO](to = peerInfo.address).flatMap { socket =>
      Resource.make(Connection.connect(selfId, infoHash, socket, timer))(_ => IO.unit)
    }
  }

  def saveToFile[F[_]: Sync](downloading: Downloading[F], metaInfo: MetaInfo, logger: Logger[F]): F[Unit] = {
    val sink = Sync[F].delay {
      FileSink(metaInfo, Paths.get("/Users/vitaly/Downloads/my_torrent"))
    }
    Resource.fromAutoCloseable(sink).use { fileSink =>
      for {
        _ <- downloading.completePieces
          .evalTap(p => logger.info(s"Complete: $p"))
          .evalTap(p => Sync[F].delay(fileSink.write(p.index, p.begin, p.bytes)))
          .compile
          .drain
        _ <- logger.info("The End")
      } yield ()
    }
  }
}
