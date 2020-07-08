package logic

import com.github.lavrov.bittorrent.app.domain.InfoHash
import logic.SearchApi.SearchResults
import squants.Quantity
import squants.information.Information

case class RootModel(
  connected: Boolean,
  search: Option[RootModel.Search],
  torrent: Option[TorrentModel],
  logs: List[String]
)

object RootModel {
  def initial: RootModel = {
    RootModel(
      connected = false,
      search = None,
      torrent = None,
      logs = List.empty
    )
  }
  case class Search(
    query: String,
    results: Option[SearchResults]
  )
}

case class TorrentModel(
  infoHash: InfoHash,
  connected: Int,
  availability: List[Double],
  metadata: Option[Either[String, Metadata]]
) {
  def withMetadata(metadata: Metadata): TorrentModel = copy(metadata = Some(Right(metadata)))
  def withError(message: String): TorrentModel = copy(metadata = Some(Left(message)))
}

case class Metadata(
  name: String,
  files: List[Metadata.File]
)
object Metadata {
  case class File(
    path: List[String],
    size: Quantity[Information]
  )
}
