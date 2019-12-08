package com.github.lavrov.bittorrent

import verify._
import com.github.lavrov.bencode
import com.github.lavrov.bittorrent.TestUtils._
import com.github.lavrov.bittorrent.wire.TorrentControl

object DownloadTorrentSpec extends BasicTestSuite {

  test("build queue of pieces to download") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(result) = bencode.decode(source)
    val torrentFile = TorrentFile.TorrentFileFormat.read(result).toOption.get
    assert(
      PartialFunction.cond(torrentFile.info) {
        case MetaInfo(metadata @ TorrentMetadata(_, _, List(file)), _) =>
          val fileSize = file.length
          val queue = TorrentControl.buildQueue(metadata)
          assert(queue.map(_.size).toList.sum == fileSize)
          assert(queue.toList.flatMap(_.requests).map(_.length).sum == fileSize)
          true
      }
    )
  }

}
