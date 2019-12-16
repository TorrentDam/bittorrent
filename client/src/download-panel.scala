import slinky.core.annotations.react
import slinky.core.FunctionalComponent
import slinky.web.html._
import material_ui.core._
import material_ui.styles.makeStyles

import scala.scalajs.js.Dynamic
import slinky.core.facade.Hooks

@react
object DownloadPanel {
  case class Props(model: TorrentPanelModel, dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
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
  }
}
