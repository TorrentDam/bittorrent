import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, ContextShift, IO, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
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
    makeTorrent: InfoHash => Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]],
    metadataRegistry: MetadataRegistry[IO],
    torrentIndex: TorrentIndex
  )(implicit
    F: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ): IO[Response[IO]] =
    for {
      _ <- logger.info("Session started")
      input <- Queue.unbounded[IO, WebSocketFrame]
      output <- Queue.unbounded[IO, WebSocketFrame]
      send = (e: Event) => output.enqueue1(WebSocketFrame.Text(upickle.default.write(e)))
      handlerAndClose <- CommandHandler(send, makeTorrent, metadataRegistry, torrentIndex).allocated
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
          _ <- logger.debug(s"Received $command")
          _ <- commandHandler.handle(command)
        } yield ()
      case _ => IO.unit
    }
  }

  private val Cmd: PartialFunction[String, Command] =
    ((input: String) => Try(upickle.default.read[Command](input)).toOption).unlift

  class CommandHandler(
    send: Event => IO[Unit],
    getTorrent: InfoHash => Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]],
    metadataRegistry: MetadataRegistry[IO],
    torrentIndex: TorrentIndex,
    closed: IO[Unit]
  )(implicit
    F: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    logger: LogIO[IO]
  ) {
    def handle(command: Command): IO[Unit] =
      command match {
        case Command.GetTorrent(infoHash) =>
          for {
            _ <- send(Event.RequestAccepted(infoHash))
            _ <- handleGetTorrent(InfoHash(infoHash.bytes))
          } yield ()

        case Command.GetDiscovered() =>
          for {
            torrents <- metadataRegistry.recent
            torrents <-
              torrents
                .map {
                  case (infoHash, metadata) => (infoHash, metadata.parsed.name)
                }
                .pure[IO]
            _ <- send(Event.Discovered(torrents))
            _ <-
              metadataRegistry.subscribe.chunks
                .evalTap { chunk =>
                  val event =
                    Event.Discovered(
                      chunk.toIterable
                        .map {
                          case (infoHash, metadata) => (infoHash, metadata.parsed.name)
                        }
                    )
                  send(event)
                }
                .interruptWhen(closed.attempt)
                .compile
                .drain
                .start
          } yield {}

        case Command.Search(query) =>
          torrentIndex
            .byName(query)
            .flatMap { entries =>
              val result = entries.map(e => Event.SearchResults.Entry(e.name, InfoHash.fromString(e.infoHash), e.size))
              send(Event.SearchResults(query, result))
            }
      }

    private def handleGetTorrent(infoHash: InfoHash): IO[Unit] =
      F.uncancelable {
        getTorrent(infoHash)
          .use { getTorrent =>
            getTorrent
              .flatMap { phase =>
                phase.done
              }
              .flatMap { phase =>
                phase.fromPeers.discrete
                  .evalTap { count =>
                    send(Event.TorrentPeersDiscovered(infoHash, count))
                  }
                  .interruptWhen(phase.done.void.attempt)
                  .compile
                  .drain >>
                phase.done
              }
              .flatMap { phase =>
                val metadata = phase.serverTorrent.metadata.parsed
                val files = metadata.files.map(f => Event.File(f.path, f.length))
                send(Event.TorrentMetadataReceived(infoHash, metadata.name, files)) >>
                phase.serverTorrent.pure[IO]
              }
              .timeout(1.minute)
              .flatMap { torrent =>
                sendTorrentStats(infoHash, torrent) >>
                IO.never
              }
          }
          .orElse {
            send(Event.TorrentError(infoHash, "Could not fetch metadata"))
          }
          .start
          .flatMap(fiber => (closed >> fiber.cancel).start)
          .void
      }

    private def sendTorrentStats(infoHash: InfoHash, torrent: ServerTorrent): IO[Unit] = {

      val sendStats =
        for {
          stats <- torrent.stats
          _ <- send(Event.TorrentStats(infoHash, stats.connected, stats.availability))
          _ <- timer.sleep(5.seconds)
        } yield ()

      sendStats.foreverM
    }
  }

  object CommandHandler {
    def apply(
      send: Event => IO[Unit],
      makeTorrent: InfoHash => Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]],
      metadataRegistry: MetadataRegistry[IO],
      torrentIndex: TorrentIndex
    )(implicit
      F: Concurrent[IO],
      cs: ContextShift[IO],
      timer: Timer[IO],
      logger: LogIO[IO]
    ): Resource[IO, CommandHandler] =
      Resource {
        for {
          closed <- Deferred[IO, Unit]
        } yield {
          val impl = new CommandHandler(
            send,
            makeTorrent,
            metadataRegistry,
            torrentIndex,
            closed.get
          )
          (impl, closed.complete(()))
        }
      }
  }
}
