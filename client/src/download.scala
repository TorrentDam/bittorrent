import slinky.core.annotations.react
import slinky.core.facade.Hooks
import slinky.core.FunctionalComponent
import slinky.web.html._

import material_ui.core._
import material_ui.styles.makeStyles

import scala.scalajs.js.Dynamic
import diode.FastEq
import slinky.core.facade.ReactElement
import diode.Dispatcher
import slinky.core.KeyAddingStage

@react
object DownloadPanel {
  case class Props(model: DownloadPanelModel, dispatcher: Dispatcher)

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
    def handleClick() = props.dispatcher(Action.DownloadTorrentFile("download"))
    main(
      Container(maxWidth = "md", className = classes.container.toString)(
        Paper(className = classes.root.toString)(
          InputBase(
            placeholder = "Info hash",
            disabled = props.model.downloading,
            className = classes.input.toString
          ),
          Button(variant = "contained")(onClick := handleClick _)(
            if (props.model.downloading) "Cancel" else "Download"
          )
        ),
        LinearProgress()(hidden := !props.model.downloading)
      )
    )
  }
}
