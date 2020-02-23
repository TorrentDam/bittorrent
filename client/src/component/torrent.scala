package component

import component.material_ui.core.{
  Breadcrumbs,
  Divider,
  IconButton,
  Link,
  ListItem,
  ListItemSecondaryAction,
  ListItemText,
  Typography,
  List => MUIList
}
import component.material_ui.icons
import logic.{Metadata, TorrentModel}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._
import squants.experimental.formatter.Formatters.InformationMetricFormatter
import squants.Percent

import scala.scalajs.js

@react
object Torrent {
  case class Props(router: Router, model: TorrentModel, metadata: Metadata)

  val component = FunctionalComponent[Props] { props =>
    def videoStreamUrl(index: Int) = environment.httpUrl(s"/torrent/${props.model.infoHash.toString}/data/$index")
    def handlePlayClick(index: Int): js.Function0[Unit] =
      () => props.router.navigate(Router.Route.File(index, Router.Route.Torrent(props.model.infoHash)))
    renderList(videoStreamUrl, props.metadata, props.model.availability, handlePlayClick)
  }

  private def renderList(
    videoSrc: Int => String,
    metadata: Metadata,
    availability: List[Double],
    handleClick: Int => () => Unit
  ): ReactElement =
    div(
      MUIList(
        for ((file, index) <- metadata.files.zipWithIndex)
          yield {
            ListItem(button = true)(
              key := s"file-$index",
              onClick := handleClick(index),
              ListItemText(
                primary = file.path.last,
                secondary =
                  InformationMetricFormatter.inBestUnit(file.size).rounded(1).toString() +
                  availability
                    .lift(index)
                    .map { p =>
                      val percent = Percent(p * 100).rounded(1, BigDecimal.RoundingMode.FLOOR).toString()
                      s" | $percent"
                    }
                    .getOrElse("")
              ),
              ListItemSecondaryAction(
                IconButton(edge = "end", `aria-label` = "download", href = videoSrc(index))(
                  target := "_blank",
                  icons.GetApp()
                )
              )
            )
          }
      )
    )

}
