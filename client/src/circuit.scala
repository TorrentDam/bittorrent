import diode.Circuit
import diode.ActionResult
import diode.ActionResult.ModelUpdate
import cats.syntax.all._
import diode.ActionType
import diode.ActionHandler
import slinky.web.html.download
import diode.UseValueEq
import diode.Effect
import scala.concurrent.ExecutionContext

class AppCircuit(send: String => Unit) extends Circuit[RootModel] {
  def initialModel = RootModel.initial
  def actionHandler: HandlerFunction = composeHandlers(
    new ActionHandler(zoomTo(_.downloadPanelModel)) {
      def handle = {
        case Action.DownloadTorrentFile(text) =>
          send(text)
          updated(value.copy(downloading = true))
      }
    }
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
