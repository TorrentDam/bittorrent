import cats.effect.{ContextShift, IO, Resource}

import com.github.lavrov.bittorrent.wire.{PieceStore, Torrent}
import com.github.lavrov.bittorrent.{FileMapping, MetaInfo}
import fs2.Stream

trait ServerTorrent {
  def files: FileMapping
  def stats: IO[ServerTorrent.Stats]
  def piece(index: Int): IO[Stream[IO, Byte]]
  def metadata: MetaInfo
}

object ServerTorrent {
  def make(torrent: Torrent[IO], pieceStore: PieceStore[IO])(
    implicit cs: ContextShift[IO]
  ): Resource[IO, ServerTorrent] = {

    def fetch(index: Int): IO[Stream[IO, Byte]] = {
      for {
        bytes <- pieceStore.get(index)
        bytes <- bytes match {
          case Some(bytes) => IO.pure(bytes)
          case None =>
            for {
              bytes <- torrent.piece(index)
              bytes <- pieceStore.put(index, bytes)
            } yield bytes
        }
      } yield bytes
    }

    val doMake =
      for {
        multiplexer <- Multiplexer[IO](fetch)
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
          def piece(index: Int): IO[Stream[IO, Byte]] = multiplexer.get(index)
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
