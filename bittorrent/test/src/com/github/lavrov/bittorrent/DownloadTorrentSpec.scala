package com.github.lavrov.bittorrent

import verify._
import com.github.lavrov.bencode
import com.github.lavrov.bittorrent.TestUtils._
import com.github.lavrov.bittorrent.TorrentMetadata.Info
import com.github.lavrov.bittorrent.wire.TorrentControl

object DownloadTorrentSpec extends BasicTestSuite {

  test("build queue of pieces to download") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(result) = bencode.decode(source)
    val (metaInfoRaw, _) = TorrentMetadata.TorrentMetadataFormatLossless.read(result).toOption.get
    val metaInfo = MetaInfo.fromBencode(metaInfoRaw).map(_.parsed)
    assert(
      PartialFunction.cond(metaInfo) {
        case Some(f: Info.SingleFile) =>
          val fileSize = f.length
          val queue = TorrentControl.buildQueue(f)
          assert(queue.map(_.size).toList.sum == fileSize)
          assert(queue.toList.flatMap(_.requests).map(_.length).sum == fileSize)
          true
      }
    )
  }

}
