import slinky.web.ReactDOM
import slinky.hot
import slinky.web.html._
import slinky.core.CustomAttribute

import org.scalajs.dom
import scala.scalajs.js
import js.Dynamic
import app.App
import material_ui.core._
import material_ui.icons._
import material_ui.styles.makeStyles
import slinky.core.FunctionalComponent

object Main {

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("root")
    ReactDOM.render(appComponent(), container)
  }

  val useStyles = makeStyles( theme =>
    Dynamic.literal(
      container = Dynamic.literal(
        paddingTop = theme.spacing(8),
        paddingBottom = theme.spacing(8),
      )
    )
  )

  val appComponent = FunctionalComponent[Unit] { _ =>
    val classes = useStyles()
    div(
      AppBar(position = "absolute")(
        Toolbar()(
          IconButton(edge = "start")(Menu()),
          Typography(component = "h1")("BitTorrent")
        )
      ),
      slinky.web.html.main(
        Container(maxWidth = "lg")(
          className := classes.container.toString,
          form(
            TextField(id = "info-hash", name = "info-hash", "Info hash"),
            Button(variant = "contained")(
              "Download"
            )
          )
        )
      )
    )

  }
}
