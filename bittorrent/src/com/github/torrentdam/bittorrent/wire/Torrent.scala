package com.github.torrentdam.bittorrent.wire

import cats.effect.implicits.*
import cats.effect.kernel.syntax.all.*
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*
import com.github.torrentdam.bittorrent.TorrentMetadata
import com.github.torrentdam.bittorrent.TorrentMetadata.Lossless
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scala.collection.immutable.BitSet
import scodec.bits.ByteVector

trait Torrent {
  def metadata: TorrentMetadata.Lossless
  def stats: IO[Torrent.Stats]
  def downloadPiece(index: Long): IO[ByteVector]
}

object Torrent {

  def make(
    metadata: TorrentMetadata.Lossless,
    swarm: Swarm
  )(using logger: Logger[IO]): Resource[IO, Torrent] =
    for
      requestDispatcher <- RequestDispatcher(metadata.parsed)
      _ <- Download(swarm, requestDispatcher).background
    yield
      val metadata0 = metadata
      new Torrent {
        def metadata: TorrentMetadata.Lossless = metadata0
        def stats: IO[Stats] =
          for
            connected <- swarm.connected.list
            availability <- connected.traverse(_.availability.get)
            availability <- availability.foldMap(identity).pure[IO]
          yield Stats(connected.size, availability)
        def downloadPiece(index: Long): IO[ByteVector] =
          requestDispatcher.downloadPiece(index)
      }
    end for

  case class Stats(
    connected: Int,
    availability: BitSet
  )

  enum Error extends Exception:
    case EmptyMetadata()
}
