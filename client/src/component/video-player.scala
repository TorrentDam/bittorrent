package component

import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.material_ui.icons
import component.material_ui.core._
import slinky.core.annotations.react
import slinky.core.{CustomAttribute, FunctionalComponent}
import slinky.web.html._

import scala.scalajs.js

@react
object VideoPlayer {

  case class Props(router: Router, infoHash: InfoHash, index: Int)

  val component = FunctionalComponent[Props] { props =>
    val navigateBack: js.Function0[Unit] = () => props.router.navigate(Router.Route.Torrent(props.infoHash))
    val videoStreamUrl = environment.httpUrl(s"/torrent/${props.infoHash}/data/${props.index}")
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
  }
}
