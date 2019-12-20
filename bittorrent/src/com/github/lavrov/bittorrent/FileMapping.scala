package com.github.lavrov.bittorrent

case class FileMapping(value: List[FileMapping.Span])

object FileMapping {
  case class Span(
    beginIndex: Long,
    beginOffset: Long,
    endIndex: Long,
    endOffset: Long
  )

  def fromMetadata(torrentMetadata: TorrentMetadata): FileMapping = {
    def forFile(beginIndex: Long, beginOffset: Long, length: Long): Span = {
      val spansPieces = length / torrentMetadata.pieceLength
      val remainder = length % torrentMetadata.pieceLength
      val endIndex = beginIndex + spansPieces + (beginOffset + remainder) / torrentMetadata.pieceLength
      val endOffset = (beginOffset + remainder) % torrentMetadata.pieceLength
      Span(beginIndex, beginOffset, endIndex, endOffset)
    }
    case class State(beginIndex: Long, beginOffset: Long, spans: List[Span])
    val spans = torrentMetadata.files
      .foldLeft(State(0L, 0L, Nil)) {
        case (state, file) =>
          val span = forFile(state.beginIndex, state.beginOffset, file.length)
          State(span.endIndex, span.endOffset, span :: state.spans)
      }
      .spans
      .reverse
    FileMapping(spans)
  }
}
