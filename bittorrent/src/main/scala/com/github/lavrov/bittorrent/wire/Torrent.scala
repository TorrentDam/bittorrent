package com.github.lavrov.bittorrent.wire

import cats.effect.implicits.*
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.{Async, Resource}
import cats.effect.kernel.syntax.all.*
import cats.implicits.*
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import org.legogroup.woof.{Logger, given}
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet

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
            yield
              Stats(connected.size, availability)
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
