package com.github.lavrov.bittorrent.wire

import cats.effect.implicits._
import cats.effect.kernel.{Async, Resource}
import cats.effect.kernel.syntax.all._
import cats.implicits._
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import org.typelevel.log4cats.{Logger, StructuredLogger}
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet

trait Torrent[F[_]] {
  def metadata: TorrentMetadata.Lossless
  def stats: F[Torrent.Stats]
  def piece(index: Int): F[ByteVector]
}

object Torrent {

  def make[F[_]](
    metadata: TorrentMetadata.Lossless,
    swarm: Swarm[F]
  )(implicit F: Async[F], logger: StructuredLogger[F]): Resource[F, Torrent[F]] =
      for {
        piecePicker <- Resource.eval { PiecePicker(metadata.parsed) }
        _ <- F.background(Download(swarm, piecePicker))
      } yield {
        val metadata0 = metadata
        new Torrent[F] {
          def metadata: TorrentMetadata.Lossless = metadata0
          def stats: F[Stats] =
            for {
              connected <- swarm.connected.list
              availability <- connected.traverse(_.availability.get)
              availability <- availability.foldMap(identity).pure[F]
            } yield Stats(connected.size, availability)
          def piece(index: Int): F[ByteVector] =
            piecePicker.download(index)
        }
      }

  case class Stats(
    connected: Int,
    availability: BitSet
  )

  sealed trait Error extends Exception
  object Error {
    case class EmptyMetadata() extends Error
  }
}
