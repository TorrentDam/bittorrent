package component

import logic.{Dispatcher, TorrentModel}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.Hooks
import slinky.web.html._

import scala.scalajs.js

@react
object Torrent {
  case class Props(model: TorrentModel, dispatcher: Dispatcher)

  val component = FunctionalComponent[Props] { props =>
    val (value, setState) = Hooks.useState(Option.empty[Int])
    def handleClick(index: Int): js.Function0[Unit] = () => setState(Some(index))
    div(
      p(s"Connected: ${props.model.connected}"),
      for (metadata <- props.model.metadata)
        yield div(
          p(
            key := "torrent-metadata",
            a(
              href := environment.httpUrl(s"/torrent/${props.model.infoHash}/metadata"),
              target := "_blank"
            )("Metadata")
          ),
          value match {
            case Some(fileIndex) =>
              val videoStreamUrl = s"/torrent/${props.model.infoHash}/data/$fileIndex"
              div(
                p(
                  key := "torrent-data",
                  a(
                    href := environment.httpUrl(videoStreamUrl),
                    target := "_blank"
                  )("Download")
                ),
                p(
                  key := "torrent-video-player",
                  video(
                    width := "400",
                    controls := true,
                    source(
                      src := environment.httpUrl(videoStreamUrl)
                    )
                  )
                )
              )
            case None =>
              div(
                for ((file, index) <- metadata.zipWithIndex)
                  yield p(
                    key := s"file-$index",
                    file.last,
                    button("Pick", onClick := handleClick(index))
                  )
              )
          }
        )
    )

  }
}
