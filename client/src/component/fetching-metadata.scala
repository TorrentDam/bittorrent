package component

import component.material_ui.styles.makeStyles
import logic.{Metadata, TorrentModel}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html.{className, p}

import scala.scalajs.js.Dynamic.literal

@react
object FetchingMetadata {

  case class Props(model: TorrentModel, render: Metadata => ReactElement)

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    props.model.metadata match {
      case None =>
        p(className := classes.centered.toString)("Fetching torrent metadata...")
      case Some(Left(_)) =>
        p(className := classes.centered.toString)("Could not fetch metadata")
      case Some(Right(metadata)) =>
        props.render(metadata)
    }
  }

  private val useStyles = makeStyles(
    _ =>
      literal(
        centered = literal(
          textAlign = "center"
        )
      )
  )
}
