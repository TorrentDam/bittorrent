package component

import component.material_ui.styles.makeStyles
import logic.model.{Metadata, Torrent}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html.{className, p}

import scala.scalajs.js.Dynamic.literal

@react
object FetchingMetadata {

  case class Props(model: Torrent, render: Metadata => ReactElement)

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    props.model.metadata match {
      case None =>
        p(className := classes.centered.toString)(
          if (props.model.connected == 0)
            "Discovering peers"
          else
            s"Fetching torrent metadata from ${props.model.connected} peers"
        )
      case Some(Left(_)) =>
        p(className := classes.centered.toString)("Could not fetch metadata")
      case Some(Right(metadata)) =>
        props.render(metadata)
    }
  }

  private val useStyles = makeStyles(_ =>
    literal(
      centered = literal(
        textAlign = "center"
      )
    )
  )
}
