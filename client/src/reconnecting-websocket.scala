import org.scalajs.dom.raw.WebSocket
import org.scalajs.dom.console

import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import fs2.concurrent.Topic
import fs2.Pipe
import cats.effect.Timer

import scala.concurrent.duration._
import fs2.concurrent.Queue

case class ReconnectingWebsocket(
  status: Topic[IO, Boolean]
)

object ReconnectingWebsocket {

  def create(
    url: String,
    service: Pipe[IO, String, String]
  )(implicit cs: ContextShift[IO], timer: Timer[IO]): IO[ReconnectingWebsocket] =
    for {
      statusTopic <- Topic[IO, Boolean](false)
      incoming <- Queue.unbounded[IO, String]
      continuallyConnect = connectWithRetries(url).flatMap { socket =>
        for {
          _ <- statusTopic.publish1(true)
          _ <- IO {
            socket.onmessage = { msg =>
              incoming.enqueue1(msg.data.toString()).unsafeRunSync()
            }
          }
          _ <- service(incoming.dequeue)
            .evalTap { out =>
              IO { socket.send(out) }
            }
            .compile
            .drain
          _ <- IO.async[Unit] { cont =>
            socket.onerror = { _ =>
              cont(().asRight)
            }
          }
          _ <- statusTopic.publish1(false)
        } yield ()
      }.foreverM
      _ <- continuallyConnect.start
    } yield ReconnectingWebsocket(statusTopic)

  private def connectWithRetries(url: String)(implicit timer: Timer[IO]): IO[WebSocket] = {
    connect(url).handleErrorWith {
      case ConnectionError() =>
        timer.sleep(10.seconds) >> connectWithRetries(url)
    }
  }

  private def connect(url: String): IO[WebSocket] = IO.async { cont =>
    console.info(s"Connecting to $url")
    val websocket = new WebSocket(url)
    websocket.onopen = { _ =>
      console.info(s"Connected to $url")
      cont(websocket.asRight)
    }
    websocket.onerror = { _ =>
      console.info(s"Failed to connect to $url")
      cont(ConnectionError().asLeft)
    }
  }

  case class ConnectionError() extends Throwable
}
