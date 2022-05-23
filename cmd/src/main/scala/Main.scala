import cats.syntax.all.*
import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.effect.{ExitCode, IO}
import com.comcast.ip4s.SocketAddress
import com.github.lavrov.bittorrent.dht.*
import com.github.lavrov.bittorrent.wire.{Connection, Download, DownloadMetadata, Swarm}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.net.Network
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "tdm",
      header = "TorrentDam"
    ) {

  def main: Opts[IO[ExitCode]] = {

    given logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromClass(classOf[Main.type])

    val discoverCommand =
      Opts.subcommand("dht", "discover peers") {
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>
          val resources =
            for
              given Random[IO] <- Resource.eval { Random.scalaUtilRandom[IO] }
              selfId <- Resource.eval { NodeId.generate[IO] }
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
            yield discovery.discover(infoHash)

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
      Opts.subcommand("metadata", "download metadata") {
        Opts.option[String]("info-hash", "Info-hash").map { infoHash =>

          val resources =
            for
              given Random[IO] <- Resource.eval { Random.scalaUtilRandom[IO] }
              selfId <- Resource.eval { NodeId.generate[IO] }
              selfPeerId <- Resource.eval { PeerId.generate[IO] }
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
            yield DownloadMetadata(swarm.connected.stream)

          resources.use { getMetadata =>
            getMetadata
              .flatMap(metadata => logger.info(s"Downloaded metadata $metadata"))
              .as(ExitCode.Success)
          }
        }
      }

    val downloadCommand =
      Opts.subcommand("download", "download torrent") {
        val options: Opts[(String, Option[String])] =
          (Opts.option[String]("info-hash", "Info-hash"), Opts.option[String]("peer", "Peer address").orNone).tupled
        options.map { case (infoHash, peerAddress) =>

          val resources =
            for
              given Random[IO] <- Resource.eval { Random.scalaUtilRandom[IO] }
              selfId <- Resource.eval { NodeId.generate[IO] }
              selfPeerId <- Resource.eval { PeerId.generate[IO] }
              infoHash <- Resource.eval {
                InfoHash.fromString
                  .unapply(infoHash)
                  .liftTo[IO](new Exception("Malformed info-hash"))
              }
              peerAddress <- Resource.pure(peerAddress.flatMap(SocketAddress.fromStringIp))
              table <- Resource.eval { RoutingTable[IO](selfId) }
              node <- Network[IO].datagramSocketGroup().flatMap { implicit group =>
                Node(selfId, QueryHandler(selfId, table))
              }
              _ <- Resource.eval { RoutingTableBootstrap(table, node.client) }
              discovery <- PeerDiscovery.make(table, node.client)
              peers <- Resource.pure(
                peerAddress match
                  case Some(peerAddress) => Stream.emit(PeerInfo(peerAddress)).covary[IO]
                  case None => discovery.discover(infoHash)
              )
              swarm <- Network[IO].socketGroup().flatMap { implicit group =>
                Swarm(peers, peerInfo => Connection.connect[IO](selfPeerId, peerInfo, infoHash))
              }
            yield swarm

          resources.use { swarm =>
            for
              metadata <- DownloadMetadata(swarm.connected.stream)
              _ <- Download(swarm, metadata.parsed).use { picker =>
                picker.pieces.flatMap { pieces =>
                  pieces.traverse { index =>
                    picker.download(index) >> logger.info(s"Downloaded piece $index")
                  }
                }
              }
            yield ExitCode.Success
          }
        }
      }

    discoverCommand <+> metadataCommand <+> downloadCommand
  }
}
