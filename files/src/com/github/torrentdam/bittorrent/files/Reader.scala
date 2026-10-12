package com.github.torrentdam.bittorrent.files

import cats.effect.IO
import com.github.torrentdam.bittorrent.TorrentMetadata
import scodec.bits.ByteVector

trait Reader {
  def read(pieceIndex: Long): List[Reader.ReadBytes]
}

object Reader {
  
    def fromTorrent(torrent: TorrentMetadata): Reader = apply(torrent.files, torrent.pieceLength)
    case class ReadBytes(file: TorrentMetadata.File, offset: Long, endOffset: Long)
    private[files] def apply(files: List[TorrentMetadata.File], pieceLength: Long): Reader =
      val totalLength = files.map(_.length).sum
      val fileOffsets = createFileOffsets(files)
      new {
        def read(pieceIndex: Long): List[ReadBytes] =
          val pieceStartOffset = pieceIndex * pieceLength
          val pieceEndOffset = math.min(pieceStartOffset + pieceLength, totalLength)
          val fileRanges = fileOffsets.matchFiles(pieceStartOffset, pieceEndOffset)
          fileRanges
            .map(range =>
              ReadBytes(
                range.file,
                math.max(range.startOffset, pieceStartOffset) - range.startOffset,
                math.min(range.endOffset, pieceEndOffset) - range.startOffset
              )
            )
            .toList
      }
}
