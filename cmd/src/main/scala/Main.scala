import cats.effect.std.Random
import cats.effect.syntax.all.*
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.ResourceIO
import cats.syntax.all.*
import com.comcast.ip4s.SocketAddress
import com.github.torrentdam.bittorrent.dht.*
import com.github.torrentdam.bittorrent.wire.Connection
import com.github.torrentdam.bittorrent.wire.Download
import com.github.torrentdam.bittorrent.wire.DownloadMetadata
import com.github.torrentdam.bittorrent.wire.RequestDispatcher
import com.github.torrentdam.bittorrent.wire.Swarm
import com.github.torrentdam.bittorrent.wire.Torrent
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerId
import com.github.torrentdam.bittorrent.PeerInfo
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.Opts
import cats.effect.cps.{*, given}
import com.github.torrentdam.bittorrent.files.Writer
import cps.syntax.*
import fs2.io.file.{Flag, Flags, Path}
import fs2.Chunk
import fs2.Stream

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import org.legogroup.woof.*
import org.legogroup.woof.given

import scala.concurrent.duration.DurationInt

object Main
    extends CommandIOApp(
      name = "tdm",
      header = "TorrentDam"
    ) {

  def main: Opts[IO[ExitCode]] =
    val discoverCommand =
      Opts.subcommand("dht", "discover peers") {
        Opts.option[String]("info-hash", "Info-hash").map { infoHash0 =>
          withLogger {
            async[ResourceIO] {
              given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await
              val selfId = Resource.eval(NodeId.generate[IO]).await
              val infoHash = Resource.eval(
                InfoHash.fromString
                  .unapply(infoHash0)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              ).await
              val table = Resource.eval(RoutingTable[IO](selfId)).await
              val node = Node(selfId, QueryHandler(selfId, table)).await
              Resource.eval(RoutingTableBootstrap(table, node.client)).await
              val discovery = PeerDiscovery.make(table, node.client).await
              discovery
                .discover(infoHash)
                .evalTap { peerInfo =>
                  Logger[IO].trace(s"Discovered peer ${peerInfo.address}")
                }
                .compile
                .drain
                .as(ExitCode.Success)
            }.useEval
          }
        }
      }

    val metadataCommand =
      Opts.subcommand("metadata", "download metadata") {
        Opts.option[String]("info-hash", "Info-hash").map { infoHash0 =>
          withLogger {
            async[ResourceIO] {
              given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await

              val selfId = Resource.eval(NodeId.generate[IO]).await
              val selfPeerId = Resource.eval(PeerId.generate[IO]).await
              val infoHash = Resource.eval(
                InfoHash.fromString
                  .unapply(infoHash0)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              ).await
              val table = Resource.eval(RoutingTable[IO](selfId)).await
              val node = Node(selfId, QueryHandler(selfId, table)).await
              Resource.eval(RoutingTableBootstrap[IO](table, node.client)).await
              val discovery = PeerDiscovery.make(table, node.client).await

              val swarm = Swarm(
                discovery.discover(infoHash),
                Connection.connect(selfPeerId, _, infoHash)
              ).await
              DownloadMetadata(swarm)
                .flatMap(metadata => Logger[IO].info(s"Downloaded metadata $metadata"))
                .as(ExitCode.Success)
            }.useEval
          }
        }
      }

    val downloadCommand =
      Opts.subcommand("download", "download torrent") {
        val options: Opts[(String, Option[String])] =
          (Opts.option[String]("info-hash", "Info-hash"), Opts.option[String]("peer", "Peer address").orNone).tupled
        options.map { case (infoHash0, peerAddress0) =>
          withLogger {
            async[ResourceIO] {
              given Random[IO] = Resource.eval(Random.scalaUtilRandom[IO]).await
              val selfId = Resource.eval(NodeId.generate[IO]).await
              val selfPeerId = Resource.eval(PeerId.generate[IO]).await
              val infoHash = Resource.eval(
                InfoHash.fromString
                  .unapply(infoHash0)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              ).await
              val peerAddress = peerAddress0.flatMap(SocketAddress.fromStringIp)
              val peers: Stream[IO, PeerInfo] =
                peerAddress match
                  case Some(peerAddress) =>
                    Stream.emit(PeerInfo(peerAddress)).covary[IO]
                  case None =>
                    val table = Resource.eval(RoutingTable[IO](selfId)).await
                    val node = Node(selfId, QueryHandler(selfId, table)).await
                    Resource.eval(RoutingTableBootstrap(table, node.client)).await
                    val discovery = PeerDiscovery.make(table, node.client).await
                    discovery.discover(infoHash)
              val swarm = Swarm(peers, peerInfo => Connection.connect(selfPeerId, peerInfo, infoHash)).await
              val metadata = Resource.eval(DownloadMetadata(swarm)).await
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
                  fs2.io.file.Files[IO].createDirectories(dir)
                }
              Resource.eval(createDirectories).await
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
                .evalMap(write =>
                  val path = write.file.path.foldLeft(Path("."))(_ / _)
                  fs2.io.file.Files[IO]
                    .writeCursor(path, Flags(Flag.Create, Flag.Write))
                    .use(cursor =>
                      cursor.seek(write.offset).write(Chunk.byteVector(write.bytes))
                    )
                )
                .compile
                .drain
                .as(ExitCode.Success)
            }.useEval
          }
        }
      }

    discoverCommand <+> metadataCommand <+> downloadCommand
  end main

  def withLogger[A](body: Logger[IO] ?=> IO[A]): IO[A] =
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = ColorPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO]).flatMap(body(using _))
}
