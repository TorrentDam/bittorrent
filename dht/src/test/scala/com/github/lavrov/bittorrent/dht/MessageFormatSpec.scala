package com.github.lavrov.bittorrent.dht

import com.github.lavrov.bittorrent.InfoHash
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.torrentdam.bencode.Bencode
import scodec.bits.ByteVector

class MessageFormatSpec extends munit.FunSuite {

  test("decode ping response") {
    val input = Bencode.BDictionary(
      "ip" -> Bencode.BString(ByteVector.fromValidHex("1f14bdfa9f21")),
      "y" -> Bencode.BString("q"),
      "t" -> Bencode.BString(ByteVector.fromValidHex("6a76679c")),
      "a" -> Bencode.BDictionary(
        "id" -> Bencode.BString(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267"))
      ),
      "q" -> Bencode.BString("ping")
    )

    val result = summon[BencodeFormat[Message]].read(input)
    val expectation = Right(
      Message.QueryMessage(
        ByteVector.fromValidHex("6a76679c"),
        Query.Ping(NodeId(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267")))
      )
    )

    assert(result == expectation)
  }

  test("decode announce_peer query") {
    val input = Bencode.BDictionary(
      "t" -> Bencode.BString(ByteVector.fromValidHex("6a76679c")),
      "y" -> Bencode.BString("q"),
      "q" -> Bencode.BString("announce_peer"),
      "a" -> Bencode.BDictionary(
        "id" -> Bencode.BString(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267")),
        "info_hash" -> Bencode.BString(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267")),
        "port" -> Bencode.BInteger(9999)
      )
    )
    val result = summon[BencodeFormat[Message]].read(input)
    val expectation = Right(
      Message.QueryMessage(
        ByteVector.fromValidHex("6a76679c"),
        Query.AnnouncePeer(
          NodeId(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267")),
          InfoHash(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267")),
          9999L
        )
      )
    )
    assert(result == expectation)
  }
}
