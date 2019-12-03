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
  case class Props(circuit: AppCircuit)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        appBarSpacer = theme.mixins.toolbar
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    div(
      AppBar(position = "absolute")(
        Toolbar(
          IconButton(edge = "start")(MenuIcon()),
          Typography(component = "h1")("BitTorrent")
        )
      ),
      div(className := classes.appBarSpacer.toString),
      main(
        Connect(props.circuit, _.torrentPanel)(
          (model, dispatcher) =>
            model.torrent match {
              case Some(torrent) => TorrentPanel(torrent, dispatcher)
              case _ => DownloadPanel(model, dispatcher)
            }
        ),
        Connect(props.circuit, _.logs)(LogPanel.apply)
      )
    )
  }
}
