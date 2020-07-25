package logic

import logic.model._
import monix.reactive.Observable
import component.Router.Route

object WindowTitle {

  def fromModel(model: Root): String =
    model.route.getOrElse(Route.Root) match {
      case Route.Search(_) => "Search / BitTorrent"
      case Route.Torrent(infoHash) =>
        model.torrent
          .flatMap(_.metadata)
          .flatMap(_.toOption)
          .map(_.name)
          .fold("Opening torrent...")(name => s"$name / BitTorrent")
      case _ => "BitTorrent"
    }

}
