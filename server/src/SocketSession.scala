import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, IO, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import com.github.lavrov.bittorrent.wire.TorrentControl
import com.github.lavrov.bittorrent.{InfoHash, InfoHashFromString}
import fs2.Stream
import fs2.concurrent.Queue
import logstage.LogIO
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration._
import scala.util.Try

object SocketSession {
  def apply(
    makeTorrentControl: InfoHash => Resource[IO, TorrentControl[IO]]
  )(
    implicit F: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ): IO[Response[IO]] =
    for {
      _ <- logger.info("Session started")
      input <- Queue.unbounded[IO, WebSocketFrame]
      output <- Queue.unbounded[IO, WebSocketFrame]
      send = (e: Event) => output.enqueue1(WebSocketFrame.Text(upickle.default.write(e)))
      handlerAndClose <- CommandHandler(send, makeTorrentControl).allocated
      (handler, closeHandler) = handlerAndClose
      fiber <- processor(input, send, handler).compile.drain.start
      response <- WebSocketBuilder[IO].build(
        output.dequeue,
        input.enqueue,
        onClose = fiber.cancel >> closeHandler >> logger.info("Session closed")
      )
    } yield response

  private def processor(
    input: Queue[IO, WebSocketFrame],
    send: Event => IO[Unit],
    commandHandler: CommandHandler
  )(implicit logger: LogIO[IO]): Stream[IO, Unit] = {
    input.dequeue.evalMap {
      case WebSocketFrame.Text(Cmd(command), _) =>
        for {
          _ <- logger.info(s"Received $command")
          _ <- commandHandler.handle(command)
        } yield ()
      case _ => IO.unit
    }
  }

  private def onCommand(command: Command, send: Event => IO[Unit]): IO[Unit] = command match {
    case Command.AddTorrent(infoHash) => send(Event.NewTorrent(infoHash))
  }

  private val Cmd: PartialFunction[String, Command] =
    ((input: String) => Try(upickle.default.read[Command](input)).toOption).unlift

  class CommandHandler(
    controlRef: Ref[IO, Option[CommandHandler.AllocatedControl]],
    send: Event => IO[Unit],
    makeTorrentControl: InfoHash => Resource[IO, TorrentControl[IO]]
  )(
    implicit
    F: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ) {
    def handle(command: Command): IO[Unit] = command match {
      case Command.AddTorrent(infoHashString @ InfoHashFromString(infoHash)) =>
        for {
          currentControl <- controlRef.get
          _ <- currentControl.traverse(_.close)
          control <- makeTorrentControl(infoHash).allocated
            .map(CommandHandler.AllocatedControl.tupled)
          _ <- controlRef.set(control.some)
          _ <- Stream
            .repeatEval(
              (timer.sleep(10.seconds) >> control.instance.stats).flatMap { stats =>
                send(Event.TorrentStats(infoHashString, stats.connected))
              }
            )
            .compile
            .drain
            .start
          _ <- send(Event.NewTorrent(infoHashString))
        } yield ()
    }
  }

  object CommandHandler {
    case class AllocatedControl(instance: TorrentControl[IO], close: IO[Unit])
    def apply(
      send: Event => IO[Unit],
      makeTorrentControl: InfoHash => Resource[IO, TorrentControl[IO]]
    )(
      implicit
      F: Concurrent[IO],
      cs: ContextShift[IO],
      timer: Timer[IO],
      logger: LogIO[IO]
    ): Resource[IO, CommandHandler] = Resource {
      for {
        controlRef <- Ref.of(Option.empty[AllocatedControl])
      } yield {
        val impl = new CommandHandler(controlRef, send, makeTorrentControl)
        val close: IO[Unit] = controlRef.get.flatMap {
          case Some(allocatedControl) => allocatedControl.close
          case _ => IO.unit
        }
        (impl, close)
      }
    }
  }
}
