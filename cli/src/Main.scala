package com.github.lavrov.bittorrent

import java.nio.file.{Files, Path, Paths}

import cats.data.Validated
import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import com.github.lavrov.bencode.{decode, encode}
import com.github.lavrov.bittorrent.dht.{Node, NodeId, PeerDiscovery}
import com.github.lavrov.bittorrent.wire.{Connection, DownloadMetadata, Swarm}
import com.monovore.decline.{Command, Opts}
import fs2.Stream
import fs2.io.tcp.{SocketGroup => TcpSocketGroup}
import fs2.io.udp.{SocketGroup => UdpScoketGroup}
import izumi.logstage.api.IzLogger
import logstage.LogIO
import scodec.bits.{Bases, BitVector, ByteVector}

import scala.concurrent.duration._
import scala.util.Random

object Main extends IOApp {

  val rnd = new Random
  val selfId = PeerId.generate(rnd)
  implicit val logger: LogIO[IO] =
    LogIO.fromLogger(IzLogger(threshold = IzLogger.Level.Info))

  def run(args: List[String]): IO[ExitCode] = {
    topLevelCommand.parse(args) match {
      case Right(thunk) => thunk as ExitCode.Success
      case Left(help) =>
        IO(println(help)) as {
          if (help.errors.isEmpty) ExitCode.Success else ExitCode.Error
        }
    }
  }

  val topLevelCommand = {
    val torrentFileOpt =
      Opts
        .option[String]("torrent", help = "Path to torrent file")
        .map(Paths.get(_))

    val infoHashOpt = Opts
      .option[String]("info-hash", "Info-hash of the torrent file")
      .mapValidated(string =>
        Validated
          .fromEither(ByteVector.fromHexDescriptive(string))
          .toValidatedNel
      )
      .validate("Must be 20 bytes long")(_.size == 20)
      .map(InfoHash)

    val downloadCommand =
      Opts.subcommand(name = "download", help = "Download files") {
        val targetDirectoryOpt =
          Opts
            .option[String](
              "target-directory",
              help = "Path to target directory"
            )
            .map(Paths.get(_))
        (torrentFileOpt.orNone, infoHashOpt.orNone, targetDirectoryOpt).tupled
          .mapValidated {
            case ((torrentPath, infoHash, targetPath)) =>
              if (torrentPath.isEmpty && infoHash.isEmpty)
                "Provide either torrent-file or info-hash".invalidNel
              else
                (torrentPath.toRight(infoHash.get), targetPath).validNel
          }
          .map {
            case ((metadataSource, targetPath)) =>
              download(metadataSource, targetPath)
          }
      }

    val getTorrentCommand = Opts.subcommand(
      name = "download-metadata",
      help = "Download torrent metadata also known as torrent file"
    ) {
      (torrentFileOpt, infoHashOpt).mapN(getTorrentAndSave)
    }

    val findPeersCommand =
      Opts.subcommand(name = "find-peers", help = "Find peers by info-hash") {
        infoHashOpt.map(findPeers)
      }

    val readTorrentCommand = Opts.subcommand(
      name = "read-torrent",
      help = "Read torrent file from file and print it out"
    ) {
      torrentFileOpt.map(readTorrent)
    }

    val connectCommand =
      Opts.subcommand(name = "connect", help = "Connect at max to 10 peers") {
        infoHashOpt.map(connect)
      }

    Command(name = "bittorrent", header = "Bittorrent client")(
      downloadCommand <+> getTorrentCommand
      <+> findPeersCommand <+> readTorrentCommand <+> connectCommand
    )
  }

  def asynchronousSocketGroupResource: Resource[IO, UdpScoketGroup] =
    Blocker[IO].flatMap(UdpScoketGroup[IO](_))

  def asynchronousChannelGroupResource: Resource[IO, TcpSocketGroup] =
    Blocker[IO].flatMap(TcpSocketGroup[IO](_))

  def resources: Resource[IO, (UdpScoketGroup, TcpSocketGroup)] =
    for {
      a <- asynchronousSocketGroupResource
      b <- asynchronousChannelGroupResource
    } yield (a, b)

  def download(metadataSource: Either[InfoHash, Path], targetDirectory: Path): IO[Unit] =
    for {
      _ <- resources.use {
        case (usg: UdpScoketGroup, tsg: TcpSocketGroup) =>
          implicit val usg0: UdpScoketGroup = usg
          implicit val tsg0: TcpSocketGroup = tsg
          for {
            result <- metadataSource.fold(
              infoHash => IO.pure((infoHash, none)),
              torrentPath =>
                readTorrentFile(torrentPath).map {
                  case (infoHash, metadata) => (infoHash, metadata.some)
                }
            )
            (infoHash, metaInfoOpt) = result
            _ <- makeSwarm(infoHash).use { swarm =>
              for {
                metaInfo <- metaInfoOpt.map(IO.pure).getOrElse {
                  logger.info("Downloading torrent file using info-hash") >>
                  logger.info(s"Info-hash ${infoHash.bytes.toHex}") >>
                  DownloadMetadata(swarm.connected.stream)
                }
//                _ <- FileStorage[IO](metaInfo.parsed, targetDirectory).use { fileStorage =>
//                  for {
//                    _ <- logger.info(s"Start downloading")
//                    control <- Torrent[IO](
//                      metaInfo,
//                      swarm,
//                      fileStorage
//                    )
//                    _ <- torrent.downloadAll
//                      .evalTap { piece =>
//                        fileStorage.save(FileStorage.Piece(piece.begin, piece.bytes))
//                      }
//                      .compile
//                      .drain
//                  } yield ()
//                }
              } yield ()
            }
          } yield ()
      }
    } yield ()

  def makeSwarm(
    infoHash: InfoHash
  )(implicit ev0: TcpSocketGroup, ev1: UdpScoketGroup): Resource[IO, Swarm[IO]] = {
    val connect = (p: PeerInfo) => Connection.connect[IO](selfId, p, infoHash)
    Swarm[IO](
      discoverPeers(infoHash),
      connect
    )
  }

  def readTorrentFile(torrentPath: Path): IO[(InfoHash, MetaInfo)] = {
    for {
      bytes <- IO(BitVector(Files.readAllBytes(torrentPath)))
      bc <- IO.fromEither(decode(bytes))
      torrentFile <- IO.fromEither(
        TorrentFile.TorrentFileFormat.read(bc)
      )
    } yield (InfoHash(encode(torrentFile.info.raw).digest("SHA-1").bytes), torrentFile.info)
  }

  def readTorrent(torrentPath: Path): IO[Unit] = {
    readTorrentFile(torrentPath)
      .flatMap {
        case (infoHash, torrentMetadata) =>
          IO {
            println("Info-hash:")
            pprint.pprintln(infoHash.bytes.toHex(Bases.Alphabets.HexUppercase))
            println()
            pprint.pprintln(torrentMetadata, height = Int.MaxValue)
          }
      }
  }

  def findPeers(infoHash: InfoHash): IO[Unit] =
    for {
      _ <- asynchronousSocketGroupResource.use { implicit asg =>
        discoverPeers(infoHash)
          .evalTap(peerInfo => logger.info(s"Found peer $peerInfo"))
          .compile
          .drain
      }
    } yield ()

  def getTorrentAndSave(targetFile: Path, infoHash: InfoHash): IO[Unit] =
    resources
      .flatMap {
        case (usg: UdpScoketGroup, tsg: TcpSocketGroup) =>
          implicit val usg0: UdpScoketGroup = usg
          implicit val tsg0: TcpSocketGroup = tsg
          makeSwarm(infoHash)
      }
      .use { swarm =>
        for {
          metaInfo <- DownloadMetadata(swarm.connected.stream)
          torrentFile = TorrentFile(metaInfo, None)
          bcode <- IO.fromEither(
            TorrentFile.TorrentFileFormat.write(torrentFile)
          )
          bytes <- IO.pure(encode(bcode))
          _ <- IO.delay {
            Files.write(targetFile, bytes.toByteArray)
          }
          _ <- logger.info("Successfully downloaded torrent file")
        } yield ()
      }

  def connect(infoHash: InfoHash): IO[Unit] =
    resources.use {
      case (usg: UdpScoketGroup, tsg: TcpSocketGroup) =>
        implicit val usg0: UdpScoketGroup = usg
        implicit val tsg0: TcpSocketGroup = tsg
        val peers = discoverPeers(infoHash)
        val swarmR =
          Swarm[IO](
            peers,
            Connection.connect(selfId, _, infoHash),
            10
          )
        for {
          _ <- swarmR.use { swarm =>
            swarm.connected.count.get
              .flatMap { n =>
                logger.info(s"$n open connections")
              }
              .flatMap { _ =>
                timer.sleep(5.seconds)
              }
              .foreverM
          }
        } yield ()
    }

  private def discoverPeers(
    infoHash: InfoHash
  )(implicit usg: UdpScoketGroup): Stream[IO, PeerInfo] = {
    val selfId = NodeId.generate(rnd)
    for {
      client <- Stream.resource(Node[IO](selfId, port = 6881))
      discovery <- Stream.resource(PeerDiscovery.make[IO](client))
      peer <- discovery.discover(infoHash)
    } yield peer
  }

}
