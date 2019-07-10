import slinky.web.ReactDOM
import slinky.hot
import slinky.web.html._
import slinky.core.CustomAttribute

import org.scalajs.dom
import scala.scalajs.js

import react_md._
import app.App

object Main {

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("root")
    val app =
      div(
        Toolbar(
          title = "BitTorrent",
          themed = true,
          actions = (MenuButton("main-menu", Seq("Settings", "Help", "Feedback"))("more_vert"): js.Object)
        ),
        Grid()(
          Cell(size = 3)(
            Toolbar(
              actions = Button(flat = true, primary = true)("Download"): js.Object,
            )(
              TextField(id = "magnet-link-download", label = "Magnet link"),
            )
          )
        )
      )
    ReactDOM.render(app, container)
  }
}
