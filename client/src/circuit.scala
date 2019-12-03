import diode.Circuit
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import diode.ActionHandler
import diode.ActionResult.{ModelUpdate, NoChange}
import diode.UseValueEq

class AppCircuit(send: Command => Unit) extends Circuit[RootModel] {
  def initialModel: RootModel = RootModel.initial
  def actionHandler: HandlerFunction = composeHandlers(
    (value, action) =>
      action match {
        case Action.DownloadTorrentFile(text) =>
          send(Command.AddTorrent(text))
          Some(NoChange)
        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)
          val panel = value.torrentPanel
          val updatedPanel = event match {
            case Event.NewTorrent(infoHash) =>
              panel.copy(torrent = Some(TorrentModel(infoHash, 0)))
            case Event.TorrentStats(_, connected) =>
              panel.copy(
                torrent = panel.torrent.map(_.copy(connected = connected))
              )
            case _ =>
              panel
          }
          Some(
            ModelUpdate(
              value.copy(
                torrentPanel = updatedPanel,
                logs = payload :: value.logs
              )
            )
          )
      }
  )
}

object AppCircuit {
  def apply(send: String => Unit) = new AppCircuit(
    command => send(upickle.default.write(command))
  )
}

case class RootModel(
  mainPanel: MainPanel,
  torrentPanel: TorrentPanelModel,
  logs: List[String]
)

object RootModel {
  def initial: RootModel = {
    val torrentPanel = TorrentPanelModel()
    RootModel(
      mainPanel = torrentPanel,
      torrentPanel = torrentPanel,
      logs = List.empty
    )
  }
}

sealed trait MainPanel extends UseValueEq

case class TorrentPanelModel(
  torrent: Option[TorrentModel] = Option.empty
) extends MainPanel

case class TorrentModel(
  infoHash: String,
  connected: Int
) extends MainPanel
