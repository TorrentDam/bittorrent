import slinky.core.annotations.react
import slinky.core.FunctionalComponent
import slinky.web.html._

import material_ui.core._
import material_ui.styles.makeStyles

import scala.scalajs.js.Dynamic

@react
object DownloadPanel {
  case class Props()

  private val useStyles = makeStyles( theme =>
    Dynamic.literal(
      container = Dynamic.literal(
        paddingTop = theme.spacing(4),
        paddingBottom = theme.spacing(4),
      ),
      root = Dynamic.literal(
        padding = theme.spacing(2),
        display = "flex",
        alignItems = "center", 
      ),
      input = Dynamic.literal(
        marginLeft = theme.spacing(1),
        marginRight = theme.spacing(1),
        flex = 1,
      )
    )
  )

  val component = FunctionalComponent[Unit] { _ =>
    val classes = useStyles()
    main(
      Container(maxWidth = "md", className = classes.container.toString)(
        Paper(className = classes.root.toString)(
          InputBase(placeholder = "Info hash", className = classes.input.toString),
          Button(variant = "contained")("Download")
        )
      )
    )
  }
}