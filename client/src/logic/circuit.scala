package logic
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import component.Router.Route
import frp.{Observable, Var}
import squants.information

class Circuit(send: Command => Unit, state: Var[RootModel]) {
  def actionHandler: (RootModel, Action) => Option[RootModel] =
    (value, action) =>
      action match {
        case Action.UpdateConnectionStatus(connected) =>
          Some(
            value.copy(connected = connected)
          )
        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)
          event match {
            case Event.RequestAccepted(infoHash) =>
              Some(
                value.copy(torrent = Some(TorrentModel(infoHash, 0, None)))
              )
            case Event.TorrentMetadataReceived(infoHash, files) =>
              value.torrent.filter(_.infoHash == infoHash).map { torrent =>
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
            case Event.TorrentError(message) =>
              Some(
                value.copy(torrent = value.torrent.map(_.withError(message)))
              )
            case Event.TorrentStats(_, connected) =>
              Some(
                value.copy(
                  torrent = value.torrent.map(_.copy(connected = connected))
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
  private def getTorrent(model: RootModel, infoHash: String) = {
    if (model.torrent.exists(_.infoHash == infoHash))
      None
    else {
      send(Command.GetTorrent(infoHash))
      Some(
        model.copy(
          torrent = Some(TorrentModel(infoHash, 0, None))
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
  def apply(send: String => Unit) = new Circuit(
    command => send(upickle.default.write(command)),
    Var(RootModel.initial)
  )
}
