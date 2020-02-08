package component.material_ui

import slinky.core._
import slinky.core.annotations.react
import slinky.web.html._

import scala.scalajs.js
import scala.scalajs.js.UndefOr
import scala.scalajs.js.annotation.JSImport

package core {
  import slinky.core.facade.ReactElement
  private object imports {
    @js.native @JSImport("@material-ui/core/AppBar", JSImport.Default)
    object AppBar extends js.Object
    @js.native @JSImport("@material-ui/core/Avatar", JSImport.Default)
    object Avatar extends js.Object
    @js.native @JSImport("@material-ui/core/Button", JSImport.Default)
    object Button extends js.Object
    @js.native @JSImport("@material-ui/core/Breadcrumbs", JSImport.Default)
    object Breadcrumbs extends js.Object
    @js.native @JSImport("@material-ui/core/Toolbar", JSImport.Default)
    object Toolbar extends js.Object
    @js.native @JSImport("@material-ui/core/IconButton", JSImport.Default)
    object IconButton extends js.Object
    @js.native @JSImport("@material-ui/core/Typography", JSImport.Default)
    object Typography extends js.Object
    @js.native @JSImport("@material-ui/core/Container", JSImport.Default)
    object Container extends js.Object
    @js.native @JSImport("@material-ui/core/TextField", JSImport.Default)
    object TextField extends js.Object
    @js.native @JSImport("@material-ui/core/Paper", JSImport.Default)
    object Paper extends js.Object
    @js.native @JSImport("@material-ui/core/InputBase", JSImport.Default)
    object InputBase extends js.Object
    @js.native @JSImport("@material-ui/core/Link", JSImport.Default)
    object Link extends js.Object
    @js.native @JSImport("@material-ui/core/LinearProgress", JSImport.Default)
    object LinearProgress extends js.Object
    @js.native @JSImport("@material-ui/core/List", JSImport.Default)
    object List extends js.Object
    @js.native @JSImport("@material-ui/core/ListSubheader", JSImport.Default)
    object ListSubheader extends js.Object
    @js.native @JSImport("@material-ui/core/ListItem", JSImport.Default)
    object ListItem extends js.Object
    @js.native @JSImport("@material-ui/core/ListItemAvatar", JSImport.Default)
    object ListItemAvatar extends js.Object
    @js.native @JSImport("@material-ui/core/ListItemText", JSImport.Default)
    object ListItemText extends js.Object
    @js.native @JSImport("@material-ui/core/ListItemSecondaryAction", JSImport.Default)
    object ListItemSecondaryAction extends js.Object
    @js.native @JSImport("@material-ui/core/Divider", JSImport.Default)
    object Divider extends js.Object
    @js.native @JSImport("@material-ui/core/CssBaseline", JSImport.Default)
    object CssBaseline extends js.Object
  }

  @react
  object AppBar extends ExternalComponentWithAttributes[*.tag.type] {
    case class Props(position: String, color: UndefOr[String] = js.undefined, className: UndefOr[String] = js.undefined)
    val component = imports.AppBar
  }

  @react
  object Button extends ExternalComponent {
    case class Props(
      variant: String,
      color: String = "primary",
      disabled: UndefOr[Boolean] = js.undefined
    )
    val component = imports.Button
  }

  @react
  object Breadcrumbs extends ExternalComponent {
    case class Props(className: UndefOr[String] = js.undefined)
    val component = imports.Breadcrumbs
  }

  @react
  object Toolbar extends ExternalComponent {
    case class Props(disableGutters: UndefOr[Boolean] = js.undefined)
    val component = imports.Toolbar
  }

  @react
  object IconButton extends ExternalComponent {
    case class Props(
      edge: UndefOr[String] = js.undefined,
      color: String = "inherit",
      `aria-label`: String = "open drawer",
      href: UndefOr[String] = js.undefined
    )
    val component = imports.IconButton
  }

  @react
  object Typography extends ExternalComponent {
    case class Props(
      variant: UndefOr[String] = js.undefined,
      component: UndefOr[String] = js.undefined,
      color: String = "inherit"
    )
    val component = imports.Typography
  }

  @react
  object Container extends ExternalComponent {
    case class Props(
      maxWidth: UndefOr[String] = js.undefined,
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
      value: String,
      onChange: js.Dynamic => Unit,
      className: UndefOr[String] = js.undefined,
      disabled: UndefOr[Boolean] = js.undefined
    )
    val component = imports.InputBase
  }

  @react
  object Link extends ExternalComponent {
    case class Props(href: UndefOr[String] = js.undefined, onClick: UndefOr[js.Function0[Unit]] = js.undefined)
    val component = imports.Link
  }

  @react
  object LinearProgress extends ExternalComponent {
    case class Props(
      color: String = "primary",
      value: UndefOr[Int] = js.undefined,
      variant: String = "indeterminate"
    )
    val component = imports.LinearProgress
  }

  @react
  object List extends ExternalComponent {
    case class Props(
      className: UndefOr[String] = js.undefined,
      subheader: UndefOr[ReactElement] = js.undefined
    )
    val component = imports.List
  }

  object ListSubheader extends ExternalComponentNoProps {
    val component = imports.ListSubheader
  }

  @react
  object ListItem extends ExternalComponent {
    case class Props(
      className: UndefOr[String] = js.undefined,
      button: UndefOr[Boolean] = js.undefined
    )
    val component = imports.ListItem
  }

  @react
  object ListItemText extends ExternalComponent {
    case class Props(
      primary: String,
      secondary: UndefOr[String] = js.undefined
    )
    val component = imports.ListItemText
  }

  object ListItemSecondaryAction extends ExternalComponentNoProps {
    val component = imports.ListItemSecondaryAction
  }

  @react
  object Divider extends ExternalComponent {
    case class Props(
      className: UndefOr[String] = js.undefined
    )
    val component = imports.Divider
  }

  object CssBaseline extends ExternalComponentNoProps {
    val component = imports.CssBaseline
  }

}

package icons {
  private object imports {
    @js.native @JSImport("@material-ui/icons/Home", JSImport.Default)
    object Home extends js.Object
    @js.native @JSImport("@material-ui/icons/Menu", JSImport.Default)
    object Menu extends js.Object
    @js.native @JSImport("@material-ui/icons/GetApp", JSImport.Default)
    object GetApp extends js.Object
    @js.native @JSImport("@material-ui/icons/PlayArrow", JSImport.Default)
    object PlayArrow extends js.Object
    @js.native @JSImport("@material-ui/icons/ArrowBack", JSImport.Default)
    object ArrowBack extends js.Object
  }

  object Home extends ExternalComponentNoProps {
    val component = imports.Home
  }
  object Menu extends ExternalComponentNoProps {
    val component = imports.Menu
  }
  object GetApp extends ExternalComponentNoProps {
    val component = imports.GetApp
  }
  object PlayArrow extends ExternalComponentNoProps {
    val component = imports.PlayArrow
  }
  object ArrowBack extends ExternalComponentNoProps {
    val component = imports.ArrowBack
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
