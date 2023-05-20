package com.github.lavrov.bittorrent

import scodec.bits.ByteVector
import FileMapping.FileSpan
import TorrentMetadata.File

class FileMappingSpec extends munit.FunSuite {

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
        FileSpan(0, 5, 0, 0, 0, 5),
        FileSpan(1, 3, 0, 5, 0, 8),
        FileSpan(2, 2, 0, 8, 1, 0)
      ),
      pieceLength = 10
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
        FileSpan(0, 5, 0, 0, 0, 5),
        FileSpan(1, 13, 0, 5, 1, 8),
        FileSpan(2, 2, 1, 8, 2, 0)
      ),
      pieceLength = 10
    )
    assert(result == expectation)
  }
}
