import cats.effect.{ContextShift, IO, Resource}
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
  def make(torrent: Torrent[IO])(implicit cs: ContextShift[IO]): Resource[IO, ServerTorrent] = {

    val doMake =
      for {
        multiplexer <- Multiplexer[IO](torrent.piece)
      } yield {
        new ServerTorrent {
          def files: FileMapping = FileMapping.fromMetadata(torrent.getMetaInfo.parsed)
          def stats: IO[Stats] =
            for {
              stats <- torrent.stats
            } yield Stats(
              connected = stats.connected,
              availability = files.value.map { span =>
                val range = span.beginIndex.toInt to span.endIndex.toInt
                val available = range.count(stats.availability.contains)
                available.toDouble / range.size
              }
            )
          def piece(index: Int): IO[ByteVector] = multiplexer.get(index)
          def metadata: MetaInfo = torrent.getMetaInfo
        }
      }

    Resource.liftF(doMake)
  }

  case class Stats(
    connected: Int,
    availability: List[Double]
  )

}
