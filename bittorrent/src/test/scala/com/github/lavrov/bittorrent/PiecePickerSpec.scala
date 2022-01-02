package com.github.lavrov.bittorrent

import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.lavrov.bittorrent.wire.PiecePicker

class PiecePickerSpec extends munit.FunSuite {

  test("build request queue from torrent metadata") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(result) = bencode.decode(source): @unchecked
    val torrentFile = summon[BencodeFormat[TorrentFile]].read(result).toOption.get
    assert(
      PartialFunction.cond(torrentFile.info) {
        case TorrentMetadata.Lossless(metadata @ TorrentMetadata(_, _, _, List(file)), _) =>
          val fileSize = file.length
          val queue = PiecePicker.buildQueue(metadata)
          assert(queue.map(_.size).toList.sum == fileSize)
          assert(queue.toList.flatMap(_.requests.value).map(_.length).sum == fileSize)
          true
      }
    )
  }

}
