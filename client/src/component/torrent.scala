package component

import logic.{Dispatcher, TorrentModel}
import slinky.core.CustomAttribute
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._
import material_ui.core.{IconButton, ListItem, ListItemSecondaryAction, ListItemText, List => MUIList}
import material_ui.icons
import material_ui.styles.makeStyles

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
      case Some(metadata) =>
        value match {
          case Some(fileIndex) => renderFile(videoStreamUrl(fileIndex), handleBackClick)
          case None => renderList(videoStreamUrl, metadata, handlePlayClick)
        }
      case None =>
        p(className := classes.centered.toString)("Fetching torrent metadata...")
    }
  }

  private def renderList(
    videoSrc: Int => String,
    metadata: List[List[String]],
    handleClick: Int => () => Unit
  ): ReactElement = {
    MUIList()(
      for ((file, index) <- metadata.zipWithIndex)
        yield {
          ListItem()(
            key := s"file-$index",
            ListItemText(primary = file.last),
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
