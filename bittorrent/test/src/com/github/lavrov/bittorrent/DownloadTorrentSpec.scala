package com.github.lavrov.bittorrent

import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import org.scalatest.Inside.inside
import com.github.lavrov.bencode
import com.github.lavrov.bittorrent.TestUtils._
import com.github.lavrov.bittorrent.TorrentMetadata.Info

class DownloadTorrentSpec extends FlatSpec {

  it should "build queue of pieces to download" in {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(result) = bencode.decode(source)
    val metaInfo = TorrentMetadata.TorrentMetadataFormat.read(result).right.get
    inside(metaInfo.info) {
      case f: Info.SingleFile =>
        val fileSize = f.length
        val queue = DownloadTorrent.buildQueue(f)
        queue.map(_.size).toList.sum mustEqual fileSize
        queue.toList.flatMap(_.requests).map(_.length).sum mustEqual fileSize
    }
  }

}
