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
          val updatedModel = event match {
            case Event.RequestAccepted(infoHash) =>
              value.copy(torrent = Some(TorrentModel(infoHash, 0, None)))
            case Event.TorrentMetadataReceived(files) =>
              val metadataFiles = files.map { f =>
                Metadata.File(
                  f.path,
                  information.Bytes(f.size)
                )
              }
              val metadata = Metadata(metadataFiles)
              value.copy(torrent = value.torrent.map(_.withMetadata(metadata)))
            case Event.TorrentError(message) =>
              value.copy(torrent = value.torrent.map(_.withError(message)))
            case Event.TorrentStats(_, connected) =>
              value.copy(
                torrent = value.torrent.map(_.copy(connected = connected))
              )
            case _ =>
              value
          }
          Some(
            updatedModel.copy(
              logs = payload :: value.logs
            )
          )
        case Action.Navigate(route) =>
          route match {
            case Route.Root =>
              None
            case Route.Torrent(infoHash) =>
              getTorrent(value, infoHash)
              None
            case Route.File(_, Route.Torrent(infoHash)) =>
              getTorrent(value, infoHash)
              None
          }
      }
  private def getTorrent(model: RootModel, infoHash: String) = {
    if (!model.torrent.exists(_.infoHash == infoHash))
      send(Command.GetTorrent(infoHash))
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
