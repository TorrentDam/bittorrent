import cats.syntax.all._
import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Resource
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.{Node, NodeId, PeerDiscovery, QueryHandler, RoutingTable, RoutingTableBootstrap}
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

    Opts.subcommand("dht", "discover peers"){
      Opts.option[String]("info-hash", "Info-hash").map { infoHash =>
        val selfId = NodeId.generate(Random)
        val resources =
          for {
            table <- Resource.eval { RoutingTable[IO](selfId) }
            node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
              Node(selfId, QueryHandler(selfId, table))
            }
            _ <- Resource.eval {
              RoutingTableBootstrap(table, node.client)
            }
            discovery <- PeerDiscovery.make(table, node.client)
          } yield {
            discovery.discover(InfoHash.fromString(infoHash))
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
  }
}