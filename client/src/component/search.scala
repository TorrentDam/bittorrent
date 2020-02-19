package component
import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.Router.Route
import material_ui.core._
import material_ui.icons
import material_ui.styles.makeStyles
import org.scalajs.dom.Event
import scodec.bits.ByteVector
import slinky.core.{FunctionalComponent, SyntheticEvent}
import slinky.core.annotations.react
import slinky.core.facade.Hooks
import slinky.web.html._

import scala.scalajs.js.Dynamic

@react
object Search {
  case class Props(router: Router)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        root = Dynamic.literal(
          padding = theme.spacing(1),
          display = "flex",
          alignItems = "center"
        ),
        input = Dynamic.literal(
          marginLeft = theme.spacing(1),
          marginRight = theme.spacing(1),
          flex = 1
        )
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    val (value, setState) = Hooks.useState("")
    val infoHashOpt = extractInfoHash(value)
    def handleClick(e: SyntheticEvent[org.scalajs.dom.html.Form, Event]) = {
      e.preventDefault()
      infoHashOpt match {
        case Some(infoHash) =>
          props.router.navigate(Route.Torrent(infoHash))
        case None =>
          println(value)
      }
    }
    Paper(className = classes.root.toString, component = "form")(
      onSubmit := [form.tagType] (handleClick),
      InputBase(
        placeholder = "Info hash or magnet link",
        value = value,
        onChange = event => setState(event.target.value.asInstanceOf[String]),
        className = classes.input.toString
      ),
      IconButton(`type` = "submit")(
        icons.ArrowForward()
      )
    )
  }

  private val regex = """xt=urn:btih:(\w+)""".r

  private def extractInfoHash(value: String): Option[InfoHash] = {
    def isInfoHash(str: String) = ByteVector.fromHex(str).exists(_.size == 20)
    InfoHash.fromString
      .lift(value)
      .orElse(
        regex.findFirstMatchIn(value).map(_.group(1)).collectFirst {
          case InfoHash.fromString(infoHash) => infoHash
        }
      )
  }
}
