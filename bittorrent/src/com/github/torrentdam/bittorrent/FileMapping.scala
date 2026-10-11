package com.github.torrentdam.bittorrent

import com.github.torrentdam.bittorrent.FileMapping.FileSpan
import com.github.torrentdam.bittorrent.FileMapping.PieceInFile
import com.github.torrentdam.bittorrent.TorrentMetadata

case class FileMapping(value: List[FileSpan], pieceLength: Long) {
  def mapToFiles(pieceIndex: Long, length: Long): List[PieceInFile] =
    value
      .filter(span => span.beginIndex <= pieceIndex && span.endIndex >= pieceIndex)
      .map(span => pieceInFile(span, pieceIndex, length))

  private def pieceInFile(span: FileSpan, pieceIndex: Long, length: Long): PieceInFile =
    val beginOffset = (pieceIndex - span.beginIndex) * pieceLength + span.beginOffset
    val writeLength = Math.min(length, span.length - beginOffset)
    PieceInFile(span.fileIndex, beginOffset, writeLength)
}

object FileMapping {
  case class FileSpan(
    fileIndex: Int,
    length: Long,
    beginIndex: Long,
    beginOffset: Long,
    endIndex: Long,
    endOffset: Long
  )
  case class PieceInFile(
    fileIndex: Int,
    offset: Long,
    length: Long
  )
  def fromMetadata(torrentMetadata: TorrentMetadata): FileMapping =
    def forFile(fileIndex: Int, beginIndex: Long, beginOffset: Long, length: Long): FileSpan = {
      val spansPieces = length / torrentMetadata.pieceLength
      val remainder = length % torrentMetadata.pieceLength
      val endIndex = beginIndex + spansPieces + (beginOffset + remainder) / torrentMetadata.pieceLength
      val endOffset = (beginOffset + remainder) % torrentMetadata.pieceLength
      FileSpan(fileIndex, length, beginIndex, beginOffset, endIndex, endOffset)
    }
    case class State(beginIndex: Long, beginOffset: Long, spans: List[FileSpan])
    val spans =
      torrentMetadata.files.zipWithIndex
        .foldLeft(State(0L, 0L, Nil)) { case (state, (file, index)) =>
          val span = forFile(index, state.beginIndex, state.beginOffset, file.length)
          State(span.endIndex, span.endOffset, span :: state.spans)
        }
        .spans
        .reverse
    FileMapping(spans, torrentMetadata.pieceLength)
}
