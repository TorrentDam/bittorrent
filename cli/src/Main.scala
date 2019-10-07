package com.github.lavrov.bittorrent

import java.nio.file.{Files, Path, Paths}

import cats.data.Validated
import cats.effect._
import cats.syntax.all._
import com.github.lavrov.bencode.{decode, encode, Bencode, BencodeCodec}
import com.github.lavrov.bittorrent.dht.{NodeId, PeerDiscovery, Client => DhtClient}
import com.monovore.decline.{Command, Opts}
import fs2.Stream
import fs2.io.tcp.{SocketGroup => TcpSocketGroup}
import fs2.io.udp.{SocketGroup => UdpScoketGroup}
import scodec.bits.{Bases, BitVector, ByteVector}

import scala.util.Random
import logstage.LogIO
import izumi.logstage.api.IzLogger

object Main extends IOApp {

  val rnd = new Random
  val selfId = PeerId.generate(rnd)
  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger())

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
      .mapValidated(
        string =>
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
      name = "get-torrent",
      help = "Download torrent metadata"
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
    Blocker[IO].flatMap(UdpScoketGroup(_))

  def asynchronousChannelGroupResource: Resource[IO, TcpSocketGroup] =
    Blocker[IO].flatMap(TcpSocketGroup(_))

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
              infoHash =>
                logger.info("Downloading torrent file using info-hash") *>
                  logger.info(s"Info-hash ${infoHash.bytes.toHex}") *>
                  getTorrent(infoHash).map { bytes =>
                    val bc =
                      com.github.lavrov.bencode
                        .decode(bytes.bits)
                        .getOrElse(
                          sys.error("Failed to read metadata") // todo
                        )
                    val metaInfo =
                      TorrentMetadata.InfoFormat.read
                        .run(bc)
                        .getOrElse(sys.error("Filed to read Info"))
                    (infoHash, metaInfo)
                  },
              torrentPath =>
                readTorrentFile(torrentPath).map {
                  case (infoHash, metadata) => (infoHash, metadata.info)
                }
            )
            (infoHash, metaInfo) = result
            connections <- ConnectionManager.make(
              discoverPeers(infoHash),
              peerInfo => Connection.connect[IO](selfId, peerInfo, infoHash),
              50
            )
            _ <- logger.info(s"Start downloading")
            downloading <- DownloadTorrent.start[IO](metaInfo, connections, logger)
            _ <- saveToFile(targetDirectory, downloading, metaInfo, logger)
            _ <- downloading.fiber.cancel
          } yield ()
      }
    } yield ()

  def readTorrentFile(torrentPath: Path): IO[(InfoHash, TorrentMetadata)] = {
    for {
      bytes <- IO(BitVector(Files.readAllBytes(torrentPath)))
      bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
      infoDict <- IO.fromEither(
        TorrentMetadata.RawInfoFormat.read(bc).left.map(new Exception(_))
      )
      torrentMetadata <- IO.fromEither(
        TorrentMetadata.TorrentMetadataFormat
          .read(bc)
          .left
          .map(new Exception(_))
      )
    } yield (InfoHash(encode(infoDict).digest("SHA-1").bytes), torrentMetadata)
  }

  def readTorrent(torrentPath: Path): IO[Unit] = {
    readTorrentFile(torrentPath)
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
    for {
      metadata <- getTorrent(infoHash)
      _ <- IO.delay {
        val bytes = BencodeCodec.instance
          .encode(
            Bencode.BDictionary(
              (
                "info",
                BencodeCodec.instance.decodeValue(metadata.toBitVector).require
              ),
              ("creationDate", Bencode.BInteger(System.currentTimeMillis))
            )
          )
          .require
        Files.write(targetFile, bytes.toByteArray)
      } *>
        logger.info("Successfully downloaded torrent file")
    } yield ()

  def connect(infoHash: InfoHash): IO[Unit] = resources.use {
    case (usg: UdpScoketGroup, tsg: TcpSocketGroup) =>
      implicit val usg0: UdpScoketGroup = usg
      implicit val tsg0: TcpSocketGroup = tsg
      val peers = discoverPeers(infoHash)
      for {
        connections <- ConnectionManager
          .make[IO](peers, Connection.connect(selfId, _, infoHash), 10)
        _ <- connections
          .evalTap { connection =>
            logger.info(s"Received connection ${connection.info}")
          }
          .compile
          .drain
      } yield ()
  }

  private def discoverPeers(
      infoHash: InfoHash
  )(implicit usg: UdpScoketGroup): Stream[IO, PeerInfo] = {
    val selfId = NodeId.generate(rnd)
    for {
      client <- Stream.resource(DhtClient.start[IO](selfId, port = 6881))
      peers <- Stream.eval(PeerDiscovery.start(infoHash, client))
      peer <- peers
    } yield peer
  }

  private def getTorrent(infoHash: InfoHash): IO[ByteVector] = {
    for {
      metadata <- resources.use {
        case (usg: UdpScoketGroup, tsg: TcpSocketGroup) =>
          implicit val usg0: UdpScoketGroup = usg
          implicit val tsg0: TcpSocketGroup = tsg
          val connections =
            discoverPeers(infoHash).map(
              peer => (peer, Connection0.connect[IO](selfId, peer, infoHash))
            )
          DownloadTorrentMetadata.start[IO](infoHash, connections)
      }
    } yield metadata
  }

  private def saveToFile[F[_]: Concurrent](
      targetDirectory: Path,
      downloading: DownloadTorrent[F],
      metaInfo: TorrentMetadata.Info,
      logger: LogIO[F]
  ): F[Unit] = {
    val sink = FileSink(metaInfo, targetDirectory)
    for {
      _ <- downloading.completePieces
        .evalTap(p => logger.info(s"Complete piece: ${p.index}"))
        .map(p => FileSink.Piece(p.begin, p.bytes))
        .through(sink)
        .compile
        .drain
        .onError {
          case e =>
            logger.error(s"Download failed $e")
        }
      _ <- logger.info(s"Download complete")
    } yield ()
  }

}
