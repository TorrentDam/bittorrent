import Routes.FileIndex
import cats.data.{Kleisli, OptionT}
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.github.lavrov.bittorrent.dht.{Node, NodeId, PeerDiscovery, QueryHandler, RoutingTable}
import com.github.lavrov.bittorrent.wire.{Connection, Swarm}
import com.github.lavrov.bittorrent.{FileMapping, PeerId, TorrentFile, InfoHash => BTInfoHash}
import com.github.lavrov.bittorrent.app.domain.InfoHash
import com.github.lavrov.bittorrent.dht.message.Query
import fs2.Stream
import fs2.concurrent.Queue
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
import org.http4s.{HttpApp, MediaType, Response}
import sun.misc.Signal

import scala.util.Random
import scala.concurrent.duration._

object Main extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger(IzLogger.Level.Info))
  val rnd = new Random
  val selfId: PeerId = PeerId.generate(rnd)
  val selfNodeId: NodeId = NodeId.generate(rnd)
  val downloadPieceTimeout: FiniteDuration = 3.minutes
  val maxPrefetchBytes = 50 * 1000 * 1000

  def run(args: List[String]): IO[ExitCode] = {
    registerSignalHandler >>
    makeApp.use { it =>
      val bindPort = Option(System.getenv("PORT")).flatMap(_.toIntOption).getOrElse(9999)
      serve(bindPort, it) <* logger.info(s"Started http server at 0.0.0.0:$bindPort")
    }
  }

  def registerSignalHandler: IO[Unit] =
    IO {
      Signal.handle(new Signal("INT"), _ => System.exit(0))
    }

  def makeApp: Resource[IO, HttpApp[IO]] = {
    import org.http4s.dsl.io._
    import cats.effect.Sync.catsOptionTSync
    for {
      implicit0(blocker: Blocker) <- Blocker[IO]
      implicit0(socketGroup: SocketGroup) <- SocketGroup[IO](blocker)
      udpSocketGroup <- UdpSocketGroup[IO](blocker)
      getPeerRequests <- Resource.liftF { Queue.unbounded[IO, com.github.lavrov.bittorrent.InfoHash] }
      dhtNode <- {
        implicit val ev0 = udpSocketGroup
        for {
          routingTable <- Resource.liftF { RoutingTable[IO](selfNodeId) }
          queryHandler <- QueryHandler(selfNodeId, routingTable).pure[Resource[IO, *]]
          queryHandler <-
            QueryHandler
              .fromFunction[IO] { query =>
                val registerInfoHash = query match {
                  case Query.GetPeers(_, infoHash) =>
                    getPeerRequests.enqueue1(infoHash)
                  case _ => IO.unit
                }
                queryHandler(query).flatTap { _ =>
                  registerInfoHash
                }
              }
              .pure[Resource[IO, *]]
          node <- Node[IO](selfNodeId, 9596, queryHandler, routingTable)
        } yield node
      }
      peerDiscovery <- PeerDiscovery.make[IO](dhtNode)
      metadataRegistry <- MetadataRegistry[IO]().to[Resource[IO, *]]
      _ <- MetadataDiscovery(
        getPeerRequests.dequeue,
        peerDiscovery,
        (infoHash, peerInfo) => Connection.connect[IO](selfId, peerInfo, infoHash),
        metadataRegistry
      ).background
      createSwarm = (infoHash: InfoHash) => {
        Swarm[IO](
          peerDiscovery.discover(BTInfoHash(infoHash.bytes)),
          peerInfo => Connection.connect[IO](selfId, peerInfo, BTInfoHash(infoHash.bytes)),
          30
        )
      }
      createServerTorrent = new ServerTorrent.Create(createSwarm, metadataRegistry)
      torrentRegistry <- Resource.liftF { TorrentRegistry.make(createServerTorrent) }
      handleSocket = SocketSession(torrentRegistry.get, metadataRegistry)
      handleGetTorrent =
        (infoHash: InfoHash) =>
          torrentRegistry
            .tryGet(infoHash)
            .use { torrent =>
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
              OptionT.liftF(
                Ok(
                  bytes.toByteArray,
                  `Content-Disposition`("inline", Map("filename" -> s"$filename.torrent"))
                )
              )
            }
            .getOrElseF {
              NotFound("Torrent not found")
            }
      handleGetData =
        (infoHash: InfoHash, fileIndex: FileIndex, rangeOpt: Option[Range]) =>
          torrentRegistry.tryGet(infoHash).allocated.value.flatMap {
            case Some((torrent: ServerTorrent, release)) =>
              if (torrent.files.value.lift(fileIndex).isDefined) {
                val file = torrent.metadata.parsed.files(fileIndex)
                val extension = file.path.lastOption.map(_.reverse.takeWhile(_ != '.').reverse)
                val fileMapping = torrent.files
                val parallelPieces = scala.math.max(maxPrefetchBytes / torrent.metadata.parsed.pieceLength, 2).toInt
                def dataStream(span: FileMapping.Span) = {
                  Stream
                    .emits(span.beginIndex to span.endIndex)
                    .covary[IO]
                    .parEvalMap(parallelPieces) { index =>
                      torrent
                        .piece(index.toInt)
                        .timeoutTo(
                          downloadPieceTimeout,
                          IO.raiseError(PieceDownloadTimeout(index))
                        )
                        .tupleLeft(index)
                    }
                    .flatMap {
                      case (span.beginIndex, bytes) =>
                        bytes.drop(span.beginOffset)
                      case (span.endIndex, bytes) =>
                        bytes.take(span.endOffset)
                      case (_, bytes) => bytes
                    }
                    .onFinalize(release.value.void)
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

      handleDiscoverTorrents = for {
        response <- Ok("TODO")
      } yield response

    } yield Routes.httpApp(handleSocket, handleGetTorrent, handleGetData, handleDiscoverTorrents)
  }

  def serve(bindPort: Int, app: HttpApp[IO]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withHttpApp(app)
      .withWebSockets(true)
      .bindHttp(bindPort, "0.0.0.0")
      .serve
      .compile
      .lastOrError

  case class PieceDownloadTimeout(index: Long) extends Throwable(s"Timeout downloading piece $index")

}

object Routes {
  val dsl = org.http4s.dsl.io

  def httpApp(
    handleSocket: IO[Response[IO]],
    handleGetTorrent: InfoHash => IO[Response[IO]],
    handleGetData: (InfoHash, FileIndex, Option[Range]) => IO[Response[IO]],
    handleDiscovered: IO[Response[IO]]
  ): HttpApp[IO] = {
    import dsl._
    Kleisli {
      case GET -> Root => Ok("Success")
      case GET -> Root / "ws" => handleSocket
      case GET -> Root / "torrent" / InfoHash.fromString(infoHash) / "metadata" =>
        handleGetTorrent(infoHash)
      case req @ GET -> Root / "torrent" / InfoHash.fromString(infoHash) / "data" / FileIndexVar(index) =>
        handleGetData(infoHash, index, req.headers.get(Range))
      case GET -> Root / "discover" / "torrents" =>
        handleDiscovered
      case _ => NotFound()
    }
  }

  type FileIndex = Int
  val FileIndexVar: PartialFunction[String, FileIndex] = Function.unlift { (in: String) =>
    in.toIntOption.filter(_ >= 0)
  }
}
