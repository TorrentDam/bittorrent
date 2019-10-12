import scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom

import slinky.web.ReactDOM

object Main {

  @JSExportTopLevel("main")
  def main(): Unit = {
    ReactDOM.render(App(), dom.document.getElementById("root"))
  }

}
