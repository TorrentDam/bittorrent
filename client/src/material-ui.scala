package material_ui

import slinky.core._
import slinky.core.annotations.react
import slinky.web.html._

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSImport}
import scala.scalajs.js.UndefOr


package core {
    import scala.scalajs.js.|
    @js.native
    @JSImport("@material-ui/core", JSImport.Default)
    private object imports extends js.Object {
      val AppBar: js.Object = js.native
      val Button: js.Object = js.native
      val Toolbar: js.Object = js.native
      val IconButton: js.Object = js.native
      val Typography: js.Object = js.native
      val Container: js.Object = js.native
      val TextField: js.Object = js.native
      val Paper: js.Object = js.native
      val InputBase: js.Object = js.native
      val CssBaseline: js.Object = js.native
    }

    @react 
    object AppBar extends ExternalComponentWithAttributes[*.tag.type] {
      case class Props(position: String)
      val component = imports.AppBar
    }

    @react 
    object Button extends ExternalComponent {
      case class Props(variant: String, color: String = "primary")
      val component = imports.Button
    }

    object Toolbar extends ExternalComponentNoProps {
      val component = imports.Toolbar
    }

    @react 
    object IconButton extends ExternalComponent {
      case class Props(
        edge: String,
        color: String = "inherit",
        `aria-label`: String = "open drawer",
      )
      val component = imports.IconButton
    }

    @react 
    object Typography extends ExternalComponent {
      case class Props(
        component: String,
        variant: String = "h6",
        color: String = "inherit",
      )
      val component = imports.Typography
    }

    @react 
    object Container extends ExternalComponent {
      case class Props(
        maxWidth: String,
        className: UndefOr[String] = js.undefined
      )
      val component = imports.Container
    }

    @react
    object TextField extends ExternalComponent {
      case class Props(
        id: String,
        name: String,
        label: String
      )
      val component = imports.TextField
    }

    @react
    object Paper extends ExternalComponent {
      case class Props(
        className: UndefOr[String] = js.undefined
      )
      val component = imports.Paper
    }

    @react
    object InputBase extends ExternalComponent {
      case class Props(
        placeholder: String,
        className: UndefOr[String] = js.undefined
      )
      val component = imports.InputBase
    }

    object CssBaseline extends ExternalComponentNoProps {
      val component = imports.CssBaseline
    }

}

package icons {
  @js.native
  @JSImport("@material-ui/icons", JSImport.Default)
  private object imports extends js.Object {
    val Menu: js.Object = js.native
  }

  object Menu extends ExternalComponentNoProps {
    val component = imports.Menu
  }
}

package object styles {
  @js.native
  @JSImport("@material-ui/core/styles", JSImport.Default)
  private object imports extends js.Object {
    def makeStyles(f: js.Function1[js.Dynamic, js.Dynamic]): js.Dynamic = js.native
  }

  def makeStyles(f: js.Function1[js.Dynamic, js.Dynamic]): js.Dynamic = imports.makeStyles(f)

}