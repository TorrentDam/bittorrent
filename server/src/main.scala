import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import com.github.lavrov.bittorrent.dht.{Client, NodeId, PeerDiscovery}
import com.github.lavrov.bittorrent.wire.{Connection, ConnectionManager, TorrentControl}
import com.github.lavrov.bittorrent.{
  FileStorage,
  InfoHash,
  InfoHashFromString,
  PeerId,
  TorrentFile,
  TorrentMetadata
}
import fs2.Stream
import fs2.io.tcp.SocketGroup
import fs2.io.udp.{SocketGroup => UdpSocketGroup}
import izumi.logstage.api.IzLogger
import logstage.LogIO
import org.http4s.headers.`Content-Type`
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpApp, HttpRoutes, MediaType, Response}
import scodec.Codec

import scala.util.Random

object Main extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger())
  implicit val decoder: Codec[String] = scodec.codecs.utf8
  val rnd = new Random
  val selfId: PeerId = PeerId.generate(rnd)
  val selfNodeId: NodeId = NodeId.generate(rnd)

  def run(args: List[String]): IO[ExitCode] = {
    makeApp.use { it =>
      serve(it)
    }
  }

  def makeApp: Resource[IO, HttpApp[IO]] =
    for {
      blocker <- Blocker[IO]
      socketGroup <- SocketGroup[IO](blocker)
      udpSocketGroup <- UdpSocketGroup[IO](blocker)
      dhtClient <- {
        implicit val ev0 = udpSocketGroup
        Client.start[IO](selfNodeId, 9596)
      }
      torrentRegistry <- Resource.liftF { Ref.of[IO, Map[InfoHash, TorrentControl[IO]]](Map.empty) }
      makeTorrentControl = { (infoHash: InfoHash) =>
        implicit val ev0 = socketGroup
        for {
          connectionManager <- ConnectionManager[IO](
            Stream.eval(PeerDiscovery.start(infoHash, dhtClient)).flatten,
            peerInfo => Connection.connect[IO](selfId, peerInfo, infoHash)
          )
          metaInfo <- Resource.liftF { TorrentControl.downloadMetaInfo(connectionManager) }
          result <- Resource.liftF {
            TorrentControl[IO](metaInfo, connectionManager, FileStorage.noop)
          }
          _ <- {
            val add = torrentRegistry.update { map =>
              map.updated(infoHash, result)
            }
            val remove = torrentRegistry.update { map =>
              map.removed(infoHash)
            }
            Resource.make(add)(_ => remove)
          }
        } yield result
      }
      handleSocket = SocketSession(makeTorrentControl)
      handleGetTorrent = (infoHash: InfoHash) =>
        torrentRegistry.get.flatMap { map =>
          import org.http4s.dsl.io._
          map.get(infoHash).map(_.getMetaInfo) match {
            case Some(metadata) =>
              val torrentFile = TorrentFile(metadata, None)
              val bcode =
                TorrentFile.TorrentFileFormat
                  .write(torrentFile)
                  .toOption
                  .get
              val bytes = com.github.lavrov.bencode.encode(bcode)
              Ok(bytes.toByteArray)
            case None => NotFound("Torrent not found")
          }
        }
      handleGetData = (infoHash: InfoHash) =>
        torrentRegistry.get.flatMap { map =>
          import org.http4s.dsl.io._
          map.get(infoHash) match {
            case Some(control) =>
              val dataStream = control.downloadAllSequentially.map(p => p.bytes.toArray)
              Ok(dataStream, `Content-Type`(MediaType.video.`x-matroska`))
            case None => NotFound("Torrent not found")
          }
        }

    } yield httpApp(handleSocket, handleGetTorrent, handleGetData)

  def serve(app: HttpApp[IO]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withHttpApp(app)
      .withWebSockets(true)
      .bindHttp(9999, "0.0.0.0")
      .serve
      .compile
      .lastOrError

  def httpApp(
    handleSocket: IO[Response[IO]],
    handleGetTorrent: InfoHash => IO[Response[IO]],
    handleGetData: InfoHash => IO[Response[IO]]
  ): HttpApp[IO] = {
    import org.http4s.dsl.io._
    HttpRoutes
      .of[IO] {
        case GET -> Root => Ok("Success")
        case GET -> Root / "ws" => handleSocket
        case GET -> Root / "torrent" / InfoHashFromString(infoHash) / "metadata" =>
          handleGetTorrent(infoHash)
        case GET -> Root / "torrent" / InfoHashFromString(infoHash) / "data" =>
          handleGetData(infoHash)
      }
      .mapF(_.getOrElseF(NotFound()))
  }
}
