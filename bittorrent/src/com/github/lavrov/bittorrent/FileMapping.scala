package com.github.lavrov.bittorrent

case class FileMapping(value: List[FileMapping.Span])

object FileMapping {
  case class Span(
    beginIndex: Long,
    beginOffset: Long,
    endIndex: Long,
    endOffset: Long,
    pieceLength: Long
  ) {
    def advance(begin: Long): Span = {
      val spansPieces = begin / pieceLength
      val remainder = begin % pieceLength
      val index = beginIndex + spansPieces + (beginOffset + remainder) / pieceLength
      val offset = (beginOffset + remainder) % pieceLength
      copy(beginIndex = index, beginOffset = offset)
    }
    def take(bytes: Long): Span = {
      val s0 = advance(bytes)
      copy(endIndex = s0.beginIndex, endOffset = beginOffset)
    }
  }

  def fromMetadata(torrentMetadata: TorrentMetadata): FileMapping = {
    def forFile(beginIndex: Long, beginOffset: Long, length: Long): Span = {
      val spansPieces = length / torrentMetadata.pieceLength
      val remainder = length % torrentMetadata.pieceLength
      val endIndex = beginIndex + spansPieces + (beginOffset + remainder) / torrentMetadata.pieceLength
      val endOffset = (beginOffset + remainder) % torrentMetadata.pieceLength
      Span(beginIndex, beginOffset, endIndex, endOffset, torrentMetadata.pieceLength)
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
