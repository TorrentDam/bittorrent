package logic

import cats.implicits._
import logic.model._
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import component.Router.Route
import squants.information

import scala.util.chaining._
import scala.concurrent.ExecutionContext

trait Handler {

  def apply(model: Root, action: Action): Root
}

object Handler {

  def apply(send: Command => Unit, dispatcher: Dispatcher)(implicit ec: ExecutionContext): Handler =
    new Impl(send, dispatcher)

  private class Impl(send: Command => Unit, dispatcher: Dispatcher)(implicit ec: ExecutionContext) extends Handler {

    override def apply(model: Root, action: Action): Root = {
      action match {
        case Action.UpdateConnectionStatus(connected) =>
          model.copy(connected = connected)

        case Action.Search(query) =>
          model.search match {
            case Some(Root.Search(`query`, _)) => model
            case _ =>
              send(Command.Search(query))
              val search = Root.Search(query, None)
              model.copy(search = Some(search))
          }

        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)

          event match {
            case Event.RequestAccepted(infoHash) =>
              model.copy(torrent = Some(Torrent(infoHash, 0, Nil, None)))

            case Event.TorrentPeersDiscovered(infoHash, count) if model.torrent.exists(_.infoHash == infoHash) =>
              model.copy(
                torrent = model.torrent.map(_.copy(connected = count))
              )

            case Event.TorrentMetadataReceived(infoHash, name, files) if model.torrent.exists(_.infoHash == infoHash) =>
              model.torrent match {
                case Some(torrent) =>
                  val metadataFiles = files.map { f =>
                    Metadata.File(
                      f.path,
                      information.Bytes(f.size)
                    )
                  }
                  val metadata = Metadata(name, metadataFiles)
                  val withMetadata = torrent.withMetadata(metadata)
                  model.copy(torrent = Some(withMetadata))

                case _ => model
              }

            case Event.TorrentError(infoHash, message) if model.torrent.exists(_.infoHash == infoHash) =>
              model.copy(
                torrent = model.torrent.map(_.withError(message))
              )

            case Event.Discovered(torrents) =>
              model.copy(
                discovered = model.discovered
                  .fold(Discovered(torrents.toList)) { discovered =>
                    discovered.copy(torrents = torrents.toList ++ discovered.torrents)
                  }
                  .some
              )

            case Event.TorrentStats(infoHash, connected, availability)
                if model.torrent.exists(_.infoHash == infoHash) =>
              model.copy(
                torrent = model.torrent.map(_.copy(connected = connected, availability = availability))
              )

            case Event.SearchResults(query, entries) =>
              model.search
                .filter(_.query == query)
                .fold(model) { search =>
                  val updated = search.copy(results = Some(entries))
                  model.copy(search = Some(updated))
                }

            case _ =>
              model
          }
        case Action.Navigate(route) =>
          model.copy(route = Some(route)).pipe { value =>
            route match {
              case Route.Search(query) =>
                this(value, Action.Search(query))
              case Route.Torrent(infoHash) =>
                println("Handler navigated to torrent")
                getTorrent(value, infoHash)
              case Route.File(_, Route.Torrent(infoHash)) =>
                getTorrent(value, infoHash)
              case _ =>
                if (value.discovered.isEmpty) {
                  send(Command.GetDiscovered())
                  value.copy(discovered = Discovered(List.empty).some)
                }
                else {
                  value
                }
            }
          }
      }
    }

    private def getTorrent(model: Root, infoHash: InfoHash) = {
      if (model.torrent.exists(_.infoHash == infoHash))
        model
      else {
        send(Command.GetTorrent(infoHash))
        model.copy(
          torrent = Some(Torrent(infoHash, 0, Nil, None))
        )
      }
    }
  }

}
