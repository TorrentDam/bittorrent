import diode.Circuit
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import diode.ActionHandler
import diode.UseValueEq

class AppCircuit(send: Command => Unit) extends Circuit[RootModel] {
  def initialModel: RootModel = RootModel.initial
  def actionHandler: HandlerFunction = composeHandlers(
    new ActionHandler(zoomTo(_.torrentPanel)) {
      def handle = {
        case Action.DownloadTorrentFile(text) =>
          send(Command.AddTorrent(text))
          noChange
        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)
          event match {
            case Event.NewTorrent(infoHash) =>
              updated(
                value.copy(torrent = Some(TorrentModel(infoHash, 0)))
              )
            case Event.TorrentStats(_, connected) =>
              updated(
                value.copy(
                  torrent = value.torrent.map(_.copy(connected = connected))
                )
              )
            case _ =>
              noChange
          }
      }
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
  torrentPanel: TorrentPanelModel
)

object RootModel {
  def initial: RootModel = {
    val torrentPanel = TorrentPanelModel()
    RootModel(
      mainPanel = torrentPanel,
      torrentPanel = torrentPanel
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
