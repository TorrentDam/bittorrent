package com.github.torrentdam.bittorrent.wire

import com.github.torrentdam.bittorrent.wire.RequestDispatcher
import com.github.torrentdam.bittorrent.TorrentFile
import com.github.torrentdam.bittorrent.TorrentMetadata
import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.format.BencodeFormat
import scodec.bits.BitVector

class RequestDispatcherSpec extends munit.FunSuite {

  test("build request queue from torrent metadata") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = bencode.decode(BitVector(source)): @unchecked
    val torrentFile = summon[BencodeFormat[TorrentFile]].read(result).toOption.get
    assert(
      PartialFunction.cond(torrentFile.info) {
        case TorrentMetadata.Lossless(metadata @ TorrentMetadata(_, _, pieces, List(file)), _) =>
          val fileSize = file.length
          val piecesTotal = (pieces.length.toDouble / 20).ceil.toInt
          val workGenerator = RequestDispatcher.WorkGenerator(metadata)
          val queue =
            (0 until piecesTotal).map(pieceIndex => workGenerator.pieceWork(pieceIndex))
          assert(queue.map(_.size).toList.sum == fileSize)
          assert(queue.toList.flatMap(_.requests.toList).map(_.length).sum == fileSize)
          true
      }
    )
  }

}
