import slinky.web.ReactDOM

import org.scalajs.dom

object Main {

  @scalajs.js.annotation.JSExportTopLevel("main")
  def main(): Unit = {
    ReactDOM.render(App(), dom.document.getElementById("root"))
  }

}
