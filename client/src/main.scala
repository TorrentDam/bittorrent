import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom
import slinky.web.ReactDOM
import cats.implicits._
import cats.effect.IOApp
import cats.effect.IO
import cats.effect.ExitCode
import cats.effect.concurrent.MVar
import component.{App, Router}
import logic.{Action, Circuit}

class Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
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

object Main {
  @JSExportTopLevel("main")
  def main() = (new Main).main(Array.empty)
}
