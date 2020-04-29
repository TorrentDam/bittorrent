package logic
import com.github.lavrov.bittorrent.app.domain.InfoHash
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import component.Router.Route
import frp.{Observable, Var}
import squants.information

import scala.concurrent.ExecutionContext

class Circuit(send: Command => Unit, state: Var[RootModel])(implicit ec: ExecutionContext) {
  def actionHandler: (RootModel, Action) => Option[RootModel] =
    (value, action) =>
      action match {
        case Action.UpdateConnectionStatus(connected) =>
          Some(
            value.copy(connected = connected)
          )
        case Action.Search(query) =>
          value.search match {
            case Some(RootModel.Search(`query`, _)) => None
            case _ =>
              val search = RootModel.Search(query, None)
              SearchApi(query).foreach { results =>
                dispatcher(Action.UpdateSearchResults(query, results))
              }
              val model = value.copy(search = Some(search))
              Some(model)
          }
        case Action.UpdateSearchResults(query, results) =>
          value.search.filter(_.query == query).map { search =>
            val updated = search.copy(results = Some(results))
            value.copy(search = Some(updated))
          }
        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)
          event match {
            case Event.RequestAccepted(infoHash) =>
              Some(
                value.copy(torrent = Some(TorrentModel(infoHash, 0, Nil, None)))
              )
            case Event.TorrentPeersDiscovered(infoHash, count) if value.torrent.exists(_.infoHash == infoHash) =>
              Some(
                value.copy(
                  torrent = value.torrent.map(_.copy(connected = count))
                )
              )
            case Event.TorrentMetadataReceived(infoHash, files) if value.torrent.exists(_.infoHash == infoHash) =>
              value.torrent.map { torrent =>
                val metadataFiles = files.map { f =>
                  Metadata.File(
                    f.path,
                    information.Bytes(f.size)
                  )
                }
                val metadata = Metadata(metadataFiles)
                val withMetadata = torrent.withMetadata(metadata)
                value.copy(torrent = Some(withMetadata))
              }
            case Event.TorrentError(infoHash, message) if value.torrent.exists(_.infoHash == infoHash) =>
              Some(
                value.copy(
                  torrent = value.torrent.map(_.withError(message))
                )
              )
            case Event.TorrentStats(infoHash, connected, availability)
                if value.torrent.exists(_.infoHash == infoHash) =>
              Some(
                value.copy(
                  torrent = value.torrent.map(_.copy(connected = connected, availability = availability))
                )
              )
            case _ =>
              None
          }
        case Action.Navigate(route) =>
          route match {
            case Route.Root =>
              None
            case Route.Torrent(infoHash) =>
              getTorrent(value, infoHash)
            case Route.File(_, Route.Torrent(infoHash)) =>
              getTorrent(value, infoHash)
          }
      }
  private def getTorrent(model: RootModel, infoHash: InfoHash) = {
    if (model.torrent.exists(_.infoHash == infoHash))
      None
    else {
      send(Command.GetTorrent(infoHash))
      Some(
        model.copy(
          torrent = Some(TorrentModel(infoHash, 0, Nil, None))
        )
      )
    }
  }

  val dispatcher: Dispatcher = action => {
    actionHandler(state.value, action).foreach(state.set)
  }
  def observed: Observable[RootModel] = state
}

object Circuit {
  def apply(send: String => Unit)(implicit ec: ExecutionContext) = new Circuit(
    command => send(upickle.default.write(command)),
    Var(RootModel.initial)
  )
}
