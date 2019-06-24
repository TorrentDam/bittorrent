package com.github.lavrov.bittorrent

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.data.Validated
import cats.effect._
import cats.implicits._
import com.github.lavrov.bencode.{Bencode, BencodeCodec, decode}
import com.github.lavrov.bittorrent.dht.{NodeId, Client => DHTClient}
import com.github.lavrov.bittorrent.protocol.Connection.Event
import com.github.lavrov.bittorrent.protocol.{Connection, Downloading}
import com.monovore.decline.{Command, Opts}
import fs2.Stream
import fs2.io.tcp.{Socket => TCPSocket}
import fs2.io.udp.{AsynchronousSocketGroup, Socket}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{Bases, ByteVector}

import scala.concurrent.duration._
import scala.util.Random
import scodec.bits.BitVector
import io.github.timwspence.cats.stm.TVar
import io.github.timwspence.cats.stm.STM
import fs2.concurrent.Queue
import com.github.lavrov.bittorrent.protocol.ConnectionManager

object Main extends IOApp {

  val rnd = new Random

  val torrentFileOpt =
    Opts.option[String]("torrent", help = "Path to torrent file").map(Paths.get(_))

  val downloadCommand = Opts.subcommand(
    name = "download",
    help = "Download files"
  ) {
    val targetDirectoryOpt =
      Opts.option[String]("target-directory", help = "Path to target directory").map(Paths.get(_))
    (torrentFileOpt, targetDirectoryOpt).mapN {
      case (torrentPath, targetPath) => download(torrentPath, targetPath)
    }
  }

  val infoHashOpt = Opts
    .option[String]("info-hash", "Info-hash of the torrent file")
    .mapValidated(
      string => Validated.fromEither(ByteVector.fromHexDescriptive(string)).toValidatedNel
    )
    .validate("Must be 20 bytes long")(_.size == 20)
    .map(InfoHash)

  val findPeersCommand = Opts.subcommand(
    name = "find-peers",
    help = "Find peers by info-hash"
  ) {
    infoHashOpt.map(findPeers)
  }

  val getTorrentCommand = Opts.subcommand(
    name = "get-torrent",
    help = "Download torrent file by info-hash from peers"
  ) {
    (torrentFileOpt, infoHashOpt).mapN(getTorrent)
  }

  val readTorrentCommand = Opts.subcommand(
    name = "read-torrent",
    help = "Read torrent file from file and print it out"
  ) {
    torrentFileOpt.map(printTorrentMetadata)
  }

  val connectCommand = Opts.subcommand(
    name = "connect",
    help = "Connect at max to 10 peers"
  ) {
    infoHashOpt.map(connect)
  }

  val topLevelCommand = Command(
    name = "get-torrent",
    header = "Bittorrent client"
  )(downloadCommand <+> findPeersCommand <+> getTorrentCommand <+> readTorrentCommand <+> connectCommand)

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
      case Left(help) =>
        IO(println(help)) as {
          if (help.errors.isEmpty) ExitCode.Success else ExitCode.Error
        }
    }
  }

  def makeLogger: IO[Logger[IO]] = Slf4jLogger.fromClass[IO](getClass)

  def download(torrentPath: Path, targetDirectory: Path): IO[Unit] =
    for {
      logger <- makeLogger
      metaInfoResult <- getMetaInfo(torrentPath)
      (infoHash, metaInfo) = metaInfoResult
      _ <- logger.info(s"Info-hash ${infoHash.bytes.toHex}")
      _ <- resources.use {
        case (asg, acg) =>
          implicit val asyncSocketGroup: AsynchronousSocketGroup = asg
          implicit val asyncChannelGroup: AsynchronousChannelGroup = acg
          for {
            foundPeers <- getPeers(infoHash)
            connections = foundPeers
              .evalMap { peer =>
                logger.info(s"Connecting to $peer") *>
                  connectToPeer(peer, selfId, infoHash, logger).allocated
                    .map(_._1.some)
                    .start
                    .flatMap(_.join.timeout(1.seconds))
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
    } yield ()

  def getMetaInfo(torrentPath: Path): IO[(InfoHash, TorrentMetadata)] = {
    for {
      bytes <- IO(BitVector(Files.readAllBytes(torrentPath)))
      bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
      infoDict <- IO.fromEither(TorrentMetadata.RawInfoFormat.read(bc).left.map(new Exception(_)))
      metaInfo <- IO.fromEither(
        TorrentMetadata.TorrentMetadataFormat.read(bc).left.map(new Exception(_))
      )
    } yield (InfoHash(util.sha1Hash(infoDict)), metaInfo)
  }

  def printTorrentMetadata(torrentPath: Path): IO[Unit] = {
    getMetaInfo(torrentPath)
      .flatMap {
        case (infoHash, torrentMetadata) =>
          IO {
            println("Info-hash:")
            pprint.pprintln(infoHash.bytes.toHex(Bases.Alphabets.HexUppercase))
            println()
            pprint.pprintln(torrentMetadata)
          }
      }
  }

  def getPeers(
      infoHash: InfoHash
  )(implicit asynchronousSocketGroup: AsynchronousSocketGroup): IO[Stream[IO, PeerInfo]] =
    Socket[IO](address = new InetSocketAddress(6881)).allocated.flatMap {
      case (socket, _) =>
        DHTClient[IO](NodeId.generate(rnd), socket).flatMap { dhtClient =>
          dhtClient.getPeersAlgo(infoHash)
        }
    }

  def connectToPeer(peerInfo: PeerInfo, selfId: PeerId, infoHash: InfoHash, logger: Logger[IO])(
      implicit asynchronousChannelGroup: AsynchronousChannelGroup
  ): Resource[IO, Connection[IO]] = {
    TCPSocket.client[IO](to = peerInfo.address).flatMap { socket =>
      val acquire =
        logger.debug(s"Opened socket to ${peerInfo.address}") *>
          logger.debug(s"Initiate peer connection to ${peerInfo.address}") *>
          Connection.connect(selfId, peerInfo, infoHash, socket, timer)
      Resource.make(acquire)(_ => IO.unit)
    }
  }

  def saveToFile[F[_]: Concurrent](
      targetDirectory: Path,
      downloading: Downloading[F],
      metaInfo: TorrentMetadata,
      logger: Logger[F]
  ): F[Unit] = {
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

  def findPeers(infoHash: InfoHash): IO[Unit] =
    for {
      logger <- makeLogger
      _ <- resources.use {
        case (asg, _) =>
          getPeers(infoHash)(asg).flatMap { stream =>
            stream
              .evalTap(peerInfo => logger.info(s"Found peer $peerInfo"))
              .compile
              .drain
          }
      }
    } yield ()

  def getTorrent(targetFile: Path, infoHash: InfoHash): IO[Unit] =
    for {
      logger <- makeLogger
      result <- resources.use {
        case (asg, acg) =>
          getPeers(infoHash)(asg).flatMap { stream =>
            stream
              .evalMap { peer =>
                logger.debug(s"Connecting to $peer") *>
                  connectToPeer(peer, selfId, infoHash, logger)(acg).allocated.start
                    .flatMap(_.join.timeout(1.second))
                    .flatMap {
                      case (connection, release) =>
                        logger.info(s"Connected to $peer") >>
                          (
                            if (connection.extensionProtocol)
                              logger.info(s"Connected to $peer which supports extension protocol") *>
                                connection.metadataExtension
                                  .flatMap { extension =>
                                    extension.get(0) *>
                                      connection.events
                                        .collect {
                                          case Event.DownloadedMetadata(bytes) => bytes
                                        }
                                        .head
                                        .compile
                                        .last
                                  }
                            else
                              logger
                                .info(
                                  s"Connected to $peer which does not support extension protocol"
                                )
                                .as(none)
                          ) <*
                          release
                    }
                    .attempt
              }
              .collect {
                case Right(Some(metadata)) => metadata
              }
              .head
              .compile
              .last
          }
      }
      _ <- result match {
        case Some(metadata) =>
          IO.delay {
            val bytes = BencodeCodec.instance
              .encode(
                Bencode.BDictionary(
                  ("info", BencodeCodec.instance.decodeValue(metadata.toBitVector).require),
                  ("creationDate", Bencode.BInteger(System.currentTimeMillis))
                )
              )
              .require
            Files.write(targetFile, bytes.toByteArray)
          } *>
            logger.info("Successfully downloaded torrent file")
        case None =>
          logger.info("Could not download torrent file")
      }
    } yield ()

  def connect(infoHash: InfoHash): IO[Unit] = resources.use {
    case (_asg, _acg) =>
      implicit val asg: AsynchronousSocketGroup = _asg
      implicit val acg: AsynchronousChannelGroup = _acg
      for {
        logger <- makeLogger
        peers <- getPeers(infoHash)
        connections <- ConnectionManager.make[IO](peers, connectToPeer(_, selfId, infoHash, logger))
        _ <- connections.evalTap { connection =>
          logger.info(s"Received connection ${connection.info}")
        }.compile.drain
      } yield ()
  }

}
