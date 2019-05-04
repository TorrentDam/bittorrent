package com.github.lavrov.bittorrent

import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import cats.data.Chain
import cats.effect.Sync
import fs2.{Sink, Stream}
import scodec.bits.ByteVector

object FileSink {

  case class Piece(begin: Long, bytes: ByteVector)

  def apply[F[_]: Sync](metaInfo: MetaInfo, targetDirectory: Path): Sink[F, Piece] = {
    def openChannel(filePath: Path) =
      Stream.bracket(
        Sync[F].delay {
          val fullFilePath = targetDirectory.resolve(filePath)
          Files.createDirectories(fullFilePath.getParent)
          Files.newByteChannel(
            fullFilePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
          )
        }
      )(
        channel =>
          Sync[F].delay(channel.close())
      )
    metaInfo.info match {
      case Info.SingleFile(name, _, _, _, _) =>
        source =>
          openChannel(Paths get name).flatMap( channel =>
            source.evalMap { piece =>
              Sync[F].delay {
                channel.position(piece.begin)
                channel.write(piece.bytes.toByteBuffer)
                ()
              }
            }
          )
      case Info.MultipleFiles(_, _, files) =>
        val channels = {
          def recur(b0: Long = 0L, list: List[Info.File]): Stream[F, OpenChannel] = list match {
            case f :: tail =>
              val begin = b0
              val until = begin + f.length
              val cons =
                for {
                  channel <- openChannel(Paths.get(f.path.head, f.path.tail: _*))
                }
                yield
                  OpenChannel(begin, until, channel)
              cons ++ recur(until, tail)
            case Nil =>
              Stream.empty
          }
          recur(0L, files)
        }
        source =>
          channels.map(Chain.one).reduceSemigroup.flatMap { channels =>
            def write(begin: Long, bytes: ByteVector): F[Unit] = Sync[F].delay {
              val fileChannel = channels.find(oc => oc.begin <= begin && oc.until > begin).get
              import fileChannel.channel
              val position = begin - fileChannel.begin
              val (thisFileBytes, leftoverBytes) = bytes.splitAt(fileChannel.until - begin)
              channel.position(position)
              channel.write(thisFileBytes.toByteBuffer)
              if (leftoverBytes.nonEmpty)
                write(fileChannel.until, leftoverBytes)
              else
                ()
            }
            source.evalMap { piece =>
              write(piece.begin, piece.bytes)
            }
          }
    }
  }

  private case class OpenChannel(begin: Long, until: Long, channel: SeekableByteChannel)
}
