package logic

import com.github.lavrov.bittorrent.app.domain.InfoHash
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import component.Router.Route
import squants.information

import scala.concurrent.ExecutionContext

trait Handler {

  def apply(value: RootModel, action: Action): RootModel
}

object Handler {

  def apply(send: Command => Unit, dispatcher: Dispatcher)(implicit ec: ExecutionContext): Handler =
    new Impl(send, dispatcher)

  private class Impl(send: Command => Unit, dispatcher: Dispatcher)(implicit ec: ExecutionContext) extends Handler {

    override def apply(value: RootModel, action: Action): RootModel = {
      action match {
        case Action.UpdateConnectionStatus(connected) =>
          value.copy(connected = connected)

        case Action.Search(query) =>
          value.search match {
            case Some(RootModel.Search(`query`, _)) => value
            case _ =>
              val search = RootModel.Search(query, None)
              SearchApi(query).foreach { results =>
                dispatcher(Action.UpdateSearchResults(query, results))
              }
              value.copy(search = Some(search))
          }

        case Action.UpdateSearchResults(query, results) =>
          value.search.filter(_.query == query).fold(value) { search =>
            val updated = search.copy(results = Some(results))
            value.copy(search = Some(updated))
          }

        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)

          event match {
            case Event.RequestAccepted(infoHash) =>
              value.copy(torrent = Some(TorrentModel(infoHash, 0, Nil, None)))

            case Event.TorrentPeersDiscovered(infoHash, count) if value.torrent.exists(_.infoHash == infoHash) =>
              value.copy(
                torrent = value.torrent.map(_.copy(connected = count))
              )

            case Event.TorrentMetadataReceived(infoHash, files) if value.torrent.exists(_.infoHash == infoHash) =>
              value.torrent match {
                case Some(torrent) =>
                  val metadataFiles = files.map { f =>
                    Metadata.File(
                      f.path,
                      information.Bytes(f.size)
                    )
                  }
                  val metadata = Metadata(metadataFiles)
                  val withMetadata = torrent.withMetadata(metadata)
                  value.copy(torrent = Some(withMetadata))

                case _ => value
              }

            case Event.TorrentError(infoHash, message) if value.torrent.exists(_.infoHash == infoHash) =>
              value.copy(
                torrent = value.torrent.map(_.withError(message))
              )

            case Event.TorrentStats(infoHash, connected, availability)
                if value.torrent.exists(_.infoHash == infoHash) =>
              value.copy(
                torrent = value.torrent.map(_.copy(connected = connected, availability = availability))
              )

            case _ =>
              value
          }
        case Action.Navigate(route) =>
          route match {
            case Route.Root =>
              value
            case Route.Search(query) =>
              this(value, Action.Search(query))
            case Route.Torrent(infoHash) =>
              getTorrent(value, infoHash)
            case Route.File(_, Route.Torrent(infoHash)) =>
              getTorrent(value, infoHash)
          }
      }
    }

    private def getTorrent(model: RootModel, infoHash: InfoHash) = {
      if (model.torrent.exists(_.infoHash == infoHash))
        model
      else {
        send(Command.GetTorrent(infoHash))
        model.copy(
          torrent = Some(TorrentModel(infoHash, 0, Nil, None))
        )
      }
    }
  }

}
