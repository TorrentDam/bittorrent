package logic.model

import com.github.lavrov.bittorrent.app.domain.InfoHash
import logic.SearchApi.SearchResults
import squants.Quantity
import squants.information.Information
import component.Router.Route

case class Root(
  connected: Boolean,
  route: Option[Route],
  search: Option[Root.Search],
  torrent: Option[Torrent],
  discovered: Option[Discovered],
  logs: List[String]
)

object Root {
  def initial: Root = {
    Root(
      connected = false,
      route = None,
      search = None,
      torrent = None,
      discovered = None,
      logs = List.empty
    )
  }
  case class Search(
    query: String,
    results: Option[SearchResults]
  )
}

case class Torrent(
  infoHash: InfoHash,
  connected: Int,
  availability: List[Double],
  metadata: Option[Either[String, Metadata]]
) {
  def withMetadata(metadata: Metadata): Torrent = copy(metadata = Some(Right(metadata)))
  def withError(message: String): Torrent = copy(metadata = Some(Left(message)))
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

case class Discovered(torrents: List[(InfoHash, String)])
