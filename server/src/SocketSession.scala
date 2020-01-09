import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, IO, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import com.github.lavrov.bittorrent.wire.Torrent
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
    makeTorrent: InfoHash => Resource[IO, IO[Torrent[IO]]]
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
      handlerAndClose <- CommandHandler(send, makeTorrent).allocated
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
    case Command.GetTorrent(infoHash) => send(Event.RequestAccepted(infoHash))
  }

  private val Cmd: PartialFunction[String, Command] =
    ((input: String) => Try(upickle.default.read[Command](input)).toOption).unlift

  class CommandHandler(
    send: Event => IO[Unit],
    getTorrent: InfoHash => IO[Torrent[IO]]
  )(
    implicit
    F: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ) {
    def handle(command: Command): IO[Unit] = command match {
      case Command.GetTorrent(infoHashString @ InfoHashFromString(infoHash)) =>
        for {
          _ <- send(Event.RequestAccepted(infoHashString))
          _ <- (
            handleGetTorrent(infoHash) >>=
            (sentTorrentStats(infoHashString, _))
          ).start
        } yield ()
    }

    private def handleGetTorrent(infoHash: InfoHash): IO[Torrent[IO]] =
      for {
        torrent <- getTorrent(infoHash)
        files = torrent.getMetaInfo.parsed.files.map(f => Event.File(f.path, f.length))
        _ <- send(Event.TorrentMetadataReceived(files))
      } yield torrent

    private def sentTorrentStats(infoHash: String, torrent: Torrent[IO]): IO[Unit] =
      Stream
        .repeatEval(
          (timer.sleep(2.seconds) >> torrent.stats).flatMap { stats =>
            send(Event.TorrentStats(infoHash, stats.connected))
          }
        )
        .compile
        .drain
  }

  object CommandHandler {
    def apply(
      send: Event => IO[Unit],
      makeTorrent: InfoHash => Resource[IO, IO[Torrent[IO]]]
    )(
      implicit
      F: Concurrent[IO],
      cs: ContextShift[IO],
      timer: Timer[IO],
      logger: LogIO[IO]
    ): Resource[IO, CommandHandler] = Resource {
      for {
        torrentRef <- Ref.of(Option.empty[OpenTorrent])
      } yield {
        val closeCurrentTorrent = torrentRef
          .getAndSet(none)
          .flatMap { opneTorrent =>
            opneTorrent.traverse_(_.close)
          }
        val impl = new CommandHandler(
          send,
          closeCurrentTorrent >>
          makeTorrent(_).allocated.flatMap {
            case (torrent, close) =>
              torrentRef.set(OpenTorrent(torrent, close).some) >> torrent
          }
        )
        (impl, closeCurrentTorrent)
      }
    }
    case class OpenTorrent(torrent: IO[Torrent[IO]], close: IO[Unit])
  }
}
