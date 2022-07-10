import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.effect.{ExitCode, IO}
import com.comcast.ip4s.SocketAddress
import com.github.lavrov.bittorrent.dht.*
import com.github.lavrov.bittorrent.wire.{Connection, Download, DownloadMetadata, Swarm}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.net.{Network, SocketGroup}
import fs2.Stream
import org.legogroup.woof.{*, given}

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.DurationInt

object Main
    extends CommandIOApp(
      name = "tdm",
      header = "TorrentDam"
    ) {

  def main: Opts[IO[ExitCode]] = {
    val discoverCommand =
      Opts.subcommand("dht", "discover peers") {
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>
          withLogger {
            val resources =
              for
                given Random[IO] <- Resource.eval {
                  Random.scalaUtilRandom[IO]
                }
                selfId <- Resource.eval {
                  NodeId.generate[IO]
                }
                infoHash <- Resource.eval {
                  InfoHash.fromString
                    .unapply(infoHash)
                    .liftTo[IO](new Exception("Malformed info-hash"))
                }
                table <- Resource.eval {
                  RoutingTable[IO](selfId)
                }
                node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                  Node(selfId, QueryHandler(selfId, table))
                }
                _ <- Resource.eval {
                  RoutingTableBootstrap(table, node.client)
                }
                discovery <- PeerDiscovery.make(table, node.client)
              yield discovery.discover(infoHash)

            resources.use { stream =>
              stream
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
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>
          withLogger {
            val resources =
              for
                given Random[IO] <- Resource.eval {
                  Random.scalaUtilRandom[IO]
                }
                selfId <- Resource.eval {
                  NodeId.generate[IO]
                }
                selfPeerId <- Resource.eval {
                  PeerId.generate[IO]
                }
                infoHash <- Resource.eval {
                  InfoHash.fromString
                    .unapply(infoHash)
                    .liftTo[IO](new Exception("Malformed info-hash"))
                }
                table <- Resource.eval {
                  RoutingTable[IO](selfId)
                }
                node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                  Node(selfId, QueryHandler(selfId, table))
                }
                _ <- Resource.eval {
                  RoutingTableBootstrap(table, node.client)
                }
                discovery <- PeerDiscovery.make(table, node.client)
                given SocketGroup[IO] <- Network[IO].socketGroup()
                swarm <- Swarm(
                  discovery.discover(infoHash),
                  Connection.connect(selfPeerId, _, infoHash)
                )
              yield DownloadMetadata(swarm.connected.stream)

            resources.use { getMetadata =>
              getMetadata
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
        options.map { case (infoHash, peerAddress) =>
          withLogger {
            val resources =
              for
                given Random[IO] <- Resource.eval {
                  Random.scalaUtilRandom[IO]
                }
                selfId <- Resource.eval {
                  NodeId.generate[IO]
                }
                selfPeerId <- Resource.eval {
                  PeerId.generate[IO]
                }
                infoHash <- Resource.eval {
                  InfoHash.fromString
                    .unapply(infoHash)
                    .liftTo[IO](new Exception("Malformed info-hash"))
                }
                peerAddress <- Resource.pure(peerAddress.flatMap(SocketAddress.fromStringIp))
                table <- Resource.eval {
                  RoutingTable[IO](selfId)
                }
                node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                  Node(selfId, QueryHandler(selfId, table))
                }
                _ <- Resource.eval {
                  RoutingTableBootstrap(table, node.client)
                }
                discovery <- PeerDiscovery.make(table, node.client)
                peers <- Resource.pure(
                  peerAddress match
                    case Some(peerAddress) => Stream.emit(PeerInfo(peerAddress)).covary[IO]
                    case None              => discovery.discover(infoHash)
                )
                swarm <- Network[IO].socketGroup().flatMap { implicit group =>
                  Swarm(
                    peers,
                    peerInfo =>
                      Connection.connect[IO](selfPeerId, peerInfo, infoHash)
                  )
                }
              yield swarm

            resources.use { swarm =>
              for
                metadata <- DownloadMetadata(swarm.connected.stream)
                _ <- Download(swarm, metadata.parsed).use { picker =>
                  (picker.pieces, IO.ref(0)).tupled.flatMap { (pieces, counter) =>
                    val total = pieces.size
                    Stream
                      .emits(pieces)
                      .parEvalMap(10)(index =>
                        for
                          _ <- picker.download(index)
                          count <- counter.updateAndGet(_ + 1)
                          percent = ((count.toDouble / total) * 100).toInt
                          _ <- Logger[IO].info(s"Downloaded piece $count/$total ($percent%)")
                        yield ()
                      )
                      .compile
                      .drain
                  }
                }
              yield ExitCode.Success
            }
          }
        }
      }

    discoverCommand <+> metadataCommand <+> downloadCommand
  }

  def withLogger[A](body: Logger[IO] ?=> IO[A]): IO[A] =
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = ColorPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO]).flatMap(body(using _))
}
