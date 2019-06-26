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
import com.github.lavrov.bittorrent.dht.PeerDiscovery
import com.github.lavrov.bittorrent.protocol.DownloadTorrentMetadata
import com.github.lavrov.bittorrent.protocol.Connection0

object Main extends IOApp {

  val rnd = new Random

  val torrentFileOpt =
    Opts.option[String]("torrent", help = "Path to torrent file").map(Paths.get(_))

  val infoHashOpt = Opts
    .option[String]("info-hash", "Info-hash of the torrent file")
    .mapValidated(
      string => Validated.fromEither(ByteVector.fromHexDescriptive(string)).toValidatedNel
    )
    .validate("Must be 20 bytes long")(_.size == 20)
    .map(InfoHash)

  val downloadCommand = Opts.subcommand(
    name = "download",
    help = "Download files"
  ) {
    val targetDirectoryOpt =
      Opts.option[String]("target-directory", help = "Path to target directory").map(Paths.get(_))
    (torrentFileOpt.orNone, infoHashOpt.orNone, targetDirectoryOpt).tupled
      .mapValidated {
        case ((torrentPath, infoHash, targetPath)) =>
          if (torrentPath.isEmpty && infoHash.isEmpty)
            "Provide either torrent-file or info-hash".invalidNel
          else
            (torrentPath.toRight(infoHash.get), targetPath).validNel
      }
      .map {
        case ((metadataSource, targetPath)) => download(metadataSource, targetPath)
      }
  }

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
    (torrentFileOpt, infoHashOpt).mapN(getTorrentAndSave)
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
  )(
    downloadCommand <+> findPeersCommand <+> getTorrentCommand <+> readTorrentCommand <+> connectCommand
  )

  val selfId = PeerId.generate(rnd)

  val asynchronousSocketGroupResource =
    Resource.make(IO(AsynchronousSocketGroup()))(
      r => IO(r.close())
    )

  val asynchronousChannelGroupResource =
    Resource.make(
      IO(
        AsynchronousChannelProvider
          .provider()
          .openAsynchronousChannelGroup(2, Executors.defaultThreadFactory())
      )
    )(g => IO(g.shutdown()))

  val resources = for {
    a <- asynchronousSocketGroupResource
    b <- asynchronousChannelGroupResource
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

  def download(metadataSource: Either[InfoHash, Path], targetDirectory: Path): IO[Unit] =
    for {
      logger <- makeLogger
      _ <- resources.use {
        case (asg, acg) =>
          implicit val asyncSocketGroup: AsynchronousSocketGroup = asg
          implicit val asyncChannelGroup: AsynchronousChannelGroup = acg
          for {
            metaInfoResult <- metadataSource.fold(
              infoHash =>
                logger.info("Downloading torrent file using info-hash") *>
                  logger.info(s"Info-hash ${infoHash.bytes.toHex}") *>
                  getTorrent(infoHash).map { bytes =>
                    val bc = com.github.lavrov.bencode.decode(bytes.bits).right.get
                    val metaInfo = TorrentMetadata.InfoFormat.read.run(bc).right.get
                    (infoHash, metaInfo)
                  },
              torrentPath => getMetaInfo(torrentPath)
            )
            (infoHash, metaInfo) = metaInfoResult
            connections <- ConnectionManager.make(
              getPeers(infoHash),
              connectToPeer(_, selfId, infoHash, logger)
            )
            _ <- logger.info(s"Start downloading")
            downloading <- Downloading.start[IO](metaInfo, connections)
            _ <- saveToFile(targetDirectory, downloading, metaInfo, logger)
          } yield ()
      }
    } yield ()

  def getMetaInfo(torrentPath: Path): IO[(InfoHash, TorrentMetadata.Info)] = {
    for {
      bytes <- IO(BitVector(Files.readAllBytes(torrentPath)))
      bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
      infoDict <- IO.fromEither(TorrentMetadata.RawInfoFormat.read(bc).left.map(new Exception(_)))
      torrentMetadata <- IO.fromEither(
        TorrentMetadata.TorrentMetadataFormat.read(bc).left.map(new Exception(_))
      )
    } yield (InfoHash(util.sha1Hash(infoDict)), torrentMetadata.info)
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
  )(implicit asynchronousSocketGroup: AsynchronousSocketGroup): Stream[IO, PeerInfo] =
    Stream
      .resource(Socket[IO](address = new InetSocketAddress(6881)))
      .flatMap { socket =>
        for {
          dhtClient <- Stream.eval(DHTClient[IO](NodeId.generate(rnd), socket))
          peers <- Stream.eval(PeerDiscovery.start(infoHash, dhtClient))
          peer <- peers
        } yield peer
      }

  def connectToPeer(peerInfo: PeerInfo, selfId: PeerId, infoHash: InfoHash, logger: Logger[IO])(
      implicit asynchronousChannelGroup: AsynchronousChannelGroup
  ): Resource[IO, Connection[IO]] = {
    Connection.connect[IO](selfId, peerInfo, infoHash)
  }

  def saveToFile[F[_]: Concurrent](
      targetDirectory: Path,
      downloading: Downloading[F],
      metaInfo: TorrentMetadata.Info,
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
          getPeers(infoHash)(asg)
            .evalTap(peerInfo => logger.info(s"Found peer $peerInfo"))
            .compile
            .drain
      }
    } yield ()

  def getTorrent(
      infoHash: InfoHash
  )(implicit acg: AsynchronousChannelGroup, asg: AsynchronousSocketGroup): IO[ByteVector] = {
    for {
      logger <- makeLogger
      metadata <- resources.use {
        case (asg, acg) =>
          implicit val _acg: AsynchronousChannelGroup = acg
          val connections =
            getPeers(infoHash)(asg)
              .map(peer => (peer, Connection0.connect[IO](selfId, peer, infoHash)))
          DownloadTorrentMetadata.start[IO](infoHash, connections)
      }
    } yield metadata
  }

  def getTorrentAndSave(targetFile: Path, infoHash: InfoHash): IO[Unit] =
    for {
      logger <- makeLogger
      metadata <- resources.use {
        case (asg, acg) =>
          implicit val _acg: AsynchronousChannelGroup = acg
          implicit val _asg: AsynchronousSocketGroup = asg
          getTorrent(infoHash)
      }
      _ <- IO.delay {
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
    } yield ()

  def connect(infoHash: InfoHash): IO[Unit] = resources.use {
    case (_asg, _acg) =>
      implicit val asg: AsynchronousSocketGroup = _asg
      implicit val acg: AsynchronousChannelGroup = _acg
      for {
        logger <- makeLogger
        peers = getPeers(infoHash)
        connections <- ConnectionManager.make[IO](peers, connectToPeer(_, selfId, infoHash, logger))
        _ <- connections
          .evalTap { connection =>
            logger.info(s"Received connection ${connection.info}")
          }
          .compile
          .drain
      } yield ()
  }

}
