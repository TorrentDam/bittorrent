import cats.syntax.all._
import cats.effect.{Blocker, ExitCode, IO, IOApp}

import spinoco.fs2.http
import spinoco.fs2.http.websocket
import spinoco.fs2.http.websocket.Frame

import fs2.Stream
import fs2.Pipe

import scodec.bits.ByteVector
import scodec.Codec

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import fs2.io.tcp.SocketGroup

import logstage.LogIO
import izumi.logstage.api.IzLogger
import spinoco.protocol.http.HttpRequestHeader
import spinoco.protocol.http.Uri.Path
import spinoco.fs2.http.HttpResponse
import spinoco.protocol.http.HttpStatusCode
import spinoco.protocol.http.HttpResponseHeader

object Main extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger())
  implicit val decoder: Codec[String] = scodec.codecs.utf8

  def run(args: List[String]): IO[ExitCode] = socketGroup.use { implicit socketGroup =>
    http
      .server[IO](bindTo = new InetSocketAddress("0.0.0.0", 8080))(serve)
      .attempt
      .compile
      .drain
      .start
      .flatMap(
        fiber =>
          logger.info("Server started") >> fiber.join.guarantee(logger.info("Server stopped"))
      )
      .as(ExitCode.Success)
  }

  def socketGroup = Blocker[IO].flatMap(b => SocketGroup[IO](b))

  def serve(header: HttpRequestHeader, in: Stream[IO, Byte]) = header.path match {
    case Path.Root | Path.Empty =>
      Stream.emit(HttpResponse[IO](HttpStatusCode.Ok).withUtf8Body("OK"))
    case _ => websocket.server(serveWs)(header, in)
  }

  def serveWs: Pipe[IO, Frame[String], Frame[String]] = { in =>
    in.evalTap(frame => logger.info(s"Received $frame"))
  }

}
