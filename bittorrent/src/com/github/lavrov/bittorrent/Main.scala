package com.github.lavrov.bittorrent

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.data.Validated
import cats.effect._
import cats.implicits._
import com.github.lavrov.bencode.decode
import com.github.lavrov.bittorrent.dht.{NodeId, Client => DHTClient}
import com.github.lavrov.bittorrent.protocol.{Connection, Downloading}
import com.monovore.decline.{Command, Opts}
import fs2.{Sink, Stream}
import fs2.io.tcp.{Socket => TCPSocket}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{BitVector, ByteVector}

import scala.util.Random
import scala.concurrent.duration._

object Main extends IOApp {

  val rnd = new Random

  val downloadCommand = Opts.subcommand(
    name = "download",
    help = "Download files",
  ){
    val torrentFileOpt = Opts.option[String]("torrent", help = "Path to torrent file").map(Paths.get(_))
    val targetDirectoryOpt = Opts.option[String]("target-directory", help = "Path to target directory").map(Paths.get(_))
    (torrentFileOpt, targetDirectoryOpt).mapN {
      case (torrentPath, targetPath) => download(torrentPath, targetPath)
    }
  }

  val findPeersCommand = Opts.subcommand(
    name = "find-peers",
    help = "Find peers by info-hash",
  ){
    val infoHashOpt = Opts
      .option[String]("info-hash", "Info-hash of the torrent file")
      .mapValidated(
        string =>
          Validated.fromEither(ByteVector.fromHexDescriptive(string)).toValidatedNel
      )
      .validate("Must be 20 bytes long")(_.size == 20)
      .map(InfoHash)
    infoHashOpt.map(findPeers)
  }

  val topLevelCommand = Command(
    name = "get-torrent",
    header = "Bittorrent client",
  )(downloadCommand <+> findPeersCommand)

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

  def run(args: List[String]): IO[ExitCode] = {
    topLevelCommand.parse(args) match {
      case Right(thunk) => thunk as ExitCode.Success
      case Left(help) => IO(println(help)) as {
        if (help.errors.isEmpty) ExitCode.Success else ExitCode.Error
      }
    }
  }

  def makeLogger: IO[Logger[IO]] = Slf4jLogger.fromClass[IO](getClass)

  def download(torrentPath: Path, targetDirectory: Path): IO[Unit] = {
    getMetaInfo(torrentPath).flatMap {
      case (infoHash, metaInfo) =>
        resources.use {
          case (asg, acg) =>
            implicit val asyncSocketGroup: AsynchronousSocketGroup = asg
            implicit val asyncChannelGroup: AsynchronousChannelGroup = acg

            for {
              logger <- makeLogger
              foundPeers <- getPeers(infoHash)
              connections = foundPeers
                .evalMap { peer =>
                  logger.info(s"Connecting to $peer") *>
                  connectToPeer(peer, selfId, infoHash)
                    .allocated
                    .map(_._1.some)
                    .start.flatMap(_.join.timeout(1.seconds))
                    .recoverWith {
                      case e =>
                        logger.debug(e)(s"Failed to connect to [$peer]") as none
                    }
                }
                .collect {
                  case Some(c) => c
                }
                .evalTap(_ => logger.info(s"Connected"))
              _ <- logger.info(s"Start downloading")
              downloading <- Downloading.start[IO](metaInfo, connections)
              _ <- saveToFile(targetDirectory, downloading, metaInfo, logger)
            } yield ()
        }
    }
  }

  def getMetaInfo(torrentPath: Path): IO[(InfoHash, MetaInfo)] = {
    for {
      bytes <- IO(Files.readAllBytes(torrentPath))
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
      Resource.make(Connection.connect(selfId, peerInfo, infoHash, socket, timer))(_ => IO.unit)
    }
  }

  def saveToFile[F[_]: Concurrent](targetDirectory: Path, downloading: Downloading[F], metaInfo: MetaInfo, logger: Logger[F]): F[Unit] = {
    val sink = FileSink(metaInfo, targetDirectory)
    for {
      _ <- Concurrent[F].start {
        downloading.completePieces
          .evalTap(p => logger.info(s"Complete piece: ${p.index}"))
          .map(p => FileSink.Piece(p.begin, p.bytes))
          .through(sink)
          .compile
          .drain
          .onError {
            case e =>
              logger.error(e)(s"Download failed")
          }
      }
      _ <- downloading.fiber.join
      _ <- logger.info(s"The End")
    } yield ()
  }

  def findPeers(infoHash: InfoHash): IO[Unit] = for {
    logger <- makeLogger
    _ <- resources.use {
      case (asg, _) =>
        getPeers(infoHash)(asg).flatMap { stream =>
          stream
            .evalTap(peerInfo => logger.info(s"Found peer $peerInfo"))
            .compile.drain
        }
    }
  } yield ()

}
