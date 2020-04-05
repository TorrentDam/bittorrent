package component

import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.Router.Route
import logic.{Action, Dispatcher, RootModel}
import logic.SearchApi.SearchResults
import material_ui.core._
import material_ui.icons
import material_ui.styles.makeStyles
import org.scalajs.dom.Event
import slinky.core.{FunctionalComponent, SyntheticEvent}
import slinky.core.annotations.react
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._
import slinky.web.svg.in

import scala.scalajs.js.Dynamic

@react
object Search {
  case class Props(model: Option[RootModel.Search], router: Router, dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
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
    div(
      SearchBox(props.model.map(_.query).getOrElse(""), props.router, props.dispatcher),
      for (search <- props.model; results <- search.results)
        yield ResultList(results, props.router)
    )
  }

  @react
  object SearchBox {

    case class Props(initialValue: String, router: Router, dispatcher: Dispatcher)

    val component = FunctionalComponent[Props] { props =>
      val classes = useStyles()
      val (state, setState) = Hooks.useState(props.initialValue)

      val infoHashOpt = extractInfoHash(state)

      def handleSubmit(e: Event) = {
        e.preventDefault()
        infoHashOpt match {
          case Some(infoHash) =>
            props.router.navigate(Route.Torrent(infoHash))
          case None =>
            props.dispatcher(Action.Search(state))
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

  @react
  object ResultList {

    case class Props(searchResults: SearchResults, router: Router)

    val component = FunctionalComponent[Props] { props =>
      val classes = useStyles()

      def handleClick(infoHash: InfoHash) = () => {
        props.router.navigate(Route.Torrent(infoHash))
      }

      Fade(in = true)(
        if (props.searchResults.results.nonEmpty)
          List(
            for {
              (item, index) <- props.searchResults.results.zipWithIndex
              infoHash <- extractInfoHash(item.magnet)
            } yield {
              ListItem(button = true)(
                key := s"search-result-$index",
                onClick := handleClick(infoHash),
                ListItemText(
                  primary = Typography(noWrap = true)(item.title): ReactElement,
                  secondary = infoHash.toString
                )
              )
            }
          )
        else
          p(className := classes.notFound.toString)("Nothing found")
      )
    }
  }

  private val regex = """xt=urn:btih:(\w+)""".r

  private def extractInfoHash(value: String): Option[InfoHash] = {
    InfoHash.fromString
      .lift(value)
      .orElse(
        regex.findFirstMatchIn(value).map(_.group(1)).collectFirst {
          case InfoHash.fromString(infoHash) => infoHash
        }
      )
  }

}
