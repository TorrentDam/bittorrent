package com.github.lavrov.bittorrent.protocol

import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import com.github.lavrov.bencode
import com.github.lavrov.bittorrent.{Info, MetaInfo}

class ProtocolSpec extends FlatSpec {

  it should "build queue of pieces to download" in {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = bencode.decode(source)
    val metaInfo = MetaInfo.MetaInfoFormat.read(result).right.get
    val fileSize = metaInfo.info match {
      case f: Info.SingleFile => f.length
    }
    val queue = Downloading.buildQueue(metaInfo)
    queue.map(_.size).toList.sum mustEqual fileSize
    queue.toList.flatMap(_.requests).map(_.length).sum mustEqual fileSize
  }

}
