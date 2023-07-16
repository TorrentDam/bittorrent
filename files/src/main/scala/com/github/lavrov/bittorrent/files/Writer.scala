package com.github.lavrov.bittorrent.files

import cats.effect.IO
import com.github.lavrov.bittorrent.TorrentMetadata
import scodec.bits.ByteVector

import util.chaining.scalaUtilChainingOps

trait Writer {
  def write(index: Long, bytes: ByteVector): List[Writer.WriteBytes]
}

object Writer {

  def fromTorrent(torrent: TorrentMetadata): Writer = apply(torrent.files, torrent.pieceLength)
  private[files] def apply(files: List[TorrentMetadata.File], pieceLength: Long): Writer =
    val fileOffsets =
      Array
        .ofDim[FileRange](files.length)
        .tap: array =>
          var currentOffset: Long = 0
          files.iterator.zipWithIndex.foreach(
            (file, index) =>
              array(index) = FileRange(file, currentOffset, currentOffset + file.length)
              currentOffset += file.length
          )
    def matchFiles(start: Long, end: Long): Iterator[FileRange] =
      fileOffsets.iterator
        .dropWhile(_.endOffset <= start)
        .takeWhile(_.startOffset < end)

    def distribute(pieceOffset: Long, byteVector: ByteVector, files: Iterator[FileRange]): Iterator[WriteBytes] =
      var remainingBytes = byteVector
      var bytesOffset = pieceOffset
      files.map: fileRange =>
        val offsetInFile = bytesOffset - fileRange.startOffset
        val lengthToWrite = math.min(remainingBytes.length, fileRange.file.length - offsetInFile)
        val (bytesToWrite, rem) = remainingBytes.splitAt(lengthToWrite)
        remainingBytes = rem
        bytesOffset += lengthToWrite
        WriteBytes(
          fileRange.file,
          offsetInFile,
          bytesToWrite
        )
    new {
      def write(index: Long, bytes: ByteVector): List[WriteBytes] =
        val pieceOffset = index * pieceLength
        val files = matchFiles(pieceOffset, pieceOffset + bytes.length)
        val writes = distribute(pieceOffset, bytes, files)
        writes.toList
    }

  private case class FileRange(file: TorrentMetadata.File, startOffset: Long, endOffset: Long)
  case class WriteBytes(file: TorrentMetadata.File, offset: Long, bytes: ByteVector)
}
