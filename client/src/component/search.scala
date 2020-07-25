package component

import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.Router.Route
import logic.Dispatcher
import logic.model.Root
import logic.SearchApi.SearchResults
import material_ui.core._
import material_ui.icons
import material_ui.styles.makeStyles
import org.scalajs.dom.Event
import slinky.core.{FunctionalComponent, SyntheticEvent}
import slinky.core.annotations.react
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._

import scala.scalajs.js.Dynamic

@react
object Search {
  case class Props(model: Option[Root.Search], router: Router, dispatcher: Dispatcher)

  private val useStyles = makeStyles(theme =>
    Dynamic.literal(
      root = Dynamic.literal(
        padding = theme.spacing(1),
        display = "flex",
        textAlign = "center",
        marginTop = theme.spacing(4)
      ),
      input = Dynamic.literal(
        marginLeft = theme.spacing(1),
        marginRight = theme.spacing(1),
        flex = 1
      ),
      notFound = Dynamic.literal(
        textAlign = "center",
        marginTop = theme.spacing(4)
      )
    )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    div(
      SearchBox(props.model.map(_.query).getOrElse(""), props.router, props.dispatcher),
      for (search <- props.model; results <- search.results)
        yield {
          val items = results.results.collect {
            case SearchResults.Item(title, extractInfoHash(infoHash)) =>
              (infoHash, title)
          }

          val result: ReactElement =
            if (items.nonEmpty)
              Fade(in = true)(
                TorrentList(items, props.router)
              )
            else
              p(className := classes.notFound.toString)("Nothing discovered yet")
          result
        }
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

  private val extractInfoHash: PartialFunction[String, InfoHash] = {
    InfoHash.fromString.orElse {
      case regex(InfoHash.fromString(infoHash)) => infoHash
    }
  }

}
