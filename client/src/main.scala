import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom
import slinky.web.ReactDOM
import cats.effect.IOApp
import cats.effect.IO
import cats.effect.ExitCode
import fs2.concurrent.Queue
import component.{App, Router}
import logic.{Action, Circuit}

class Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    for {
      out <- Queue.unbounded[IO, String]
      circuit <- IO { Circuit(out.enqueue1(_).unsafeRunSync()) }
      socket <- ReconnectingSocket.create(
        environment.wsUrl("/ws"),
        in =>
          in.evalTap { msg =>
              for {
                _ <- IO { org.scalajs.dom.console.info(s"WS << $msg") }
                _ <- IO { circuit.dispatcher(Action.ServerEvent(msg)) }
              } yield ()
            }
            .drain
            .merge(out.dequeue)
            .evalTap { msg =>
              IO { org.scalajs.dom.console.info(s"WS >> $msg") }
            }
      )
      _ <- socket.status
        .subscribe(1)
        .evalTap { connected =>
          IO { circuit.dispatcher(Action.UpdateConnectionStatus(connected)) }
        }
        .compile
        .drain
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
