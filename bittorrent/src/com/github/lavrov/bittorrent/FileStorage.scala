package com.github.lavrov.bittorrent

import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import cats.implicits._
import cats.effect.{Resource, Sync}
import scodec.bits.ByteVector
import TorrentMetadata.File
import cats.Applicative
import cats.effect.concurrent.Ref
import com.github.lavrov.bittorrent.FileStorage.Piece
import monocle.Lens
import monocle.macros.GenLens

trait FileStorage[F[_]] {
  def save(piece: Piece): F[Unit]
  def stats: F[FileStorage.Stats]
}

object FileStorage {

  case class Piece(begin: Long, bytes: ByteVector)

  case class Stats(downloaded: Int)
  object Stats {
    val downloaded: Lens[Stats, Int] = GenLens[Stats](_.downloaded)
  }

  def apply[F[_]](metadata: TorrentMetadata, targetDirectory: Path)(
    implicit F: Sync[F]
  ): Resource[F, FileStorage[F]] = {
    def openChannel(filePath: Path): Resource[F, SeekableByteChannel] = {
      def acquire = Sync[F].delay {
        val fullFilePath = targetDirectory.resolve(filePath)
        Files.createDirectories(fullFilePath.getParent)
        Files.newByteChannel(
          fullFilePath,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
      }
      def release(channel: SeekableByteChannel) = Sync[F].delay(channel.close())
      Resource.make(acquire)(release)
    }
    def openChannels: Resource[F, List[OpenChannel[F]]] = {
      def acquire = {
        def recur(b0: Long = 0L, list: List[File]): F[List[OpenChannel[F]]] = list match {
          case f :: tail =>
            val begin = b0
            val until = begin + f.length
            val channel =
              openChannel(Paths.get(f.path.head, f.path.tail: _*)).allocated
                .map {
                  case (channel, close) =>
                    OpenChannel(begin, until, channel, close)
                }
            channel.flatMap { channel =>
              recur(until, tail).map(channel :: _)
            }
          case Nil =>
            F.pure(Nil)
        }
        recur(0L, metadata.files)
      }
      def release(channels: List[OpenChannel[F]]) = channels.traverse_(_.close)
      Resource.make(acquire)(release)
    }

    for {
      channels <- openChannels
      statsRef <- Resource.liftF { Ref of Stats(0) }
    } yield new FileStorage[F] {
      def save(piece: Piece): F[Unit] = {
        def writeToChannel(
          fileChannel: OpenChannel[F],
          position: Long,
          bytes: ByteVector
        ): F[Unit] = Sync[F].delay {
          import fileChannel.channel
          channel.position(position)
          channel.write(bytes.toByteBuffer)
        }
        def write(begin: Long, bytes: ByteVector): F[Unit] = {
          val fileChannel =
            channels.find(oc => oc.begin <= begin && oc.until > begin).get
          val position = begin - fileChannel.begin
          val numBytesTillFileEnd = fileChannel.until - begin
          val (thisFileBytes, leftoverBytes) =
            bytes.splitAt(numBytesTillFileEnd)
          writeToChannel(fileChannel, position, thisFileBytes) *>
          F.whenA(leftoverBytes.nonEmpty) {
            write(fileChannel.until, leftoverBytes)
          }
        }
        write(piece.begin, piece.bytes) >> statsRef.update(Stats.downloaded.modify(_ + 1))
      }
      def stats: F[Stats] = statsRef.get
    }
  }

  private case class OpenChannel[F[_]](
    begin: Long,
    until: Long,
    channel: SeekableByteChannel,
    close: F[Unit]
  )

  def noop[F[_]](implicit F: Applicative[F]): FileStorage[F] = new FileStorage[F] {
    def save(piece: Piece): F[Unit] = F.unit
    def stats: F[Stats] = F.pure(Stats(0))
  }
}
