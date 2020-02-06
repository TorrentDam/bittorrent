package component

import component.material_ui.icons
import component.material_ui.core._
import slinky.core.annotations.react
import slinky.core.{CustomAttribute, FunctionalComponent}
import slinky.web.html._

import scala.scalajs.js

@react
object Player {

  case class Props(router: Router, infoHash: String, index: Int)

  val component = FunctionalComponent[Props] { props =>
    val navigateBack: js.Function0[Unit] = () => props.router.navigate(Router.Route.Torrent(props.infoHash))
    val videoStreamUrl = environment.httpUrl(s"/torrent/${props.infoHash}/data/${props.index}")
    div(
      div(
        IconButton(icons.ArrowBack(), onClick := navigateBack)
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
