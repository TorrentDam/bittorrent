import cats.syntax.all.*
import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Resource
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import com.github.lavrov.bittorrent.dht.{Node, NodeId, PeerDiscovery, QueryHandler, RoutingTable, RoutingTableBootstrap}
import com.github.lavrov.bittorrent.wire.{Connection, Download, DownloadMetadata, Swarm}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.net.Network
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.util.Random


object Main extends CommandIOApp(
  name = "tdm",
  header = "TorrentDam"
) {

  def main: Opts[IO[ExitCode]] = {

    implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromClass(classOf[Main.type])

    val discoverCommand =
      Opts.subcommand("dht", "discover peers"){
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>
          val selfId = NodeId.generate(Random)
          val resources =
            for {
              infoHash <- Resource.eval {
                InfoHash.fromString
                  .unapply(infoHash)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              }
              table <- Resource.eval { RoutingTable[IO](selfId) }
              node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                Node(selfId, QueryHandler(selfId, table))
              }
              _ <- Resource.eval { RoutingTableBootstrap(table, node.client) }
              discovery <- PeerDiscovery.make(table, node.client)
            } yield {
              discovery.discover(infoHash)
            }
          resources.use { stream =>
            stream
              .evalTap { peerInfo =>
                logger.info(s"Discovered peer ${peerInfo.address}")
              }
              .compile
              .drain
              .as(ExitCode.Success)
          }
        }
      }

    val metadataCommand =
      Opts.subcommand("metadata", "download metadata"){
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>

          val selfId = NodeId.generate(Random)
          val selfPeerId = PeerId.generate(Random)

          val resources =
            for {
              infoHash <- Resource.eval {
                InfoHash.fromString
                  .unapply(infoHash)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              }
              table <- Resource.eval { RoutingTable[IO](selfId) }
              node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                Node(selfId, QueryHandler(selfId, table))
              }
              _ <- Resource.eval { RoutingTableBootstrap(table, node.client) }
              discovery <- PeerDiscovery.make(table, node.client)
              swarm <- Network[IO].socketGroup().flatMap { implicit group =>
                Swarm(
                  discovery.discover(infoHash),
                  Connection.connect(selfPeerId, _, infoHash)
                )
              }
            } yield {
              DownloadMetadata(swarm.connected.stream)
            }

          resources.use { getMetadata =>
            getMetadata
              .flatMap(metadata => logger.info(s"Downloaded metadata $metadata"))
              .as(ExitCode.Success)
          }
        }
      }

    val downloadCommand =
      Opts.subcommand("download", "download torrent"){
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>

          val selfId = NodeId.generate(Random)
          val selfPeerId = PeerId.generate(Random)

          val resources =
            for {
              infoHash <- Resource.eval {
                InfoHash.fromString
                  .unapply(infoHash)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              }
              table <- Resource.eval { RoutingTable[IO](selfId) }
              node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                Node(selfId, QueryHandler(selfId, table))
              }
              _ <- Resource.eval { RoutingTableBootstrap(table, node.client) }
              discovery <- PeerDiscovery.make(table, node.client)
              swarm <- Network[IO].socketGroup().flatMap { implicit group =>
                Swarm(
                  discovery.discover(infoHash),
                  peerInfo =>
                    Connection
                      .connect[IO](selfPeerId, peerInfo, infoHash)
                      .evalTap(connection => logger.info(s"Connected to ${connection.info.address}"))
                )
              }
            } yield {
              swarm
            }

          resources.use { swarm =>
            for {
              metadata <- DownloadMetadata(swarm.connected.stream)
              _ <- Download(swarm, metadata.parsed).use { picker =>
                picker.pieces.flatMap { pieces =>
                  pieces.traverse { index =>
                    picker.download(index) >> logger.info(s"Downloaded piece $index")
                  }
                }
              }
            } yield {
              ExitCode.Success
            }
          }
        }
      }

    discoverCommand <+> metadataCommand <+> downloadCommand
  }
}