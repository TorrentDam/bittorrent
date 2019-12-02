import diode.Dispatcher
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.web.html._

@react
object TorrentPanel {
  case class Props(model: TorrentModel, dispatcher: Dispatcher)

  val component = FunctionalComponent[Props] { props =>
    div(
      s"Connected: ${props.model.connected}"
    )

  }
}
