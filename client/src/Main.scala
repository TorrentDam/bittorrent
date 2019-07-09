import slinky.web.ReactDOM
import slinky.hot

import org.scalajs.dom

import react_md._
import app.App

object Main {

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("root")
    val app =
      Toolbar(
        title = "BitTorrent",
        themed = true,
        actions = MenuButton("main-menu", Seq("Settings", "Help", "Feedback"))("more_vert")
      )
    ReactDOM.render(app, container)
  }
}
