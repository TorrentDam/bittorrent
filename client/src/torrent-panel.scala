import diode.Dispatcher
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.web.html._

@react
object TorrentPanel {
  case class Props(model: TorrentModel, dispatcher: Dispatcher)

  val component = FunctionalComponent[Props] { props =>
    div(
      p(s"Connected: ${props.model.connected}"),
      if (props.model.metadata)
        List(
          p(
            key := "torrent-metadata",
            a(
              href := s"http://localhost:9999/torrent/${props.model.infoHash}/metadata",
              target := "_blank"
            )("Metadata")
          ),
          p(
            key := "torrent-video-player",
            video(
              width := "400",
              controls := true,
              source(src := "https://www.w3schools.com/html/mov_bbb.mp4")
            )
          )
        )
      else Nil
    )

  }
}
