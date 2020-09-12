package component

import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.Router.Route
import logic.Dispatcher
import logic.model.{Discovered, Root}
import logic.SearchApi.SearchResults
import material_ui.core._
import material_ui.icons
import material_ui.styles.makeStyles
import org.scalajs.dom.Event
import slinky.core.{FunctionalComponent}
import slinky.core.annotations.react
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._

import scala.scalajs.js.Dynamic.{literal => obj}

@react
object Search {
  case class Props(model: Option[Root.Search], discovered: Option[Discovered], router: Router, dispatcher: Dispatcher)

  private val useStyles = makeStyles(theme =>
    obj(
      root = obj(
        padding = theme.spacing(1),
        display = "flex",
        textAlign = "center",
        marginTop = theme.spacing(4)
      ),
      input = obj(
        marginLeft = theme.spacing(1),
        marginRight = theme.spacing(1),
        flex = 1
      ),
      searchContent = obj(
        marginTop = theme.spacing(4)
      ),
      notFound = obj(
        textAlign = "center",
        marginTop = theme.spacing(4)
      )
    )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    div(
      SearchBox(props.model.map(_.query).getOrElse(""), props.router, props.dispatcher),
      div(className := classes.searchContent.toString)(
        props.model match {
          case Some(search) =>
            search.results.map { results =>
              val items = results.results.collect {
                case SearchResults.Item(title, extractInfoHash(infoHash)) =>
                  (infoHash, title)
              }

              val element: ReactElement =
                if (items.nonEmpty)
                  Fade(in = true)(
                    TorrentList("Search results", items, props.router)
                  )
                else
                  p(className := classes.notFound.toString)("Nothing discovered yet")

              element
            }
          case _ =>
            props.discovered.filter(_.torrents.nonEmpty).map { discovered =>
              TorrentList("Recent torrents", discovered.torrents, props.router)
            }
        }
      )
    )
  }

  @react
  object SearchBox {

    case class Props(initialValue: String, router: Router, dispatcher: Dispatcher)

    val component = FunctionalComponent[Props] { props =>
      val classes = useStyles()
      val (state, setState) = Hooks.useState(props.initialValue)

      val infoHashOpt = extractInfoHash.lift(state)

      def handleSubmit(e: Event) = {
        e.preventDefault()
        infoHashOpt match {
          case Some(infoHash) =>
            props.router.navigate(Route.Torrent(infoHash))
          case None =>
            props.router.navigate(Route.Search(state))
        }
      }

      div(
        Paper(className = classes.root.toString, component = "form", onSubmit = handleSubmit _)(
          InputBase(
            placeholder = "Info hash or magnet link",
            value = state,
            onChange = event => setState(event.target.value.toString),
            className = classes.input.toString
          ),
          IconButton(`type` = "submit")(
            icons.ArrowForward()
          )
        )
      )
    }
  }

  private val regex = """xt=urn:btih:(\w+)""".r

  private val infoHashInUri: String => Option[InfoHash] =
    regex
      .findFirstMatchIn(_)
      .map(_.group(1))
      .flatMap(InfoHash.fromString.lift)

  private val extractInfoHash: PartialFunction[String, InfoHash] = {
    InfoHash.fromString.orElse(infoHashInUri.unlift)
  }

}
