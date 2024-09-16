import cats.effect.cps.*
import cats.effect.cps.given
import cats.effect.std.Random
import cats.effect.syntax.all.*
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.ResourceIO
import cats.syntax.all.*
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import com.github.torrentdam.bencode
import com.github.torrentdam.bittorrent.dht.*
import com.github.torrentdam.bittorrent.files.Reader
import com.github.torrentdam.bittorrent.files.Writer
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
import cps.syntax.*
import fs2.io.file.Files
import fs2.io.file.Flag
import fs2.io.file.Flags
import fs2.io.file.Path
import fs2.io.file.WriteCursor
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
            async[ResourceIO] {
              given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await

              val selfPeerId = Resource.eval(PeerId.generate[IO]).await
              val infoHash = Resource.eval(infoHashFromString(infoHashOption)).await
              val node = Node().await

              val swarm = Swarm(
                node.discovery.discover(infoHash),
                Connection.connect(selfPeerId, _, infoHash)
              ).await
              val metadata = DownloadMetadata(swarm).toResource.await
              val torrentFile = TorrentFile(metadata, None)
              Files[IO]
                .writeAll(Path(targetFilePath), Flags.Write)(
                  Stream.chunk(Chunk.byteVector(TorrentFile.toBytes(torrentFile)))
                )
                .compile
                .drain
                .as(ExitCode.Success)
            }.useEval
          }
        }
    }

  def downloadCommand =
    Opts.subcommand("download", "download torrent data") {
      val options = (
        Opts.option[String]("info-hash", "Info-hash").orNone,
        Opts.option[String]("torrent", "Torrent file").orNone,
        Opts.option[String]("peer", "Peer address").orNone,
        Opts.option[String]("dht-node", "DHT node address").orNone
      ).tupled
      options.map { case (infoHashOption, torrentFileOption, peerAddressOption, dhtNodeAddressOption) =>
        withLogger {
          async[ResourceIO] {
            val torrentFile: Option[TorrentFile] = torrentFileOption
              .traverse[IO, TorrentFile](torrentFileOption =>
                async[IO] {
                  val torrentFileBytes = Files[IO]
                    .readAll(Path(torrentFileOption))
                    .compile
                    .to(ByteVector)
                    .await
                  TorrentFile
                    .fromBytes(torrentFileBytes)
                    .liftTo[IO]
                    .await
                }
              )
              .toResource
              .await
            val infoHash: InfoHash =
              torrentFile match
                case Some(torrentFile) =>
                  torrentFile.infoHash
                case None =>
                  infoHashOption match
                    case Some(infoHashOption) =>
                      infoHashFromString(infoHashOption).toResource.await
                    case None =>
                      throw new Exception("Missing info-hash")

            given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await
            val selfPeerId = Resource.eval(PeerId.generate[IO]).await
            val peerAddress = peerAddressOption.flatMap(SocketAddress.fromStringIp)
            val peers: Stream[IO, PeerInfo] =
              peerAddress match
                case Some(peerAddress) =>
                  Stream.emit(PeerInfo(peerAddress)).covary[IO]
                case None =>
                  val bootstrapNodeAddress = dhtNodeAddressOption.flatMap(SocketAddress.fromString)
                  val node = Node(none, bootstrapNodeAddress).await
                  node.discovery.discover(infoHash)
            val swarm = Swarm(peers, peerInfo => Connection.connect(selfPeerId, peerInfo, infoHash)).await
            val metadata =
              torrentFile match
                case Some(torrentFile) =>
                  torrentFile.info
                case None =>
                  Resource.eval(DownloadMetadata(swarm)).await
            val torrent = Torrent.make(metadata, swarm).await
            val total = (metadata.parsed.pieces.length.toDouble / 20).ceil.toLong
            val counter = Resource.eval(IO.ref(0)).await
            val writer = Writer.fromTorrent(metadata.parsed)
            val createDirectories = metadata.parsed.files
              .filter(_.path.length > 1)
              .map(_.path.init)
              .distinct
              .traverse { path =>
                val dir = path.foldLeft(Path("."))(_ / _)
                Files[IO].createDirectories(dir)
              }
            Resource.eval(createDirectories).await
            val openFiles: Map[TorrentMetadata.File, WriteCursor[IO]] =
              metadata.parsed.files
                .traverse { file =>
                  val path = file.path.foldLeft(Path("."))(_ / _)
                  val flags = Flags(Flag.Create, Flag.Write)
                  val cursor = Files[IO].writeCursor(path, flags)
                  cursor.tupleLeft(file)
                }
                .await
                .toMap
            Stream
              .range(0L, total)
              .parEvalMap(10)(index =>
                async[IO] {
                  val piece = !torrent.downloadPiece(index)
                  val count = !counter.updateAndGet(_ + 1)
                  val percent = ((count.toDouble / total) * 100).toInt
                  !Logger[IO].info(s"Downloaded piece $count/$total ($percent%)")
                  Chunk.iterable(writer.write(index, piece))
                }
              )
              .unchunks
              .evalMap(write => openFiles(write.file).seek(write.offset).write(Chunk.byteVector(write.bytes)))
              .compile
              .drain
              .as(ExitCode.Success)
          }.useEval
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
          async[IO] {
            try
              val bytes = Files[IO].readAll(Path(torrentFileName)).compile.to(Array).map(ByteVector(_)).await
              val torrentFile = IO.fromEither(TorrentFile.fromBytes(bytes)).await
              val infoHash = InfoHash(CrossPlatform.sha1(bencode.encode(torrentFile.info.raw).bytes))
              Logger[IO].info(s"Info-hash: $infoHash").await

              val reader = Reader.fromTorrent(torrentFile.info.parsed)

              def readPiece(index: Long): IO[ByteVector] =
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

              val readByteCount = IO.ref(0L).await

              Stream
                .unfold(torrentFile.info.parsed.pieces)(bytes =>
                  if bytes.isEmpty then None
                  else
                    val (checksum, rest) = bytes.splitAt(20)
                    Some((checksum, rest))
                )
                .zipWithIndex
                .evalMap { (checksum, index) =>
                  readPiece(index).map((checksum, index, _))
                }
                .evalTap { (checksum, index, bytes) =>
                  if CrossPlatform.sha1(bytes) == checksum then readByteCount.update(_ + bytes.length)
                  else Logger[IO].error(s"Piece $index failed") >> IO.raiseError(new Exception)
                }
                .compile
                .drain
                .await
              val totalBytes = readByteCount.get.await
              Logger[IO].info(s"Read $totalBytes bytes").await
              Logger[IO].info("All pieces verified").await
              ExitCode.Success
            catch
              case _ =>
                ExitCode.Error
          }
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
          async[ResourceIO] {
            val port = Port.fromInt(portParam).liftTo[ResourceIO](new Exception("Invalid port")).await
            given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await
            Node(Some(port)).await
          }.useForever
        }
      }
    }

  def getPeers =
    Opts.subcommand("get-peers", "send single get_peers query") {
      (
        Opts.option[String]("host", "DHT node address"),
        Opts.option[String]("info-hash", "Info-hash"),
      ).tupled.map { (nodeAddressParam, infoHashParam) =>
        withLogger {
          async[ResourceIO] {
            val nodeAddress = SocketAddress.fromString(nodeAddressParam).liftTo[ResourceIO](new Exception("Invalid address")).await
            val nodeIpAddress = nodeAddress.resolve[IO].toResource.await
            given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await
            val selfId = Resource.eval(NodeId.generate[IO]).await
            val infoHash = infoHashFromString(infoHashParam).toResource.await
            val messageSocket = MessageSocket(none).await
            val client = Client(selfId, messageSocket, QueryHandler.noop).await
            async[IO]:
              val response = client.getPeers(nodeIpAddress, infoHash).await
              IO.println(response).await
              ExitCode.Success
          }.useEval
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

  def withLogger[A](body: Logger[IO] ?=> IO[A]): IO[A] =
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = ColorPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO]).flatMap(body(using _))
}
