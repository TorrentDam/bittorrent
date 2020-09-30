import org.scalajs.dom.raw.WebSocket
import org.scalajs.dom.console
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import cats.effect.Timer
import cats.effect.concurrent.{Deferred, MVar}

import scala.concurrent.duration._

case class Socket(
  send: String => IO[Unit],
  receive: IO[String],
  closed: IO[Option[Socket.ConnectionInterrupted]]
)
object Socket {

  def connect(url: String)(implicit cs: ContextShift[IO]): IO[Socket] =
    for {
      websocket <- IO.async[WebSocket] { cont =>
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
      onClose <- Deferred[IO, Option[ConnectionInterrupted]]
      onDisconnected = { () =>
        console.info(s"Disconnected from $url")
        onClose.complete(ConnectionInterrupted().some).unsafeRunSync()
      }
      channel <- MVar.empty[IO, String]
      _ <- IO.delay {
        websocket.onclose = (_) => onDisconnected()
        websocket.onerror = (_) => onDisconnected()
        websocket.onmessage = { msg =>
          channel.put(msg.data.toString()).unsafeRunSync()
        }
      }
    } yield Socket(
      send = data => IO { websocket.send(data) },
      receive = channel.take,
      closed = onClose.get
    )

  case class ConnectionError() extends Throwable
  case class ConnectionInterrupted() extends Throwable
}

case class ReconnectingSocket(
  send: String => IO[Unit]
)

object ReconnectingSocket {

  def create(
    url: String,
    service: String => IO[Unit],
    onStatusChanged: Boolean => IO[Unit]
  )(implicit cs: ContextShift[IO], timer: Timer[IO]): IO[ReconnectingSocket] = {
    for {
      out <- MVar.empty[IO, String]
      doConnect = connectWithRetries(url).flatMap { socket =>
        for {
          _ <- onStatusChanged(true)
          r <- socket.receive.flatMap(service).foreverM.start
          s <- out.take.flatMap(socket.send).foreverM.start
          _ <- socket.closed
          _ <- r.cancel *> s.cancel
          _ <- onStatusChanged(false)
        } yield ()
      }
      continuallyConnect = doConnect.foreverM
      _ <- continuallyConnect.start
    } yield ReconnectingSocket(out.put)
  }

  private def connectWithRetries(
    url: String
  )(implicit cs: ContextShift[IO], timer: Timer[IO]): IO[Socket] = {
    Socket.connect(url).handleErrorWith {
      case Socket.ConnectionError() =>
        timer.sleep(10.seconds) >> connectWithRetries(url)
    }
  }
}
