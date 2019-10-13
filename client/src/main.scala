import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom

import slinky.web.ReactDOM

import cats.effect.IOApp
import cats.effect.IO
import cats.effect.ExitCode

class Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = for {
    ws <- ReconnectingWebsocket.create("wss://echo.websocket.org")
    _ <- IO { ReactDOM.render(App(), dom.document.getElementById("root")) }
    fiber <- ws.status.subscribe(1)
      .evalTap { status =>
        IO { println(s"Status $status") }
      }
      .compile.drain.start
  }
  yield ExitCode.Success

}

object Main {
  @JSExportTopLevel("main")
  def main() = (new Main).main(Array.empty)
}