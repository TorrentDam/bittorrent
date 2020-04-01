import Routes.FileIndex
import cats.data.Kleisli
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.github.lavrov.bittorrent.dht.{Client, NodeId, PeerDiscovery}
import com.github.lavrov.bittorrent.wire.{Connection, Swarm}
import com.github.lavrov.bittorrent.{FileMapping, PeerId, TorrentFile, InfoHash => BTInfoHash}
import com.github.lavrov.bittorrent.app.domain.InfoHash
import fs2.Stream
import fs2.io.tcp.SocketGroup
import fs2.io.udp.{SocketGroup => UdpSocketGroup}
import izumi.logstage.api.IzLogger
import logstage.LogIO
import org.http4s.headers.{
  `Accept-Ranges`,
  `Content-Disposition`,
  `Content-Length`,
  `Content-Range`,
  `Content-Type`,
  Range
}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpApp, HttpRoutes, MediaType, Response}
import scodec.Codec

import scala.util.Random
import scala.concurrent.duration._

object Main extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger(IzLogger.Level.Info))
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

  def makeApp: Resource[IO, HttpApp[IO]] = {
    import org.http4s.dsl.io._
    for {
      implicit0(blocker: Blocker) <- Blocker[IO]
      socketGroup <- SocketGroup[IO](blocker)
      udpSocketGroup <- UdpSocketGroup[IO](blocker)
      dhtClient <- {
        implicit val ev0 = udpSocketGroup
        Client.start[IO](selfNodeId, 9596)
      }
      peerDiscovery <- PeerDiscovery.make[IO](dhtClient)
      createSwarm = (infoHash: InfoHash) => {
        implicit val ev0 = socketGroup
        Swarm[IO](
          peerDiscovery.discover(BTInfoHash(infoHash.bytes)),
          peerInfo => Connection.connect[IO](selfId, peerInfo, BTInfoHash(infoHash.bytes)),
          30
        )
      }
      createServerTorrent = new ServerTorrent.Create(createSwarm)
      torrentRegistry <- Resource.liftF { TorrentRegistry.make(createServerTorrent) }
      handleSocket = SocketSession(torrentRegistry.get)
      handleGetTorrent = (infoHash: InfoHash) =>
        torrentRegistry.tryGet(infoHash).use {
          case Some(torrent) =>
            val metadata = torrent.metadata
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
      handleGetData = (infoHash: InfoHash, fileIndex: FileIndex, rangeOpt: Option[Range]) =>
        torrentRegistry.tryGet(infoHash).use {
          case Some(torrent) =>
            if (torrent.files.value.lift(fileIndex).isDefined) {
              val file = torrent.metadata.parsed.files(fileIndex)
              val extension = file.path.lastOption.map(_.reverse.takeWhile(_ != '.').reverse)
              val fileMapping = torrent.files
              val maxPrefetchBytes = 20 * 1000 * 1000
              val parallelPieces = scala.math.max(maxPrefetchBytes / torrent.metadata.parsed.pieceLength, 1).toInt
              def dataStream(span: FileMapping.Span) =
                Stream.resource(torrentRegistry.tryGet(infoHash)).flatMap {
                  case Some(torrent) =>
                    Stream
                      .emits(span.beginIndex to span.endIndex)
                      .covary[IO]
                      .parEvalMap(parallelPieces) { index =>
                        torrent
                          .piece(index.toInt)
                          .timeout(1.minute)
                          .tupleLeft(index)
                      }
                      .flatMap {
                        case (span.beginIndex, bytes) =>
                          bytes.drop(span.beginOffset)
                        case (span.endIndex, bytes) =>
                          bytes.take(span.endOffset)
                        case (_, bytes) => bytes
                      }
                  case _ =>
                    Stream.empty
                }
              val mediaType =
                extension.flatMap(MediaType.forExtension).getOrElse(MediaType.application.`octet-stream`)
              val span0 = fileMapping.value(fileIndex)
              rangeOpt match {
                case Some(range) =>
                  val first = range.ranges.head.first
                  val second = range.ranges.head.second
                  val advanced = span0.advance(first)
                  val span = second.fold(advanced) { second =>
                    advanced.take(second - first)
                  }
                  val subRange = rangeOpt match {
                    case Some(range) =>
                      val first = range.ranges.head.first
                      val second = range.ranges.head.second.getOrElse(file.length - 1)
                      Range.SubRange(first, second)
                    case None =>
                      Range.SubRange(0L, file.length - 1)
                  }
                  PartialContent(
                    dataStream(span),
                    `Content-Type`(mediaType),
                    `Accept-Ranges`.bytes,
                    `Content-Range`(subRange, file.length.some)
                  )
                case None =>
                  val filename = file.path.lastOption.getOrElse(s"file-$fileIndex")
                  Ok(
                    dataStream(span0),
                    `Accept-Ranges`.bytes,
                    `Content-Type`(mediaType),
                    `Content-Disposition`("inline", Map("filename" -> filename)),
                    `Content-Length`.unsafeFromLong(file.length)
                  )
              }
            }
            else {
              NotFound(s"Torrent does not contain file with index $fileIndex")
            }
          case None => NotFound("Torrent not found")
        }

    } yield Routes.httpApp(handleSocket, handleGetTorrent, handleGetData)
  }

  def serve(bindPort: Int, app: HttpApp[IO]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withHttpApp(app)
      .withWebSockets(true)
      .bindHttp(bindPort, "0.0.0.0")
      .serve
      .compile
      .lastOrError

}

object Routes {
  val dsl = org.http4s.dsl.io

  def httpApp(
    handleSocket: IO[Response[IO]],
    handleGetTorrent: InfoHash => IO[Response[IO]],
    handleGetData: (InfoHash, FileIndex, Option[Range]) => IO[Response[IO]]
  ): HttpApp[IO] = {
    import dsl._
    Kleisli {
      case GET -> Root => Ok("Success")
      case GET -> Root / "ws" => handleSocket
      case GET -> Root / "torrent" / InfoHash.fromString(infoHash) / "metadata" =>
        handleGetTorrent(infoHash)
      case req @ GET -> Root / "torrent" / InfoHash.fromString(infoHash) / "data" / FileIndexVar(index) =>
        handleGetData(infoHash, index, req.headers.get(Range))
      case _ => NotFound()
    }
  }

  type FileIndex = Int
  val FileIndexVar: PartialFunction[String, FileIndex] = Function.unlift { (in: String) =>
    in.toIntOption.filter(_ >= 0)
  }
}
