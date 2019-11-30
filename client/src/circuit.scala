import diode.Circuit
import com.github.lavrov.bittorrent.app.protocol.Command
import diode.ActionHandler
import diode.UseValueEq

class AppCircuit(send: Command => Unit) extends Circuit[RootModel] {
  def initialModel = RootModel.initial
  def actionHandler: HandlerFunction = composeHandlers(
    new ActionHandler(zoomTo(_.downloadPanelModel)) {
      def handle = {
        case Action.DownloadTorrentFile(text) =>
          send(Command.AddTorrent(text))
          updated(value.copy(downloading = true))
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
  downloadPanelModel: DownloadPanelModel
)

object RootModel {
  def initial = {
    val downloadPanelModel = DownloadPanelModel()
    RootModel(
      mainPanel = downloadPanelModel,
      downloadPanelModel = downloadPanelModel
    )
  }
}

sealed trait MainPanel extends UseValueEq

case class DownloadPanelModel(
  downloading: Boolean = false
) extends MainPanel
