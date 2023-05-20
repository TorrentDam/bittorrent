import cats.effect.std.Random
import cats.effect.syntax.all.*
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.ResourceIO
import cats.syntax.all.*
import com.comcast.ip4s.SocketAddress
import com.github.lavrov.bittorrent.dht.*
import com.github.lavrov.bittorrent.wire.Connection
import com.github.lavrov.bittorrent.wire.Download
import com.github.lavrov.bittorrent.wire.DownloadMetadata
import com.github.lavrov.bittorrent.wire.RequestDispatcher
import com.github.lavrov.bittorrent.wire.Swarm
import com.github.lavrov.bittorrent.wire.Torrent
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.PeerId
import com.github.lavrov.bittorrent.PeerInfo
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.Opts
import cps.async
import cps.await
import cps.monads.catsEffect.asyncScope
import cps.monads.catsEffect.given
import cps.syntax.*
import fs2.io.file.Flags
import fs2.io.file.Path
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
            asyncScope[IO] {
              given Random[IO] = !Random.scalaUtilRandom[IO]
              val selfId = !NodeId.generate[IO]
              val infoHash = await {
                InfoHash.fromString
                  .unapply(infoHash0)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              }
              val table = !RoutingTable[IO](selfId)
              val node = !Node(selfId, QueryHandler(selfId, table))
              !RoutingTableBootstrap(table, node.client)
              val discovery = !PeerDiscovery.make(table, node.client)
              !discovery
                .discover(infoHash)
                .evalTap { peerInfo =>
                  Logger[IO].trace(s"Discovered peer ${peerInfo.address}")
                }
                .compile
                .drain
                .as(ExitCode.Success)
            }
          }
        }
      }

    val metadataCommand =
      Opts.subcommand("metadata", "download metadata") {
        Opts.option[String]("info-hash", "Info-hash").map { infoHash0 =>
          withLogger {
            asyncScope[IO] {
              given Random[IO] = !Random.scalaUtilRandom[IO]

              val selfId = !NodeId.generate[IO]
              val selfPeerId = !PeerId.generate[IO]
              val infoHash =
                !InfoHash.fromString
                  .unapply(infoHash0)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              val table = !RoutingTable[IO](selfId)
              val node = !Node(selfId, QueryHandler(selfId, table))
              !RoutingTableBootstrap(table, node.client)
              val discovery = !PeerDiscovery.make(table, node.client)

              val swarm = !Swarm(
                discovery.discover(infoHash),
                Connection.connect(selfPeerId, _, infoHash)
              )
              !DownloadMetadata(swarm)
                .flatMap(metadata => Logger[IO].info(s"Downloaded metadata $metadata"))
                .as(ExitCode.Success)
            }
          }
        }
      }

    val downloadCommand =
      Opts.subcommand("download", "download torrent") {
        val options: Opts[(String, Option[String])] =
          (Opts.option[String]("info-hash", "Info-hash"), Opts.option[String]("peer", "Peer address").orNone).tupled
        options.map { case (infoHash0, peerAddress0) =>
          withLogger {
            asyncScope[IO] {
              given Random[IO] = !Random.scalaUtilRandom[IO]
              val selfId = !NodeId.generate[IO]
              val selfPeerId = !PeerId.generate[IO]
              val infoHash =
                !InfoHash.fromString
                  .unapply(infoHash0)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              val peerAddress = peerAddress0.flatMap(SocketAddress.fromStringIp)
              val peers: Stream[IO, PeerInfo] = !async[ResourceIO] {
                peerAddress match
                  case Some(peerAddress) =>
                    Stream.emit(PeerInfo(peerAddress)).covary[IO]
                  case None =>
                    val table = !Resource.eval {
                      RoutingTable[IO](selfId)
                    }
                    val node = !Node(selfId, QueryHandler(selfId, table))
                    !Resource.eval {
                      RoutingTableBootstrap(table, node.client)
                    }
                    val discovery = !PeerDiscovery.make(table, node.client)
                    discovery.discover(infoHash)
              }
              val swarm = !Swarm(peers, peerInfo => Connection.connect(selfPeerId, peerInfo, infoHash))
              val metadata = !DownloadMetadata(swarm)
              val torrent = !Torrent.make(metadata, swarm)
              val total = (metadata.parsed.pieces.length.toDouble / 20).ceil.toLong
              val counter = !IO.ref(0)
              !Stream
                .range(0L, total)
                .parEvalMap(10)(index =>
                  async[IO] {
                    val piece = !torrent.downloadPiece(index)
                    val count = !counter.updateAndGet(_ + 1)
                    val percent = ((count.toDouble / total) * 100).toInt
                    !Logger[IO].info(s"Downloaded piece $count/$total ($percent%)")
                    Chunk.byteVector(piece)
                  }
                )
                .unchunks
                .through(
                  fs2.io.file.Files[IO].writeAll(Path("downloaded.data"), Flags.Write)
                )
                .compile
                .drain
              ExitCode.Success
            }
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
