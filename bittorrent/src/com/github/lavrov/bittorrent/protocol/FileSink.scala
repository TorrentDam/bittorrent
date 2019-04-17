package com.github.lavrov.bittorrent.protocol

import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import com.github.lavrov.bittorrent.{Info, MetaInfo}
import scodec.bits.ByteVector

trait FileSink extends AutoCloseable {

  def write(index: Long, begin: Long, bytes: ByteVector): Unit

}

object FileSink {
  def apply(metaInfo: MetaInfo, targetDirectory: Path): FileSink = {
    def openChannel(filePath: Path) = {
      val fullFilePath = targetDirectory.resolve(filePath)
      Files.createDirectories(fullFilePath.getParent)
      Files.newByteChannel(
          fullFilePath,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
    metaInfo.info match {
      case Info.SingleFile(name, _, _, _, _) =>
        val channel = openChannel(Paths get name)
        new FileSink {
          def write(index: Long, begin: Long, bytes: ByteVector): Unit = {
            channel.position(begin)
            channel.write(bytes.toByteBuffer)
          }
          def close(): Unit = channel.close()
        }
      case Info.MultipleFiles(_, _, files) =>
        val channels = {
          var b0 = 0L
          files.map {f =>
            val begin = b0
            val until = begin + f.length
            b0 = until
            val channel = openChannel(Paths.get(f.path.head, f.path.tail: _*))
            OpenChannel(begin, until, channel)
          }
        }
        new FileSink {
          def write(index: Long, begin: Long, bytes: ByteVector): Unit = {
            val fileChannel = channels.find(oc => oc.begin <= begin && oc.until > begin).get
            import fileChannel.channel
            val position = begin - fileChannel.begin
            val (thisFileBytes, leftoverBytes) = bytes.splitAt(fileChannel.until - begin)
            channel.position(position)
            channel.write(thisFileBytes.toByteBuffer)
            if (leftoverBytes.nonEmpty)
              write(index, fileChannel.until, leftoverBytes)
          }
          def close(): Unit = channels.foreach(_.channel.close())
        }
    }
  }

  private case class OpenChannel(begin: Long, until: Long, channel: SeekableByteChannel)
}
