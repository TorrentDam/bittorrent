package logic

case class RootModel(
  connected: Boolean,
  torrent: Option[TorrentModel],
  logs: List[String]
)

object RootModel {
  def initial: RootModel = {
    RootModel(
      connected = false,
      torrent = None,
      logs = List.empty
    )
  }
}

case class TorrentModel(
  infoHash: String,
  connected: Int,
  metadata: Option[List[List[String]]]
) {
  def withMetadata(metadata: List[List[String]]): TorrentModel = copy(metadata = Some(metadata))
}
