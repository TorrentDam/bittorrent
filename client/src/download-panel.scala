import slinky.core.annotations.react
import slinky.core.FunctionalComponent
import slinky.web.html._
import material_ui.core._
import material_ui.styles.makeStyles

import scala.scalajs.js.Dynamic
import diode.Dispatcher
import slinky.core.facade.Hooks

@react
object DownloadPanel {
  case class Props(model: TorrentPanelModel, dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        container = Dynamic.literal(
          paddingTop = theme.spacing(4),
          paddingBottom = theme.spacing(4)
        ),
        root = Dynamic.literal(
          padding = theme.spacing(2),
          display = "flex",
          alignItems = "center"
        ),
        input = Dynamic.literal(
          marginLeft = theme.spacing(1),
          marginRight = theme.spacing(1),
          flex = 1
        )
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    val (value, setState) = Hooks.useState("")
    def handleClick(): Unit = {
      props.dispatcher(Action.DownloadTorrentFile(value))
      setState("")
    }
    Container(maxWidth = "md", className = classes.container.toString)(
      Paper(className = classes.root.toString)(
        InputBase(
          placeholder = "Info hash",
          value = value,
          onChange = event => setState(event.target.value.asInstanceOf[String]),
          className = classes.input.toString
        ),
        Button(variant = "contained", disabled = value.isEmpty)(onClick := handleClick _)(
          "Download"
        )
      )
    )
  }
}
