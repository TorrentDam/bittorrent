package component

import logic.model.Discovered
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._

@react
object Discover {
  case class Props(discovered: Option[Discovered], router: Router)

  val component = FunctionalComponent[Props] { props =>
    val empty: ReactElement = span()

    props.discovered.fold(empty) { discovered =>
      TorrentList(discovered.torrents, props.router)
    }
  }
}
