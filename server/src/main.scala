import cats.syntax.all._
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import com.github.lavrov.bittorrent.dht.{Client, NodeId, PeerDiscovery}
import com.github.lavrov.bittorrent.wire.{Connection, ConnectionManager, TorrentControl}
import com.github.lavrov.bittorrent.{FileStorage, InfoHash, InfoHashFromString, PeerId, TorrentFile}
import fs2.Stream
import fs2.io.tcp.SocketGroup
import fs2.io.udp.{SocketGroup => UdpSocketGroup}
import izumi.logstage.api.IzLogger
import logstage.LogIO
import org.http4s.headers.`Content-Type`
import org.http4s.headers.`Content-Disposition`
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpApp, HttpRoutes, MediaType, Response}
import scodec.Codec

import scala.util.Random
import com.github.lavrov.bittorrent.FileMapping

object Main extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger())
  implicit val decoder: Codec[String] = scodec.codecs.utf8
  val rnd = new Random
  val selfId: PeerId = PeerId.generate(rnd)
  val selfNodeId: NodeId = NodeId.generate(rnd)

  def run(args: List[String]): IO[ExitCode] = {
    makeApp.use { it =>
      val bindPort = Option(System.getenv("PORT")).flatMap(_.toIntOption).getOrElse(9999)
      serve(bindPort, it) <* logger.info(s"Started http server at 0.0.0.0:$bindPort")
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
          makeTorrentControl = TorrentControl[IO](metaInfo, connectionManager, FileStorage.noop)
          result <- Resource.make(makeTorrentControl)(_.close)
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
              val filename = metadata.parsed.files match {
                case file :: Nil if file.path.nonEmpty => file.path.last
                case _ => infoHash.bytes.toHex
              }
              val bytes = com.github.lavrov.bencode.encode(bcode)
              Ok(
                bytes.toByteArray,
                `Content-Disposition`("inline", Map("filename" -> s"$filename.torrent"))
              )
            case None => NotFound("Torrent not found")
          }
        }
      handleGetData = (infoHash: InfoHash, fileIndex: FileIndex) =>
        torrentRegistry.get.flatMap { map =>
          import org.http4s.dsl.io._
          map.get(infoHash) match {
            case Some(control) =>
              if (fileIndex < control.getMetaInfo.parsed.files.size)
                NotFound(s"Torrent does not contain file with index $fileIndex")
              else {
                val fileMapping = FileMapping.fromMetadata(control.getMetaInfo.parsed)
                val span = fileMapping.value(fileIndex)
                val dataStream = control
                  .downloadAllSequentiallyFrom(span.beginIndex)
                  .takeWhile(_.index <= span.endIndex)
                  .map {
                    case TorrentControl.CompletePiece(span.beginIndex, _, bytes) =>
                      bytes.drop(span.beginOffset).toArray
                    case TorrentControl.CompletePiece(span.endIndex, _, bytes) =>
                      bytes.take(span.endOffset).toArray
                    case TorrentControl.CompletePiece(_, _, bytes) => bytes.toArray
                  }
                Ok(dataStream, `Content-Type`(MediaType.video.`x-matroska`))
              }
            case None => NotFound("Torrent not found")
          }
        }

    } yield httpApp(handleSocket, handleGetTorrent, handleGetData)

  def serve(bindPort: Int, app: HttpApp[IO]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withHttpApp(app)
      .withWebSockets(true)
      .bindHttp(bindPort, "0.0.0.0")
      .serve
      .compile
      .lastOrError

  def httpApp(
    handleSocket: IO[Response[IO]],
    handleGetTorrent: InfoHash => IO[Response[IO]],
    handleGetData: (InfoHash, FileIndex) => IO[Response[IO]]
  ): HttpApp[IO] = {
    import org.http4s.dsl.io._
    HttpRoutes
      .of[IO] {
        case GET -> Root => Ok("Success")
        case GET -> Root / "ws" => handleSocket
        case GET -> Root / "torrent" / InfoHashFromString(infoHash) / "metadata" =>
          handleGetTorrent(infoHash)
        case GET -> Root / "torrent" / InfoHashFromString(infoHash) / "data" / FileIndexVar(
              index
            ) =>
          handleGetData(infoHash, index)
      }
      .mapF(_.getOrElseF(NotFound()))
  }

  type FileIndex = Int
  val FileIndexVar: PartialFunction[String, FileIndex] = Function.unlift { (in: String) =>
    in.toIntOption.filter(_ >= 0)
  }
}
