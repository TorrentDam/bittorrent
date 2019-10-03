import slinky.web.ReactDOM

import org.scalajs.dom

object Main {

  def main(args: Array[String]): Unit = {
    ReactDOM.render(App(), dom.document.getElementById("root"))
  }

}
