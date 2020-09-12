package component

import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.material_ui.icons
import component.material_ui.core._
import logic.model.Metadata
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.core.{CustomAttribute, FunctionalComponent}
import slinky.web.html._

import scala.scalajs.js

@react
object VideoPlayer {

  case class Props(router: Router, infoHash: InfoHash, file: Metadata.File, index: Int)

  val component = FunctionalComponent[Props] { props =>
    val navigateBack: js.Function0[Unit] = () => props.router.navigate(Router.Route.Torrent(props.infoHash))
    val videoStreamUrl = environment.httpUrl(s"/torrent/${props.infoHash.toString}/data/${props.index}")
    div(
      Toolbar(disableGutters = true)(
        Button(startIcon = icons.ArrowBack(): ReactElement)(
          onClick := navigateBack,
          "Back to files"
        )
      ),
      video(
        width := "100%",
        controls := true,
        new CustomAttribute[Boolean]("autoPlay") := true,
        source(
          src := videoStreamUrl
        )
      ),
      Grid(container = true)(
        Grid(item = true, xs = true)(
          Toolbar(disableGutters = true)(
            Typography(variant = "subtitle1", noWrap = true)(props.file.path.last)
          )
        ),
        Grid(item = true)(
          Toolbar(
            IconButton(edge = "end", `aria-label` = "download", href = videoStreamUrl)(
              target := "_blank",
              icons.GetApp()
            )
          )
        )
      )
    )
  }
}
