import org.scalajs.dom.raw.WebSocket

import cats.effect.{IO, ContextShift}
import cats.syntax.all._
import fs2.concurrent.Topic
import cats.effect.Timer

import scala.concurrent.duration._

case class ReconnectingWebsocket(
  status: Topic[IO, Boolean]
)

object ReconnectingWebsocket {

    def create(url: String)(implicit cs: ContextShift[IO], timer: Timer[IO]): IO[ReconnectingWebsocket] =
      for {
        statusTopic <- Topic[IO, Boolean](false)
        continuallyConnect = connectWithRetries(url)
          .flatMap { socket =>
            for {
              _ <- statusTopic.publish1(true)
              _ <- IO.async[Unit] { cont =>
                socket.onerror = { _ =>
                  cont(().asRight)
                }
              }
              _ <- statusTopic.publish1(false)
            }
            yield ()
          }
          .foreverM
        _ <- continuallyConnect.start
      }
      yield ReconnectingWebsocket(statusTopic)

    private def connectWithRetries(url: String)(implicit timer: Timer[IO]): IO[WebSocket] = {
      connect(url).handleErrorWith {
        case ConnectionError() =>
          timer.sleep(10.seconds) >> connectWithRetries(url)
      }
    }

    private def connect(url: String): IO[WebSocket] = IO.async { cont =>
      println(s"Connecting to $url")
      val websocket = new WebSocket(url)
      websocket.onopen = { _ =>
        cont(websocket.asRight)
      }
      websocket.onerror = { _ =>
        cont(ConnectionError().asLeft)
      }
    }

    case class ConnectionError() extends Throwable
}