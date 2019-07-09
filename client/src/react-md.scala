package react_md

import slinky.core._
import slinky.core.annotations.react
import slinky.web.html._

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSImport}

@js.native
@JSImport("react-md", JSImport.Default)
private object imports extends js.Object {
  val Toolbar: js.Object = js.native
  val MenuButton: js.Object = js.native
}

@react object Toolbar extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(title: String, themed: Boolean, actions: js.Object)
  val component = imports.Toolbar
}

@react object MenuButton extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(id: String, menuItems: Seq[String], icon: Option[Boolean] = Some(true))
  val component = imports.MenuButton
}