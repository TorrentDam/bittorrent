package logic
import squants.Quantity
import squants.information.Information

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
  metadata: Option[Either[String, Metadata]]
) {
  def withMetadata(metadata: Metadata): TorrentModel = copy(metadata = Some(Right(metadata)))
  def withError(message: String): TorrentModel = copy(metadata = Some(Left(message)))
}

case class Metadata(
  files: List[Metadata.File]
)
object Metadata {
  case class File(
    path: List[String],
    size: Quantity[Information]
  )
}
