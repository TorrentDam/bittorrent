package com.github.lavrov.bittorrent.protocol

import java.nio.file.{Files, Path, StandardOpenOption}

import com.github.lavrov.bittorrent.{Info, MetaInfo}
import scodec.bits.ByteVector

trait FileSink extends AutoCloseable {

  def write(index: Long, begin: Long, bytes: ByteVector): Unit

}

object FileSink {
  def apply(metaInfo: MetaInfo, targetDirectory: Path): FileSink = {
    Files.createDirectories(targetDirectory)
    metaInfo.info match {
      case Info.SingleFile(name, _, _, _, _) =>
        val targetFile = targetDirectory.resolve(name)
        val channel = Files.newByteChannel(
          targetFile,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        new FileSink {
          def write(index: Long, begin: Long, bytes: ByteVector): Unit = {
            channel.position(begin)
            channel.write(bytes.toByteBuffer)
          }
          def close(): Unit = channel.close()
        }
    }
  }
}
