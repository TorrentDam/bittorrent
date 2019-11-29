import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import fs2.concurrent.Queue
import scodec.Codec
import logstage.LogIO
import izumi.logstage.api.IzLogger
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

object Main extends IOApp {

  implicit val logger: LogIO[IO] = LogIO.fromLogger(IzLogger())
  implicit val decoder: Codec[String] = scodec.codecs.utf8

  def run(args: List[String]): IO[ExitCode] = {
    BlazeServerBuilder[IO]
      .withHttpApp(httpApp)
      .withWebSockets(true)
      .bindHttp(9999, "0.0.0.0")
      .serve
      .compile
      .lastOrError
  }

  def httpApp: HttpApp[IO] = {
    import org.http4s.dsl.io._
    HttpRoutes
      .of[IO] {
        case GET -> Root => Ok("Success")
        case GET -> Root / "ws" =>
          for {
            input <- Queue.unbounded[IO, WebSocketFrame]
            output <- Queue.unbounded[IO, WebSocketFrame]
            fiber <- processor(input, output).compile.drain.start
            response <- WebSocketBuilder[IO].build(
              output.dequeue,
              input.enqueue,
              onClose = fiber.cancel
            )
          } yield response
      }
      .mapF(_.getOrElseF(NotFound()))
  }

  def processor(
    input: Queue[IO, WebSocketFrame],
    output: Queue[IO, WebSocketFrame]
  ): Stream[IO, Unit] =
    input.dequeue.evalMap(frame => logger.info(s"Received $frame"))

}
