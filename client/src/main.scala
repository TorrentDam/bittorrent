import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom
import slinky.web.ReactDOM
import cats.implicits._
import cats.effect.{ContextShift, ExitCode, IO, IOApp, Timer}
import cats.effect.concurrent.MVar
import component.{App, Router}
import logic.{Action, Circuit}

import scala.concurrent.ExecutionContext

object Main {

  implicit val executionContext: ExecutionContext = ExecutionContext.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  @JSExportTopLevel("main")
  def main(): Unit = mainIO.void.unsafeRunSync()

  def mainIO: IO[ExitCode] = {
    for {
      out <- MVar.empty[IO, String]
      circuit <- IO { Circuit(out.put(_).unsafeRunSync()) }
      socket <- ReconnectingSocket.create(
        environment.wsUrl("/ws"),
        msg =>
          IO { org.scalajs.dom.console.info(s"WS << $msg") } >>
          IO { circuit.dispatcher(Action.ServerEvent(msg)) },
        connected => IO { circuit.dispatcher(Action.UpdateConnectionStatus(connected)) }
      )
      _ <- out.take
        .flatMap { msg =>
          IO { org.scalajs.dom.console.info(s"WS >> $msg") } >>
          socket.send(msg)
        }
        .foreverM
        .start
      router <- IO { Router() }
      _ <- IO {
        circuit.dispatcher(Action.Navigate(router.current))
        router.onNavigate(route => circuit.dispatcher(Action.Navigate(route)))
      }
      _ <- IO {
        ReactDOM.render(
          App(router, circuit.observed, circuit.dispatcher),
          dom.document.getElementById("root")
        )
      }
    } yield ExitCode.Success
  }

}
