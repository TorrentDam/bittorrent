package component

import component.material_ui.core._
import component.material_ui.styles.makeStyles
import monix.reactive._
import logic.{Dispatcher, Metadata, RootModel, TorrentModel}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._

import scala.scalajs.js.Dynamic

@react
object App {
  case class Props(router: Router, model: RootModel, dispatcher: Dispatcher)

  private val useStyles = makeStyles(theme =>
    Dynamic.literal(
      appBarSpacer = theme.mixins.toolbar,
      appBarTitle = Dynamic.literal(
        flexGrow = 1
      ),
      navLink = Dynamic.literal(
        margin = theme.spacing(1, 1.5)
      ),
      centered = Dynamic.literal(
        textAlign = "center"
      )
    )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    def navLink(href: String, name: String) =
      Link(href = href, className = classes.navLink.toString, color = "inherit")(name)
    div(
      div(className := classes.appBarSpacer.toString),
      AppBar(position = "fixed")(
        Container(maxWidth = "md")(
          Toolbar(disableGutters = true)(
            Link(href = "#", color = "inherit", className = classes.appBarTitle.toString)(
              Typography(variant = "h6")("BitTorrent")
            ),
            nav(
              navLink(href = "#discover", "Discover"),
              navLink(href = "https://github.com/lavrov/bittorrent", "GitHub")
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

                case Router.Route.Search(name) =>
                  Search(props.model.search, props.router, props.dispatcher)

                case torrentRoute: Router.Route.Torrent =>
                  withTorrent(torrentRoute, props.model, props.dispatcher)(torrent =>
                    metadata => Torrent(props.router, torrent, metadata)
                  )

                case Router.Route.File(index, torrentRoute) =>
                  withTorrent(torrentRoute, props.model, props.dispatcher)(torrent =>
                    metadata => VideoPlayer(props.router, torrent.infoHash, metadata.files(index), index)
                  )

                case Router.Route.Discover =>
                  Discover()
              }
            else
              p(className := classes.centered.toString)("Connecting to server...")
        )
      )
    )
  }

  private def withTorrent(route: Router.Route.Torrent, model: RootModel, dispatcher: Dispatcher)(
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
