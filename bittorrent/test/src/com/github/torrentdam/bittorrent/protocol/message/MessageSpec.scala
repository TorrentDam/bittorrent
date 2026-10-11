package com.github.torrentdam.bittorrent.protocol.message

import scodec.bits.ByteVector
import scodec.bits.hex

class MessageSpec extends munit.FunSuite {

  test("decode unknown message id") {
    val result = Message.MessageBodyCodec.decodeValue(hex"0d00000001".bits)
    assertEquals(result.toOption, Some(Message.Unknown(13, hex"00000001")))
  }

  test("decode unknown message id without payload") {
    val result = Message.MessageBodyCodec.decodeValue(hex"0e".bits)
    assertEquals(result.toOption, Some(Message.Unknown(14, ByteVector.empty)))
  }

  test("known message id with malformed payload still fails") {
    val result = Message.MessageBodyCodec.decodeValue(hex"0400".bits)
    assert(result.isFailure)
  }

  test("decode known messages") {
    assertEquals(Message.MessageBodyCodec.decodeValue(hex"01".bits).toOption, Some(Message.Unchoke))
    assertEquals(Message.MessageBodyCodec.decodeValue(hex"0400000007".bits).toOption, Some(Message.Have(7)))
  }

  test("framed unknown message round trip") {
    val message = Message.Unknown(13, hex"0102")
    val encoded = Message.MessageCodec.encode(message).require
    assertEquals(encoded.bytes, hex"000000030d0102")
    assertEquals(Message.MessageCodec.decodeValue(encoded).toOption, Some(message))
  }
}
