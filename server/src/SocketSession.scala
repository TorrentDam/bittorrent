import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, IO, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import com.github.lavrov.bittorrent.wire.{ConnectionManager, TorrentControl}
import com.github.lavrov.bittorrent.{FileStorage, InfoHash, InfoHashFromString, TorrentMetadata}
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
      pingFiber <- (timer.sleep(10.seconds) >> input.enqueue1(WebSocketFrame.Ping())).foreverM.start
      response <- WebSocketBuilder[IO].build(
        output.dequeue,
        input.enqueue,
        onClose = fiber.cancel >> pingFiber.cancel >> closeHandler >> logger.info("Session closed")
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
    controlRef: Ref[IO, Option[TorrentControl[IO]]],
    send: Event => IO[Unit],
    makeTorrentControl: InfoHash => IO[TorrentControl[IO]]
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
          _ <- send(Event.NewTorrent(infoHashString))
          control <- makeTorrentControl(infoHash)
          _ <- send(Event.TorrentMetadata())
          _ <- controlRef.set(control.some)
          _ <- Stream
            .repeatEval(
              (timer.sleep(10.seconds) >> control.stats).flatMap { stats =>
                send(Event.TorrentStats(infoHashString, stats.connected))
              }
            )
            .compile
            .drain
            .start
        } yield ()
    }
  }

  object CommandHandler {
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
        finalizer <- Ref.of(IO.unit)
        controlRef <- Ref.of(Option.empty[TorrentControl[IO]])
      } yield {
        val impl = new CommandHandler(
          controlRef,
          send,
          makeTorrentControl(_).allocated.flatMap {
            case (instance, close) =>
              finalizer.get.flatten >> finalizer.set(close).as(instance)
          }
        )
        val close: IO[Unit] = finalizer.get.flatten
        (impl, close)
      }
    }
  }
}
