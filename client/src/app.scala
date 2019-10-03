import slinky.core.annotations.react
import slinky.core.FunctionalComponent
import slinky.web.html._

import material_ui.core._
import material_ui.icons.{Menu => MenuIcon}
import material_ui.styles.makeStyles

import scala.scalajs.js.Dynamic

@react
object App {
  case class Props()

  private val useStyles = makeStyles( theme =>
    Dynamic.literal(
      appBarSpacer = theme.mixins.toolbar
    )
  )

  val component = FunctionalComponent[Unit] { _ =>
    val classes = useStyles()
    div(
      AppBar(position = "absolute")(
        Toolbar(
          IconButton(edge = "start")(MenuIcon()),
          Typography(component = "h1")("BitTorrent")
        )
      ),
      div(className := classes.appBarSpacer.toString),
      DownloadPanel()
    )
  }
}