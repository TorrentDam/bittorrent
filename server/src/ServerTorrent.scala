import cats.effect.{IO, Resource}
import com.github.lavrov.bittorrent.wire.Torrent
import com.github.lavrov.bittorrent.{FileMapping, MetaInfo}
import scodec.bits.ByteVector

trait ServerTorrent {
  def files: FileMapping
  def stats: IO[ServerTorrent.Stats]
  def piece(index: Int): IO[ByteVector]
  def metadata: MetaInfo
}

object ServerTorrent {
  def make(torrent: Torrent[IO]): Resource[IO, ServerTorrent] = {
    val impl =
      new ServerTorrent {
        def files: FileMapping = FileMapping.fromMetadata(torrent.getMetaInfo.parsed)
        def stats: IO[Stats] =
          for {
            stats <- torrent.stats
          } yield Stats(
            connected = stats.connected,
            availability = files.value.map { span =>
              val range = span.beginIndex.toInt to span.endOffset.toInt
              val available = range.count(stats.availability.contains)
              available.toDouble / range.size
            }
          )
        def piece(index: Int): IO[ByteVector] = torrent.piece(index)
        def metadata: MetaInfo = torrent.getMetaInfo
      }
    Resource.pure[IO, ServerTorrent](impl)
  }

  case class Stats(
    connected: Int,
    availability: List[Double]
  )

}
