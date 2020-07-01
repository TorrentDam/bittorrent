package com.github.lavrov.bittorrent

import scodec.bits.ByteVector
import verify.BasicTestSuite
import TorrentMetadata.File
import FileMapping.Span

object FileMappingSpec extends BasicTestSuite {

  test("all files in one piece") {
    val metadata = TorrentMetadata(
      name = "test",
      pieceLength = 10,
      pieces = ByteVector.empty,
      files = List(
        File(5, Nil),
        File(3, Nil),
        File(2, Nil)
      )
    )
    val result = FileMapping.fromMetadata(metadata)
    val expectation = FileMapping(
      List(
        Span(0, 0, 0, 5, 10),
        Span(0, 5, 0, 8, 10),
        Span(0, 8, 1, 0, 10)
      )
    )
    assert(result == expectation)
  }

  test("file spans multiple pieces") {
    val metadata = TorrentMetadata(
      name = "test",
      pieceLength = 10,
      pieces = ByteVector.empty,
      files = List(
        File(5, Nil),
        File(13, Nil),
        File(2, Nil)
      )
    )
    val result = FileMapping.fromMetadata(metadata)
    val expectation = FileMapping(
      List(
        Span(0, 0, 0, 5, 10),
        Span(0, 5, 1, 8, 10),
        Span(1, 8, 2, 0, 10)
      )
    )
    assert(result == expectation)
  }
}
