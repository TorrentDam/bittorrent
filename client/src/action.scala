import diode.ActionType

sealed trait Action

object Action {
  implicit object aType extends ActionType[Action]
  case object Noop extends Action
  case class DownloadTorrentFile(infoHash: String) extends Action
}