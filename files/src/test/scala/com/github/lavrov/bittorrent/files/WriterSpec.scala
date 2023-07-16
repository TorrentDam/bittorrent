package com.github.lavrov.bittorrent.files

import com.github.lavrov.bittorrent.TorrentMetadata
import scodec.bits.ByteVector

class WriterSpec extends munit.FunSuite {

  test("one piece one file") {
    val file =
      TorrentMetadata.File(
        1L,
        List("foo.txt")
      )
    val writer = Writer(
      List(file),
      1
    )
    val writes = writer.write(0, ByteVector(0))
    assertEquals(writes, List(Writer.WriteBytes(file, 0, ByteVector(0))))
  }

  test("write piece to second file") {
    val files =
      List(
        TorrentMetadata.File(
          1L,
          List("foo.txt")
        ),
        TorrentMetadata.File(
          1L,
          List("bar.txt")
        )
      )
    val writer = Writer(
      files,
      1
    )
    val writes = writer.write(1, ByteVector(0))
    assertEquals(writes, List(Writer.WriteBytes(files(1), 0, ByteVector(0))))
  }

  test("write piece to both files") {
    val files =
      List(
        TorrentMetadata.File(
          1L,
          List("foo.txt")
        ),
        TorrentMetadata.File(
          1L,
          List("bar.txt")
        )
      )
    val writer = Writer(
      files,
      2
    )
    val writes = writer.write(0, ByteVector(0, 0))
    assertEquals(
      writes,
      List(
        Writer.WriteBytes(files(0), 0, ByteVector(0)),
        Writer.WriteBytes(files(1), 0, ByteVector(0))
      )
    )
  }

  test("start writing in file with offset") {
    val files =
      List(
        TorrentMetadata.File(
          3L,
          List("foo.txt")
        ),
        TorrentMetadata.File(
          1L,
          List("bar.txt")
        )
      )
    val writer = Writer(
      files,
      2
    )
    val writes = writer.write(1, ByteVector(0, 0))
    assertEquals(
      writes,
      List(
        Writer.WriteBytes(files(0), 2, ByteVector(0)),
        Writer.WriteBytes(files(1), 0, ByteVector(0))
      )
    )
  }
}
