package component

import component.material_ui.core.{Divider, ListItem, ListItemText, Toolbar, Typography, List => MUIList}
import logic.model.{Metadata, Torrent => TorrentModel}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._
import squants.Percent
import squants.experimental.formatter.Formatters.InformationMetricFormatter

import scala.scalajs.js

@react
object Torrent {
  case class Props(router: Router, model: TorrentModel, metadata: Metadata)

  val component = FunctionalComponent[Props] { props =>
    def videoStreamUrl(index: Int) = environment.httpUrl(s"/torrent/${props.model.infoHash.toString}/data/$index")
    def handlePlayClick(index: Int): js.Function0[Unit] =
      () => props.router.navigate(Router.Route.File(index, Router.Route.Torrent(props.model.infoHash)))

    div(
      Toolbar(
        Typography(color = "textSecondary", variant = "h5")(props.metadata.name)
      ),
      Divider(),
      renderList(props.metadata, props.model.availability, handlePlayClick)
    )
  }

  private def renderList(
    metadata: Metadata,
    availability: List[Double],
    handleClick: Int => () => Unit
  ): ReactElement =
    MUIList(
      for ((file, index) <- metadata.files.zipWithIndex)
        yield {
          ListItem(button = true)(
            key := s"file-$index",
            onClick := handleClick(index),
            ListItemText(
              primary = Typography(noWrap = true)(file.path.last): ReactElement,
              secondary =
                InformationMetricFormatter.inBestUnit(file.size).rounded(1).toString() +
                availability
                  .lift(index)
                  .map { p =>
                    val percent = Percent(p * 100).rounded(1, BigDecimal.RoundingMode.FLOOR).toString()
                    s" | $percent"
                  }
                  .getOrElse("")
            )
          )
        }
    )

}
