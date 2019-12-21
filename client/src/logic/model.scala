package logic

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
