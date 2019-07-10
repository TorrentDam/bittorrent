package react_md

import slinky.core._
import slinky.core.annotations.react
import slinky.web.html._

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSImport}
import scala.scalajs.js.UndefOr

@js.native
@JSImport("react-md", JSImport.Default)
private object imports extends js.Object {
  val Toolbar: js.Object = js.native
  val MenuButton: js.Object = js.native
  val TextField: js.Object = js.native
  val Paper: js.Object = js.native
  val Grid: js.Object = js.native
  val Cell: js.Object = js.native
  val Button: js.Object = js.native
}

@react object Toolbar extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(
    title: UndefOr[String] = js.undefined, 
    themed: Boolean = false,
    actions: UndefOr[js.Object] = js.undefined
  )
  val component = imports.Toolbar
}

@react object MenuButton extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(id: String, menuItems: Seq[String], icon: Option[Boolean] = Some(true))
  val component = imports.MenuButton
}

@react object TextField extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(id: String, label: String, toolbar: Boolean = false)
  val component = imports.TextField
}

@react object Paper extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(zDepth: Int)
  val component = imports.Paper
}

@react object Grid extends ExternalComponentWithAttributes[*.tag.type] {
  type Props = Unit
  val component = imports.Grid
}

@react object Cell extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(size: Int)
  val component = imports.Cell
}
@react object Button extends ExternalComponentWithAttributes[*.tag.type] {
  case class Props(flat: Boolean, primary: Boolean, swapTheming: UndefOr[Boolean] = js.undefined)
  val component = imports.Button
}