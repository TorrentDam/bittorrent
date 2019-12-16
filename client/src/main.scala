import Action.ServerEvent
import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom
import slinky.web.ReactDOM
import cats.effect.IOApp
import cats.effect.IO
import cats.effect.ExitCode
import fs2.concurrent.Queue

import Environment.backendAddress

class Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    for {
      out <- Queue.unbounded[IO, String]
      circuit <- IO { AppCircuit(out.enqueue1(_).unsafeRunSync()) }
      socket <- ReconnectingWebsocket.create(
        s"wss://$backendAddress/ws",
        in =>
          in.evalTap { msg =>
              for {
                _ <- IO { org.scalajs.dom.console.info(s"WS << $msg") }
                _ <- IO { circuit.dispatcher(ServerEvent(msg)) }
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
      _ <- IO {
        ReactDOM.render(
          App(circuit.observed, circuit.dispatcher),
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
