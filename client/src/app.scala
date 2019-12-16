import slinky.core.annotations.react
import slinky.core.FunctionalComponent
import slinky.web.html._

import material_ui.core._
import material_ui.icons.{Menu => MenuIcon}
import material_ui.styles.makeStyles

import scala.scalajs.js.Dynamic
import slinky.web.svg.circle

@react
object App {
  case class Props(model: Observed[RootModel], dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        appBarSpacer = theme.mixins.toolbar,
        container = Dynamic.literal(
          paddingTop = theme.spacing(4),
          paddingBottom = theme.spacing(4)
        ),
        centered = Dynamic.literal(
          textAlign = "center"
        )
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    div(
      div(className := classes.appBarSpacer.toString),
      main(
        Container(maxWidth = "md", className = classes.container.toString)(
          Connect(props.model.zoomTo(_.connected), props.dispatcher) {
            case (true, _) =>
              Connect(props.model.zoomTo(_.torrentPanel), props.dispatcher)(
                (model, dispatcher) =>
                  model.torrent match {
                    case Some(torrent) => TorrentPanel(torrent, dispatcher)
                    case _ => DownloadPanel(model, dispatcher)
                  }
              )
            case _ =>
              p(className := classes.centered.toString)("Connecting...")
          }
        )
      )
    )
  }
}
