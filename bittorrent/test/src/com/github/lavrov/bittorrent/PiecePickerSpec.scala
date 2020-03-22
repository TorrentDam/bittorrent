package com.github.lavrov.bittorrent

import com.github.lavrov.bencode
import com.github.lavrov.bittorrent.TestUtils._
import com.github.lavrov.bittorrent.wire.PiecePicker
import verify._

object PiecePickerSpec extends BasicTestSuite {

  test("build request queue from torrent metadata") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(result) = bencode.decode(source)
    val torrentFile = TorrentFile.TorrentFileFormat.read(result).toOption.get
    assert(
      PartialFunction.cond(torrentFile.info) {
        case MetaInfo(metadata @ TorrentMetadata(_, _, List(file)), _) =>
          val fileSize = file.length
          val queue = PiecePicker.buildQueue(metadata)
          assert(queue.map(_.size).toList.sum == fileSize)
          assert(queue.toList.flatMap(_.requests.value).map(_.length).sum == fileSize)
          true
      }
    )
  }

}
