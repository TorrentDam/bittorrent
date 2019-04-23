package com.github.lavrov.bittorrent.dht

import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.dht.message.{Message, Query}
import org.scalatest.FlatSpec
import scodec.bits.ByteVector
import org.scalatest.MustMatchers._

class MessageFormatSpec extends FlatSpec {

  it should "decode ping response" in {
    val input = Bencode.Dictionary(
      Map(
        "ip" -> Bencode.String(ByteVector.fromValidHex("1f14bdfa9f21")),
        "y" -> Bencode.String("q"),
        "t" -> Bencode.String(ByteVector.fromValidHex("6a76679c")),
        "a" -> Bencode.Dictionary(
          Map(
            "id" -> Bencode.String(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267"))
          )
        ),
        "q" -> Bencode.String("ping")
      )
    )

    Message.MessageFormat.read(input) mustBe Right(
      Message.QueryMessage(
        ByteVector.fromValidHex("6a76679c"),
        Query.Ping(NodeId(ByteVector.fromValidHex("32f54e697351ff4aec29cdbaabf2fbe3467cc267"))))
    )

//    "Dictionary(" +
//      "ip -> String(0x1f14bdfa9f21), " +
//      "y -> String(q), " +
//      "t -> String(0x6a76679c), " +
//      "a -> Dictionary(" +
//        "id -> String(0x32f54e697351ff4aec29cdbaabf2fbe3467cc267)), " +
//      "q -> String(ping))"
  }
}
