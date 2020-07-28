package component

import component.material_ui.core._
import component.material_ui.icons
import component.material_ui.styles.makeStyles
import logic.Dispatcher
import logic.model.{Metadata, Root, Torrent => TorrentModel}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._

import scala.scalajs.js
import scala.scalajs.js.Dynamic.{literal => obj}
import scala.scalajs.js.annotation.JSImport

@react
object App {
  case class Props(router: Router, model: Root, dispatcher: Dispatcher)

  private val useStyles = makeStyles(theme =>
    obj(
      appBarSpacer = theme.mixins.toolbar,
      appBarTitle = obj(
        flexGrow = 1,
        margin = theme.spacing(1)
      ),
      navLink = obj(
        margin = theme.spacing(1, 1.5)
      ),
      centered = obj(
        textAlign = "center"
      )
    )
  )

  @JSImport("windmill.svg", JSImport.Default)
  @js.native
  val windmillIcon: js.Object = js.native

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()

    div()(
      CssBaseline(),
      div(className := classes.appBarSpacer.toString),
      AppBar(position = "fixed")(
        Container(maxWidth = "md")(
          Toolbar(disableGutters = true)(
            SvgIcon(component = windmillIcon, viewBox = "0 0 15 15", color = "inherit"),
            Link(href = "#", color = "inherit", className = classes.appBarTitle.toString)(
              Typography(variant = "h6")("TorrentDam")
            ),
            IconButton(href = "https://github.com/lavrov/bittorrent")(
              icons.GitHub()
            )
          )
        )
      ),
      main(
        Container(maxWidth = "md")(
          if (props.model.connected)
            props.model.route.fold[ReactElement](span()) {
              case Router.Route.Root =>
                Search(None, props.router, props.dispatcher)

              case Router.Route.Search(_) =>
                Search(props.model.search, props.router, props.dispatcher)

              case Router.Route.Torrent(_) =>
                withTorrent(props.model)(torrent => metadata => Torrent(props.router, torrent, metadata))

              case Router.Route.File(index, _) =>
                withTorrent(props.model)(torrent =>
                  metadata => VideoPlayer(props.router, torrent.infoHash, metadata.files(index), index)
                )

              case Router.Route.Discover =>
                Discover(props.model.discovered, props.router)
            }
          else
            p(className := classes.centered.toString)("Connecting to server...")
        )
      )
    )
  }

  private def withTorrent(model: Root)(
    component: TorrentModel => Metadata => ReactElement
  ): ReactElement = {
    model.torrent match {
      case Some(torrent) =>
        FetchingMetadata(torrent, component(torrent))
      case _ =>
        div()
    }
  }
}
