package component
import logic.{Action, Dispatcher, TorrentPanelModel}
import material_ui.core._
import material_ui.styles.makeStyles
import scodec.bits.ByteVector
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.Hooks
import slinky.web.html._

import scala.scalajs.js.Dynamic

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
    val isInfoHash = ByteVector.fromHex(value).exists(_.size == 20)
    Paper(className = classes.root.toString)(
      InputBase(
        placeholder = "Info hash",
        value = value,
        onChange = event => setState(event.target.value.asInstanceOf[String]),
        className = classes.input.toString
      ),
      Button(variant = "contained", disabled = !isInfoHash)(onClick := handleClick _)(
        "Download"
      )
    )
  }
}
