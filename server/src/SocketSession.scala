import Main.logger
import cats.effect.{Concurrent, ContextShift, IO}
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import fs2.Stream
import fs2.concurrent.Queue
import logstage.LogIO
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

import scala.util.Try

object SocketSession {
  def apply()(
    implicit F: Concurrent[IO],
    cs: ContextShift[IO],
    logger: LogIO[IO]
  ): IO[Response[IO]] =
    for {
      _ <- logger.info("Connected")
      input <- Queue.unbounded[IO, WebSocketFrame]
      output <- Queue.unbounded[IO, WebSocketFrame]
      fiber <- processor(input, output).compile.drain.start
      response <- WebSocketBuilder[IO].build(
        output.dequeue,
        input.enqueue,
        onClose = fiber.cancel
      )
    } yield response

  private def processor(
    input: Queue[IO, WebSocketFrame],
    output: Queue[IO, WebSocketFrame]
  ): Stream[IO, Unit] = {
    def send(e: Event) = output.enqueue1(WebSocketFrame.Text(upickle.default.write(e)))
    input.dequeue.evalMap {
      case WebSocketFrame.Text(Cmd(command), _) =>
        for {
          _ <- logger.info(s"Received $command")
          _ <- onCommand(command, send)
        } yield ()
      case _ => IO.unit
    }
  }

  private def onCommand(command: Command, send: Event => IO[Unit]): IO[Unit] = command match {
    case Command.AddTorrent(infoHash) => send(Event.NewTorrent(infoHash))
  }

  private val Cmd: PartialFunction[String, Command] =
    ((input: String) => Try(upickle.default.read[Command](input)).toOption).unlift
}
