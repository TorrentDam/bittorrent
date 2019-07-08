import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSImport}
import scala.scalajs.LinkingInfo

import slinky.core._
import slinky.core.annotations.react
import slinky.web.ReactDOM
import slinky.web.html._
import slinky.hot

import org.scalajs.dom

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

@js.native
@JSImport("react-md", JSImport.Default)
object ReactMD extends js.Object {
  val Toolbar: js.Object = js.native
  val MenuButton: js.Object = js.native
}

@react object Toolbar extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(title: String, themed: Boolean, actions: js.Object)
  val component = ReactMD.Toolbar
}

@react object MenuButton extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(id: String, menuItems: Seq[String], icon: Option[Boolean] = Some(true))
  val component = ReactMD.MenuButton
}
