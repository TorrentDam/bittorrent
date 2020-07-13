
import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom
import slinky.web.ReactDOM
import cats.implicits._
import cats.effect.{ContextShift, ExitCode, IO, Timer}
import cats.effect.concurrent.MVar
import com.github.lavrov.bittorrent.app.protocol.Command
import component.{App, Router}
import logic.{Action, Dispatcher, Handler, RootModel}
import monix.reactive.subjects.Var
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import component.Connect
import slinky.web.svg.mode
import logic.WindowTitle

object Main {

  implicit val executionContext: ExecutionContext = ExecutionContext.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  @JSExportTopLevel("main")
  def main(): Unit = mainIO.void.unsafeRunSync()

  def mainIO: IO[ExitCode] = {
    for {
      out <- MVar.empty[IO, String]
      model <- IO { Var(RootModel.initial) }
      dispatcher <- IO {
        def send(command: Command): Unit = {
          val str = upickle.default.write(command)
          out.put(str).unsafeRunSync()
        }
        lazy val dispatcher: Dispatcher = {
          val handler = Handler(send, action => dispatcher(action))
          Dispatcher(handler, model)
        }
        dispatcher
      }
      socket <- ReconnectingSocket.create(
        environment.wsUrl("/ws"),
        msg =>
          IO { org.scalajs.dom.console.info(s"WS << $msg") } >>
          IO { dispatcher(Action.ServerEvent(msg)) },
        connected => IO { dispatcher(Action.UpdateConnectionStatus(connected)) }
      )
      _ <-
        out.take
          .flatMap { msg =>
            IO { org.scalajs.dom.console.info(s"WS >> $msg") } >>
            socket.send(msg)
          }
          .foreverM
          .start
      router <- IO { Router() }
      _ <- IO {
        dispatcher(Action.Navigate(router.current))
        router.onNavigate(route => dispatcher(Action.Navigate(route)))
      }
      _ <- model.map(WindowTitle.fromModel).foreachL { dom.document.title = _ }.to[IO].start
      _ <- IO {
        ReactDOM.render(
          Connect(model)(model => App(router, model, dispatcher)),
          dom.document.getElementById("root")
        )
      }
    } yield ExitCode.Success
  }

}
