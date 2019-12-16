import org.scalajs.dom.raw.WebSocket
import org.scalajs.dom.console
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import fs2.concurrent.Topic
import fs2.Pipe
import cats.effect.Timer
import cats.effect.concurrent.Deferred

import scala.concurrent.duration._
import fs2.concurrent.Queue
import fs2.Stream

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
      continuallyConnect = connectWithRetries(url).flatMap { socket =>
        for {
          _ <- statusTopic.publish1(true)
          _ <- service(socket.receive)
            .evalTap { out =>
              socket.send(out)
            }
            .interruptWhen[IO](socket.closed as Right(()))
            .compile
            .drain
          _ <- statusTopic.publish1(false)
        } yield ()
      }.foreverM
      _ <- continuallyConnect.start
    } yield ReconnectingWebsocket(statusTopic)

  private def connectWithRetries(
    url: String
  )(implicit cs: ContextShift[IO], timer: Timer[IO]): IO[Socket] = {
    connect(url).handleErrorWith {
      case ConnectionError() =>
        timer.sleep(10.seconds) >> connectWithRetries(url)
    }
  }

  private def connect(url: String)(implicit cs: ContextShift[IO]): IO[Socket] =
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
      queue <- Queue.noneTerminated[IO, String]
      _ <- IO.delay {
        websocket.onerror = { _ =>
          console.info(s"Disconnected from $url")
          onClose.complete(ConnectionInterrupted().some).unsafeRunSync()
          queue.enqueue1(none)
        }
        websocket.onmessage = { msg =>
          queue.enqueue1(msg.data.toString().some).unsafeRunSync()
        }
      }
    } yield Socket(
      send = data => IO { websocket.send(data) },
      receive = queue.dequeue,
      closed = onClose.get
    )

  case class Socket(
    send: String => IO[Unit],
    receive: Stream[IO, String],
    closed: IO[Option[ConnectionInterrupted]]
  )

  case class ConnectionError() extends Throwable
  case class ConnectionInterrupted() extends Throwable
}
