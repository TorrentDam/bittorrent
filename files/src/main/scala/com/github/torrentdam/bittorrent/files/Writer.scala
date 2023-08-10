package com.github.torrentdam.bittorrent.files

import cats.effect.IO
import com.github.torrentdam.bittorrent.TorrentMetadata
import scodec.bits.ByteVector

import util.chaining.scalaUtilChainingOps

trait Writer {
  def write(index: Long, bytes: ByteVector): List[Writer.WriteBytes]
}

object Writer {

  def fromTorrent(torrent: TorrentMetadata): Writer = apply(torrent.files, torrent.pieceLength)
  case class WriteBytes(file: TorrentMetadata.File, offset: Long, bytes: ByteVector)
  private[files] def apply(files: List[TorrentMetadata.File], pieceLength: Long): Writer =
    val fileOffsets = createFileOffsets(files)

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
      def write(pieceIndex: Long, bytes: ByteVector): List[WriteBytes] =
        val pieceOffset = pieceIndex * pieceLength
        val files = fileOffsets.matchFiles(pieceOffset, pieceOffset + bytes.length)
        val writes = distribute(pieceOffset, bytes, files)
        writes.toList
    }
}
