import com.github.lavrov.bittorrent.app.protocol.{Command, Event}

class AppCircuit(send: Command => Unit, state: Var[RootModel]) {
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
  def observed: Observed[RootModel] = state
}

trait Dispatcher {
  def apply(action: Action): Unit
}

object AppCircuit {
  def apply(send: String => Unit) = new AppCircuit(
    command => send(upickle.default.write(command)),
    Var(RootModel.initial)
  )
}

case class RootModel(
  connected: Boolean,
  mainPanel: MainPanel,
  torrentPanel: TorrentPanelModel,
  logs: List[String]
)

object RootModel {
  def initial: RootModel = {
    val torrentPanel = TorrentPanelModel()
    RootModel(
      connected = false,
      mainPanel = torrentPanel,
      torrentPanel = torrentPanel,
      logs = List.empty
    )
  }
}

sealed trait MainPanel

case class TorrentPanelModel(
  torrent: Option[TorrentModel] = Option.empty
) extends MainPanel

case class TorrentModel(
  infoHash: String,
  connected: Int,
  metadata: Option[List[List[String]]]
) extends MainPanel {
  def withMetadata(metadata: List[List[String]]): TorrentModel = copy(metadata = Some(metadata))
}
