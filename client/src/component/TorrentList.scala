package component

import com.github.lavrov.bittorrent.app.domain.InfoHash
import component.Router.Route
import component.material_ui.core.{List, ListItem, ListItemText, ListSubheader, Typography}
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.core.facade.ReactElement
import slinky.web.html._

@react
object TorrentList {

  case class Props(title: String, items: List[(InfoHash, String)], router: Router)

  val component = FunctionalComponent[Props] { props =>
    def handleClick(infoHash: InfoHash) =
      () => {
        props.router.navigate(Route.Torrent(infoHash))
      }

    List(subheader = ListSubheader(props.title): ReactElement)(
      for {
        ((infoHash, title), index) <- props.items.zipWithIndex
      } yield {
        ListItem(button = true)(
          key := s"torrent-list-item-$index",
          onClick := handleClick(infoHash),
          ListItemText(
            primary = Typography(noWrap = true)(title): ReactElement,
            secondary = infoHash.toString
          )
        )
      }
    )
  }
}
