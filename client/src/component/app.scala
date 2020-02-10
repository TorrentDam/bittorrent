package component

import frp.Observable
import logic.{Dispatcher, Metadata, RootModel, TorrentModel}
import material_ui.core._
import material_ui.icons
import material_ui.styles.makeStyles
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._

import scala.scalajs.js.Dynamic

@react
object App {
  case class Props(router: Router, model: Observable[RootModel], dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        appBarSpacer = theme.mixins.toolbar,
        container = Dynamic.literal(
          paddingTop = theme.spacing(4),
          paddingBottom = theme.spacing(4)
        ),
        breadcrumb = Dynamic.literal(
          paddingBottom = theme.spacing(2)
        ),
        centered = Dynamic.literal(
          textAlign = "center"
        )
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    div(
      div(className := classes.appBarSpacer.toString),
      AppBar(position = "fixed")(
        Container(maxWidth = "md")(
          Toolbar(disableGutters = true)(
            IconButton(edge = "start", href = "#")(
              icons.Home()
            ),
            Typography(variant = "h6")("BitTorrent")
          )
        )
      ),
      main(
        Container(maxWidth = "md", className = classes.container.toString)(
          Connect(props.model.zoomTo(_.connected), props.dispatcher) {
            case (true, _) =>
              props.router.when {
                case Router.Route.Root =>
                  DownloadPanel(props.router)
                case torrentRoute: Router.Route.Torrent =>
                  withTorrent(torrentRoute, props.model, props.dispatcher)(
                    torrent =>
                      metadata =>
                        div(
                          Breadcrumbs(className = classes.breadcrumb.toString)(
                            Typography(color = "textPrimary")("Files")
                          ),
                          Divider(),
                          Torrent(props.router, torrent, metadata)
                        )
                  )
                case Router.Route.File(index, torrentRoute) =>
                  withTorrent(torrentRoute, props.model, props.dispatcher)(
                    torrent =>
                      metadata =>
                        div(
                          Breadcrumbs(className = classes.breadcrumb.toString)(
                            Link(href = "#" + Router.Route.toString(torrentRoute))("Files"),
                            Typography(color = "textPrimary")(metadata.files(index).path.last)
                          ),
                          Divider(),
                          VideoPlayer(props.router, torrent.infoHash, index)
                        )
                  )

              }
            case _ =>
              p(className := classes.centered.toString)("Connecting to server...")
          }
        )
      )
    )
  }

  private def withTorrent(route: Router.Route.Torrent, model: Observable[RootModel], dispatcher: Dispatcher)(
    component: TorrentModel => Metadata => ReactElement
  ): ReactElement = {
    Connect(model.zoomTo(_.torrent), dispatcher) {
      case (Some(torrent), _) =>
        FetchingMetadata(torrent, component(torrent))
      case _ =>
        div()
    }
  }
}
