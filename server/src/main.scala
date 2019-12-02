import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import com.github.lavrov.bittorrent.dht.{Client, NodeId, PeerDiscovery}
import com.github.lavrov.bittorrent.wire.{Connection, ConnectionManager, TorrentControl}
import com.github.lavrov.bittorrent.{FileStorage, InfoHash, PeerId}
import fs2.Stream
import fs2.io.tcp.SocketGroup
import fs2.io.udp.{SocketGroup => UdpSocketGroup}
import izumi.logstage.api.IzLogger
import logstage.LogIO
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpApp, HttpRoutes, Response}
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
      makeTorrentControl = { (infoHash: InfoHash) =>
        implicit val ev0 = socketGroup
        for {
          connectionManager <- ConnectionManager[IO](
            Stream.eval(PeerDiscovery.start(infoHash, dhtClient)).flatten,
            peerInfo => Connection.connect[IO](selfId, peerInfo, infoHash)
          )
          result <- Resource.liftF { TorrentControl[IO](connectionManager, FileStorage.noop) }
        } yield result
      }
      handleSocket = SocketSession(makeTorrentControl)
    } yield httpApp(handleSocket)

  def serve(app: HttpApp[IO]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withHttpApp(app)
      .withWebSockets(true)
      .bindHttp(9999, "0.0.0.0")
      .serve
      .compile
      .lastOrError

  def httpApp(handleSocket: IO[Response[IO]]): HttpApp[IO] = {
    import org.http4s.dsl.io._
    HttpRoutes
      .of[IO] {
        case GET -> Root => Ok("Success")
        case GET -> Root / "ws" => handleSocket
      }
      .mapF(_.getOrElseF(NotFound()))
  }
}
