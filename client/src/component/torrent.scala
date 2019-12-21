package component

import logic.{Dispatcher, TorrentModel}
import slinky.core.CustomAttribute
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._
import material_ui.core.{IconButton, ListItem, ListItemSecondaryAction, ListItemText, List => MUIList}
import material_ui.icons

import scala.scalajs.js

@react
object Torrent {
  case class Props(model: TorrentModel, dispatcher: Dispatcher)

  val component = FunctionalComponent[Props] { props =>
    val (value, setState) = Hooks.useState(Option.empty[Int])
    def handleClick(index: Int): js.Function0[Unit] = () => setState(Some(index))
    div(
      for (metadata <- props.model.metadata)
        yield div(
          value match {
            case Some(fileIndex) => renderFile(props.model.infoHash, fileIndex)
            case None => renderList(metadata, handleClick)
          },
          renderMetadata(props.model.infoHash)
        ),
      p(s"Connected: ${props.model.connected}")
    )
  }

  private def renderMetadata(infoHash: String): ReactElement = {
    p(
      key := "torrent-metadata",
      a(
        href := environment.httpUrl(s"/torrent/${infoHash}/metadata"),
        target := "_blank"
      )("Metadata")
    )
  }

  private def renderList(metadata: List[List[String]], handleClick: Int => () => Unit): ReactElement =
    MUIList()(
      for ((file, index) <- metadata.zipWithIndex)
        yield ListItem()(
          key := s"file-$index",
          ListItemText(primary = file.last),
          ListItemSecondaryAction(
            IconButton(edge = "end", `aria-label` = "play")(
              onClick := handleClick(index),
              icons.PlayArrow()
            ),
            IconButton(edge = "end", `aria-label` = "download")(
              icons.GetApp()
            )
          )
        )
    )

  private def renderFile(infoHash: String, fileIndex: Int): ReactElement = {
    val videoStreamUrl = s"/torrent/$infoHash/data/$fileIndex"
    div(
      p(
        key := "torrent-video-player",
        video(
          width := "100%",
          controls := true,
          new CustomAttribute[Boolean]("autoplay") := true,
          source(
            src := environment.httpUrl(videoStreamUrl)
          )
        )
      ),
      p(
        key := "torrent-data",
        a(
          href := environment.httpUrl(videoStreamUrl),
          target := "_blank"
        )("Download")
      )
    )
  }

}
