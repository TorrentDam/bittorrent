sealed trait Action

object Action {
  case class DownloadTorrentFile(infoHash: String) extends Action
  case class ServerEvent(payload: String) extends Action
  case class UpdateConnectionStatus(connected: Boolean) extends Action
}
