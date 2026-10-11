package com.github.torrentdam.bittorrent.files

import com.github.torrentdam.bittorrent.TorrentMetadata
import scala.util.chaining.scalaUtilChainingOps

private def createFileOffsets(files: List[TorrentMetadata.File]) =
  Array
    .ofDim[FileRange](files.length)
    .tap: array =>
      var currentOffset: Long = 0
      files.iterator.zipWithIndex.foreach(
        (file, index) =>
          array(index) = FileRange(file, currentOffset, currentOffset + file.length)
          currentOffset += file.length
      )

extension (fileOffsets: Array[FileRange]) {
  private def matchFiles(start: Long, end: Long): Iterator[FileRange] =
    fileOffsets.iterator
      .dropWhile(_.endOffset <= start)
      .takeWhile(_.startOffset < end)
}

private case class FileRange(file: TorrentMetadata.File, startOffset: Long, endOffset: Long)
