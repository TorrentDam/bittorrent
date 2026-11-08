import cats.effect.std.Random
import cats.effect.syntax.all.*
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import com.github.torrentdam.bencode
import com.github.torrentdam.bittorrent.dht.*
import com.github.torrentdam.bittorrent.files.Reader
import com.github.torrentdam.bittorrent.files.Writer
import com.github.torrentdam.bittorrent.net.Networks
import com.github.torrentdam.bittorrent.wire.Connection
import com.github.torrentdam.bittorrent.wire.Download
import com.github.torrentdam.bittorrent.wire.DownloadMetadata
import com.github.torrentdam.bittorrent.wire.RequestDispatcher
import com.github.torrentdam.bittorrent.wire.Swarm
import com.github.torrentdam.bittorrent.wire.Torrent
import com.github.torrentdam.bittorrent.CrossPlatform
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerId
import com.github.torrentdam.bittorrent.PeerInfo
import com.github.torrentdam.bittorrent.TorrentFile
import com.github.torrentdam.bittorrent.TorrentMetadata
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.Opts
import fs2.io.file.Files
import fs2.io.file.Flag
import fs2.io.file.Flags
import fs2.io.file.Path
import fs2.io.file.WriteCursor
import fs2.io.net.Network
import fs2.Chunk
import fs2.Stream
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import org.legogroup.woof.*
import org.legogroup.woof.given
import scala.concurrent.duration.DurationInt
import scodec.bits.ByteVector

object Main
    extends CommandIOApp(
      name = "torrentdam",
      header = "TorrentDam"
    ) {

  given Network[IO] = Networks.custom[IO]

  def main: Opts[IO[ExitCode]] =
    torrentCommand <+> dhtCommand

  def torrentCommand: Opts[IO[ExitCode]] =
    Opts.subcommand("torrent", "torrent client")(
      fetchFileCommand <+> downloadCommand <+> verifyCommand
    )

  def fetchFileCommand =
    Opts.subcommand("fetch-file", "download torrent file") {
      (
        Opts.option[String]("info-hash", "Info-hash"),
        Opts.option[String]("save", "Save as a torrent file")
      )
        .mapN { (infoHashOption, targetFilePath) =>
          withLogger {
            Resource
              .eval(Random.scalaUtilRandom[IO])
              .flatMap { random =>
                given Random[IO] = random
                Resource.eval(PeerId.generate).flatMap { selfPeerId =>
                  Resource.eval(infoHashFromString(infoHashOption)).flatMap { infoHash =>
                    Node().flatMap { node =>
                      Swarm(
                        node.discovery.discover(infoHash),
                        Connection.connect(selfPeerId, _, infoHash)
                      ).flatMap { swarm =>
                        Resource.eval(DownloadMetadata(swarm)).flatMap { metadata =>
                          val torrentFile = TorrentFile(metadata, None)
                          Resource.eval(
                            Files[IO]
                              .writeAll(Path(targetFilePath), Flags.Write)(
                                Stream.chunk(Chunk.byteVector(TorrentFile.toBytes(torrentFile)))
                              )
                              .compile
                              .drain
                              .as(ExitCode.Success)
                          )
                        }
                      }
                    }
                  }
                }
              }
              .use(IO.pure)
          }
        }
    }

  def downloadCommand =
    Opts.subcommand("download", "download torrent data") {
      val options = (
        Opts.option[String]("info-hash", "Info-hash").orNone,
        Opts.option[String]("torrent", "Torrent file").orNone,
        Opts.option[String]("peer", "Peer address").orNone,
        Opts.option[String]("dht-node", "DHT node address").orNone,
        Opts.option[String]("events", "Events output file (newline-delimited JSON)").orNone
      ).tupled
      options.map {
        case (infoHashOption, torrentFileOption, peerAddressOption, dhtNodeAddressOption, eventsFileOption) =>
          withLogger {
            Resource
              .eval(Random.scalaUtilRandom[IO])
              .flatMap { random =>
                given Random[IO] = random
                for
                  torrentFile <- Resource.eval(
                    torrentFileOption.traverse[IO, TorrentFile] { path =>
                      Files[IO]
                        .readAll(Path(path))
                        .compile
                        .to(ByteVector)
                        .flatMap(bytes => IO.fromEither(TorrentFile.fromBytes(bytes)))
                    }
                  )
                  infoHash <- Resource.eval(
                    torrentFile match
                      case Some(torrentFile) =>
                        IO.pure(torrentFile.infoHash)
                      case None =>
                        infoHashOption match
                          case Some(infoHashOption) =>
                            infoHashFromString(infoHashOption)
                          case None =>
                            IO.raiseError(new Exception("Missing info-hash"))
                  )
                  selfPeerId <- Resource.eval(PeerId.generate)
                  peers <- {
                    val peerAddress = peerAddressOption.flatMap(SocketAddress.fromStringIp)
                    peerAddress match
                      case Some(peerAddress) =>
                        Resource.pure(Stream.emit(PeerInfo(peerAddress)).covary[IO])
                      case None =>
                        val bootstrapNodeAddress = dhtNodeAddressOption.flatMap(SocketAddress.fromString)
                        Node(none, bootstrapNodeAddress).map(node => node.discovery.discover(infoHash))
                  }
                  swarm <- Swarm(peers, peerInfo => Connection.connect(selfPeerId, peerInfo, infoHash))
                  metadata <- torrentFile match
                    case Some(torrentFile) => Resource.pure(torrentFile.info)
                    case None              => Resource.eval(DownloadMetadata(swarm))
                  torrent <- Torrent.make(metadata, swarm)
                  counter <- Resource.eval(IO.ref(0))
                  _ <- Resource.eval(
                    metadata.parsed.files
                      .filter(_.path.length > 1)
                      .map(_.path.init)
                      .distinct
                      .traverse { path =>
                        val dir = path.foldLeft(Path("."))(_ / _)
                        Files[IO].createDirectories(dir)
                      }
                  )
                  openFiles <- metadata.parsed.files
                    .traverse { file =>
                      val path = file.path.foldLeft(Path("."))(_ / _)
                      val flags = Flags(Flag.Create, Flag.Write)
                      Files[IO].writeCursor(path, flags).tupleLeft(file)
                    }
                    .map(_.toMap)
                  events <- eventsPipe(eventsFileOption, metadata.parsed)
                  exitCode <- Resource.eval {
                    val total = (metadata.parsed.pieces.length.toDouble / 20).ceil.toLong
                    val writer = Writer.fromTorrent(metadata.parsed)
                    Stream
                      .range(0L, total)
                      .parEvalMap(10)(index =>
                        torrent.downloadPiece(index).flatMap { piece =>
                          counter.updateAndGet(_ + 1).flatMap { count =>
                            val percent = ((count.toDouble / total) * 100).toInt
                            Logger[IO].info(s"Downloaded piece $count/$total ($percent%)").as((index, piece))
                          }
                        }
                      )
                      .evalMap { (index, piece) =>
                        Stream
                          .emits(writer.write(index, piece))
                          .evalMap(write =>
                            openFiles(write.file).seek(write.offset).write(Chunk.byteVector(write.bytes))
                          )
                          .compile
                          .drain
                          .as(index)
                      }
                      .through(events)
                      .compile
                      .drain
                      .as(ExitCode.Success)
                  }
                yield exitCode
              }
              .use(IO.pure)
          }
      }
    }

  def verifyCommand =
    Opts.subcommand("verify", "verify torrent data") {
      val options: Opts[(String, String)] =
        (
          Opts.option[String]("torrent", "Torrent file"),
          Opts.option[String]("target", "Torrent data directory")
        ).tupled
      options.map { (torrentFileName, targetDirName) =>
        withLogger {
          def readPiece(reader: Reader, index: Long): IO[ByteVector] =
            val reads = Stream.emits(reader.read(index))
            reads
              .covary[IO]
              .evalMap { read =>
                val path = read.file.path.foldLeft(Path(targetDirName))(_ / _)
                Files[IO]
                  .readRange(path, 1024 * 1024, read.offset, read.endOffset)
                  .chunks
                  .map(_.toByteVector)
                  .compile
                  .fold(ByteVector.empty)(_ ++ _)
              }
              .compile
              .fold(ByteVector.empty)(_ ++ _)

          val program =
            for
              bytes <- Files[IO].readAll(Path(torrentFileName)).compile.to(Array).map(ByteVector(_))
              torrentFile <- IO.fromEither(TorrentFile.fromBytes(bytes))
              _ <- Logger[IO].info(
                s"Info-hash: ${InfoHash(CrossPlatform.sha1(bencode.encode(torrentFile.info.raw).bytes))}"
              )
              reader = Reader.fromTorrent(torrentFile.info.parsed)
              readByteCount <- IO.ref(0L)
              _ <- Stream
                .unfold(torrentFile.info.parsed.pieces)(bytes =>
                  if bytes.isEmpty then None
                  else
                    val (checksum, rest) = bytes.splitAt(20)
                    Some((checksum, rest))
                )
                .zipWithIndex
                .evalMap { (checksum, index) =>
                  readPiece(reader, index).map((checksum, index, _))
                }
                .evalTap { (checksum, index, bytes) =>
                  if CrossPlatform.sha1(bytes) == checksum then readByteCount.update(_ + bytes.length)
                  else Logger[IO].error(s"Piece $index failed") >> IO.raiseError(new Exception)
                }
                .compile
                .drain
              totalBytes <- readByteCount.get
              _ <- Logger[IO].info(s"Read $totalBytes bytes")
              _ <- Logger[IO].info("All pieces verified")
            yield ExitCode.Success

          program.handleErrorWith(e => Logger[IO].error(e.getMessage).as(ExitCode.Error))
        }
      }
    }

  def dhtCommand: Opts[IO[ExitCode]] =
    Opts.subcommand("dht", "DHT client")(
      startCommand <+> getPeers
    )

  def startCommand =
    Opts.subcommand("start", "start DHT node") {
      Opts.option[Int]("port", "UDP port").map { portParam =>
        withLogger {
          Resource
            .eval(Random.scalaUtilRandom[IO])
            .flatMap { random =>
              given Random[IO] = random
              Resource.eval(Port.fromInt(portParam).liftTo[IO](new Exception("Invalid port"))).flatMap { port =>
                Node(Some(port))
              }
            }
            .useForever
        }
      }
    }

  def getPeers =
    Opts.subcommand("get-peers", "send single get_peers query") {
      (
        Opts.option[String]("host", "DHT node address"),
        Opts.option[String]("info-hash", "Info-hash")
      ).tupled.map { (nodeAddressParam, infoHashParam) =>
        withLogger {
          Resource
            .eval(SocketAddress.fromString(nodeAddressParam).liftTo[IO](new Exception("Invalid address")))
            .flatMap { nodeAddress =>
              nodeAddress.resolve[IO].toResource.flatMap { nodeIpAddress =>
                Resource.eval(Random.scalaUtilRandom[IO]).flatMap { random =>
                  given Random[IO] = random
                  Resource.eval(NodeId.random).flatMap { selfId =>
                    Resource.eval(infoHashFromString(infoHashParam)).flatMap { infoHash =>
                      MessageSocket(none).flatMap { messageSocket =>
                        Client(selfId, messageSocket, QueryHandler.noop).flatMap { client =>
                          Resource.eval(client.getPeers(nodeIpAddress, infoHash).as(ExitCode.Success))
                        }
                      }
                    }
                  }
                }
              }
            }
            .use(IO.pure)
        }
      }
    }

  extension (torrentFile: TorrentFile) {
    def infoHash: InfoHash = InfoHash(CrossPlatform.sha1(bencode.encode(torrentFile.info.raw).bytes))
  }

  def infoHashFromString(value: String): IO[InfoHash] =
    InfoHash.fromString
      .unapply(value)
      .liftTo[IO](new Exception("Malformed info-hash"))

  def eventsPipe(
    eventsFileOption: Option[String],
    metadata: TorrentMetadata
  ): Resource[IO, Stream[IO, Long] => Stream[IO, Unit]] =
    eventsFileOption match
      case None => Resource.pure(_.void)
      case Some(path) =>
        val totalPieces = (metadata.pieces.length.toDouble / 20).ceil.toLong
        val totalSize = metadata.files.map(_.length).sum
        val metaEvent = s"""{"type":"TorrentMetadata","payload":{"name":"${escapeJson(
            metadata.name
          )}","pieceLength":${metadata.pieceLength},"totalPieces":$totalPieces,"totalSize":$totalSize}}\n"""
        val metaChunk = Chunk.byteVector(ByteVector.view(metaEvent.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        for
          cursor <- Files[IO].writeCursor(Path(path), Flags(Flag.Create, Flag.Write))
          _ <- Resource.eval(cursor.write(metaChunk))
        yield _.evalMapAccumulate(cursor) { (acc, index) =>
          val event = s"""{"type":"PieceDownloaded","payload":{"index":$index}}\n"""
          val chunk = Chunk.byteVector(ByteVector.view(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          acc.write(chunk).tupleRight(())
        }.void

  def escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  def withLogger[A](body: Logger[IO] ?=> IO[A]): IO[A] =
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = ColorPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO]).flatMap(body(using _))
}
