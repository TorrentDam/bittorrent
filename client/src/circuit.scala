import diode.Circuit
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import diode.ActionHandler
import diode.UseValueEq

class AppCircuit(send: Command => Unit) extends Circuit[RootModel] {
  def initialModel: RootModel = RootModel.initial
  def actionHandler: HandlerFunction = composeHandlers(
    new ActionHandler(zoomTo(_.torrentList)) {
      def handle = {
        case Action.DownloadTorrentFile(text) =>
          send(Command.AddTorrent(text))
          noChange
        case Action.ServerEvent(payload) =>
          val event = upickle.default.read[Event](payload)
          event match {
            case Event.NewTorrent(infoHash) =>
              updated(value.copy(torrents = infoHash :: value.torrents))
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
  torrentList: TorrentListModel
)

object RootModel {
  def initial: RootModel = {
    val torrentList = TorrentListModel()
    RootModel(
      mainPanel = torrentList,
      torrentList = torrentList
    )
  }
}

sealed trait MainPanel extends UseValueEq

case class TorrentListModel(
  torrents: List[String] = Nil
) extends MainPanel
