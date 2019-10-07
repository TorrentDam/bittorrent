package com.github.lavrov.bittorrent.dht

import verify._

import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.dht.message.{Message, Query}
import scodec.bits.ByteVector

object MessageFormatSpec extends BasicTestSuite {

  test("decode ping response") {
    val input = Bencode.BDictionary(
      Map(
        "ip" -> Bencode.BString(ByteVector.fromValidHex("1f14bdfa9f21")),
        "y" -> Bencode.BString("q"),
        "t" -> Bencode.BString(ByteVector.fromValidHex("6a76679c")),
        "a" -> Bencode.BDictionary(
          Map(
            "id" -> Bencode.BString(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267"))
          )
        ),
        "q" -> Bencode.BString("ping")
      )
    )

    val result = Message.MessageFormat.read(input)
    val expectation = Right(
      Message.QueryMessage(
        ByteVector.fromValidHex("6a76679c"),
        Query.Ping(NodeId(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267"))))
    )

    assert(result == expectation)
//    "Dictionary(" +
//      "ip -> String(0x1f14bdfa9f21), " +
//      "y -> String(q), " +
//      "t -> String(0x6a76679c), " +
//      "a -> Dictionary(" +
//        "id -> String(0x32f54e697351ff4aec29cdbaabf2fbe3467cc267)), " +
//      "q -> String(ping))"
  }
}
