package component

import logic.{Dispatcher, Metadata, TorrentModel}
import slinky.core.CustomAttribute
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._
import material_ui.core.{
  IconButton,
  ListItem,
  ListItemSecondaryAction,
  ListItemText,
  ListSubheader,
  Paper,
  List => MUIList
}
import material_ui.icons
import material_ui.styles.makeStyles
import squants.experimental.formatter.Formatters.InformationMetricFormatter

import scala.scalajs.js
import scala.scalajs.js.Dynamic.literal

@react
object Torrent {
  case class Props(model: TorrentModel, dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    _ =>
      literal(
        centered = literal(
          textAlign = "center"
        )
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    val (value, setState) = Hooks.useState(Option.empty[Int])
    def handlePlayClick(index: Int): js.Function0[Unit] = () => setState(Some(index))
    def handleBackClick: js.Function0[Unit] = () => setState(None)
    def videoStreamUrl(index: Int) = environment.httpUrl(s"/torrent/${props.model.infoHash}/data/$index")
    props.model.metadata match {
      case None =>
        p(className := classes.centered.toString)("Fetching torrent metadata...")
      case Some(Right(metadata)) =>
        value match {
          case Some(fileIndex) => renderFile(videoStreamUrl(fileIndex), handleBackClick)
          case None => renderList(videoStreamUrl, metadata, handlePlayClick)
        }
      case Some(Left(message)) =>
        p(className := classes.centered.toString)("Could not fetch metadata")
    }
  }

  private def renderList(
    videoSrc: Int => String,
    metadata: Metadata,
    handleClick: Int => () => Unit
  ): ReactElement = {
    Paper(
      MUIList(
        for ((file, index) <- metadata.files.zipWithIndex)
          yield {
            ListItem(button = true)(
              key := s"file-$index",
              onClick := handleClick(index),
              ListItemText(
                primary = file.path.last,
                secondary = InformationMetricFormatter.inBestUnit(file.size).rounded(1).toString()
              ),
              ListItemSecondaryAction(
                IconButton(edge = "end", `aria-label` = "play")(
                  onClick := handleClick(index),
                  icons.PlayArrow()
                ),
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

  private def renderFile(videoStreamUrl: String, handleBackClick: () => Unit): ReactElement = {
    div(
      div(
        IconButton(icons.ArrowBack(), onClick := handleBackClick)
      ),
      div(
        key := "torrent-video-player",
        video(
          width := "100%",
          controls := true,
          new CustomAttribute[Boolean]("autoPlay") := true,
          source(
            src := videoStreamUrl
          )
        )
      )
    )
  }

}
