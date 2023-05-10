import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.Resource
import cats.effect.ResourceIO
import cats.effect.std.Random
import cats.effect.{ExitCode, IO}
import com.comcast.ip4s.SocketAddress
//import com.github.lavrov.bittorrent.dht.*
import com.github.lavrov.bittorrent.wire.{Connection, Download, DownloadMetadata, RequestDispatcher, Swarm, Torrent}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Flags, Path}
import fs2.{Chunk, Stream}
import org.legogroup.woof.{*, given}
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.DurationInt

import cps.{async, await}
import cps.syntax.*
import cps.monads.catsEffect.{asyncScope, given}

object Main
    extends CommandIOApp(
      name = "tdm",
      header = "TorrentDam"
    ) {

  def main: Opts[IO[ExitCode]] =
//    val discoverCommand =
//      Opts.subcommand("dht", "discover peers") {
//        Opts.option[String]("info-hash", "Info-hash").map { infoHash0 =>
//          withLogger {
//            asyncScope[IO] {
//              given Random[IO] = !Random.scalaUtilRandom[IO]
//              val selfId = !NodeId.generate[IO]
//              val infoHash = await {
//                InfoHash.fromString
//                  .unapply(infoHash0)
//                  .liftTo[IO](new Exception("Malformed info-hash"))
//              }
//              val table = !RoutingTable[IO](selfId)
//              val node = !Node(selfId, QueryHandler(selfId, table))
//              !RoutingTableBootstrap(table, node.client)
//              val discovery = !PeerDiscovery.make(table, node.client)
//              !discovery
//                .discover(infoHash)
//                .evalTap { peerInfo =>
//                  Logger[IO].trace(s"Discovered peer ${peerInfo.address}")
//                }
//                .compile
//                .drain
//                .as(ExitCode.Success)
//            }
//          }
//        }
//      }
//
//    val metadataCommand =
//      Opts.subcommand("metadata", "download metadata") {
//        Opts.option[String]("info-hash", "Info-hash").map { infoHash0 =>
//          withLogger {
//            asyncScope[IO] {
//              given Random[IO] = !Random.scalaUtilRandom[IO]
//
//              val selfId = !NodeId.generate[IO]
//              val selfPeerId = !PeerId.generate[IO]
//              val infoHash =
//                !InfoHash.fromString
//                  .unapply(infoHash0)
//                  .liftTo[IO](new Exception("Malformed info-hash"))
//              val table = !RoutingTable[IO](selfId)
//              val node = !Node(selfId, QueryHandler(selfId, table))
//              !RoutingTableBootstrap(table, node.client)
//              val discovery = !PeerDiscovery.make(table, node.client)
//
//
//              val swarm = !Swarm(
//                discovery.discover(infoHash),
//                Connection.connect(selfPeerId, _, infoHash)
//              )
//              !DownloadMetadata(swarm)
//                .flatMap(metadata => Logger[IO].info(s"Downloaded metadata $metadata"))
//                .as(ExitCode.Success)
//            }
//          }
//        }
//      }
//
//    val downloadCommand =
//      Opts.subcommand("download", "download torrent") {
//        val options: Opts[(String, Option[String])] =
//          (Opts.option[String]("info-hash", "Info-hash"), Opts.option[String]("peer", "Peer address").orNone).tupled
//        options.map { case (infoHash0, peerAddress0) =>
//          withLogger {
//            asyncScope[IO] {
//              given Random[IO] = !Random.scalaUtilRandom[IO]
//              val selfId = !NodeId.generate[IO]
//              val selfPeerId = !PeerId.generate[IO]
//              val infoHash =
//                !InfoHash.fromString
//                  .unapply(infoHash0)
//                  .liftTo[IO](new Exception("Malformed info-hash"))
//              val peerAddress = peerAddress0.flatMap(SocketAddress.fromStringIp)
//              val peers: Stream[IO, PeerInfo] = !async[ResourceIO] {
//                peerAddress match
//                  case Some(peerAddress) =>
//                    Stream.emit(PeerInfo(peerAddress)).covary[IO]
//                  case None =>
//                    val table = !Resource.eval {
//                      RoutingTable[IO](selfId)
//                    }
//                    val node = !Node(selfId, QueryHandler(selfId, table))
//                    !Resource.eval {
//                      RoutingTableBootstrap(table, node.client)
//                    }
//                    val discovery = !PeerDiscovery.make(table, node.client)
//                    discovery.discover(infoHash)
//              }
//              val swarm = !Swarm(peers, peerInfo => Connection.connect(selfPeerId, peerInfo, infoHash))
//              val metadata = !DownloadMetadata(swarm)
//              val torrent = !Torrent.make(metadata, swarm)
//              val total = (metadata.parsed.pieces.length.toDouble / 20).ceil.toLong
//              val counter = !IO.ref(0)
//              !Stream
//                .range(0L, total)
//                .parEvalMap(10)(index =>
//                  async[IO] {
//                    val piece = !torrent.downloadPiece(index)
//                    val count = !counter.updateAndGet(_ + 1)
//                    val percent = ((count.toDouble / total) * 100).toInt
//                    !Logger[IO].info(s"Downloaded piece $count/$total ($percent%)")
//                    Chunk.byteVector(piece)
//                  }
//                )
//                .unchunks
//                .through(
//                  fs2.io.file.Files[IO].writeAll(Path("downloaded.data"), Flags.Write)
//                )
//                .compile
//                .drain
//              ExitCode.Success
//            }
//          }
//        }
//      }

//    discoverCommand <+> metadataCommand <+> downloadCommand

    Opts.subcommand("download", "download torrent") {
      val options: Opts[(String, String)] = (
        Opts.option[String]("info-hash", "Info-hash"),
        Opts.option[String]("peer", "Peer address")
      ).tupled
      options.map { case (infoHash0, peerAddressOpt) =>
        withLogger {
          asyncScope[IO] {
            given Random[IO] = !Random.scalaUtilRandom[IO]

            val selfPeerId = !PeerId.generate[IO]
            val infoHash =
              !InfoHash.fromString
                .unapply(infoHash0)
                .liftTo[IO](new Exception("Malformed info-hash"))
            val peerAddress = !IO.fromOption(SocketAddress.fromStringIp(peerAddressOpt))(new Exception("Malformed peer address"))
            val peers: Stream[IO, PeerInfo] = Stream.emit(PeerInfo(peerAddress)).covary[IO]
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
  end main

  def withLogger[A](body: Logger[IO] ?=> IO[A]): IO[A] =
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = ColorPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO]).flatMap(body(using _))
}
