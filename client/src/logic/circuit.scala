package logic
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import frp.{Observable, Var}

class Circuit(send: Command => Unit, state: Var[RootModel]) {
  def actionHandler: (RootModel, Action) => Option[RootModel] =
    (value, action) =>
      action match {
        case Action.UpdateConnectionStatus(connected) =>
          Some(
            value.copy(connected = connected)
          )
        case Action.DownloadTorrentFile(text) =>
          send(Command.AddTorrent(text))
          None
        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)
          val panel = value.torrentPanel
          val updatedPanel = event match {
            case Event.TorrentAccepted(infoHash) =>
              panel.copy(torrent = Some(TorrentModel(infoHash, 0, None)))
            case Event.TorrentMetadataReceived(files) =>
              panel.copy(torrent = panel.torrent.map(_.withMetadata(files)))
            case Event.TorrentStats(_, connected) =>
              panel.copy(
                torrent = panel.torrent.map(_.copy(connected = connected))
              )
            case _ =>
              panel
          }
          Some(
            value.copy(
              torrentPanel = updatedPanel,
              logs = payload :: value.logs
            )
          )
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
