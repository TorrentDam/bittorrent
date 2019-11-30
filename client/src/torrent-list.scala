import diode.Dispatcher

import material_ui.core._
import material_ui.styles.makeStyles

import slinky.core.FunctionalComponent
import slinky.core.annotations.react

import scala.scalajs.js.Dynamic

@react
object TorrentList {
  case class Props(model: TorrentListModel, dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        container = Dynamic.literal(
          paddingTop = theme.spacing(4),
          paddingBottom = theme.spacing(4)
        ),
        root = Dynamic.literal(
          padding = theme.spacing(2),
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
    List()(
      for (torrent <- props.model.torrents)
        yield List(
          ListItem()(torrent),
          Divider()
        )
    )
  }
}
