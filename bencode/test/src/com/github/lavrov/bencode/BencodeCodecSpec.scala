package com.github.lavrov.bencode

import java.nio.charset.Charset

import verify._

import scodec.bits.{Bases, BitVector}
import scala.language.experimental

object BencodeCodecSpec extends BasicTestSuite {

  implicit val charset = Charset.forName("UTF-8")

  test("encode/decode positive integer") {
    val encoded = encode(Bencode.BInteger(56L))
    assert(decode(encoded) == Right(Bencode.BInteger(56L)))
  }

  test("encode/decode negative integer") {
    val encoded = encode(Bencode.BInteger(-567L))
    assert(decode(encoded) == Right(Bencode.BInteger(-567L)))
  }

  test("decode byte string") {
    val result = decode(BitVector.encodeAscii("2:aa").right.get)
    val expectation = Right(Bencode.BString("aa"))
    assert(result == expectation) 
  }

  test("decode list") {
    val result = decode(BitVector.encodeAscii("l1:a2:bbe").right.get)
    val expectation = Right(Bencode.BList(Bencode.BString("a") :: Bencode.BString("bb") :: Nil))
    assert(result == expectation)
  }

  test("decode dictionary") {
    val result = decode(BitVector.encodeAscii("d1:ai6ee").right.get)
    val expectation = Right(Bencode.BDictionary(Map("a" -> Bencode.BInteger(6))))
    assert(result == expectation)
  }

  test("encode string value") {
    assert(encode(Bencode.BString("test")) == BitVector.encodeString("4:test").right.get)
  }

  test("encode list value") {
    val result = encode(Bencode.BList(Bencode.BString("test") :: Bencode.BInteger(10) :: Nil))
    val expectation =
      BitVector
        .encodeString("l4:testi10ee")
        .right
        .get
    assert(result == expectation)
  }

  test("encode and decode long list (stack safety)") {
    val data = Bencode.BList(List.fill(500)(Bencode.BInteger(0L)))
    def encoded = encode(data)
    assert(decode(encoded) == Right(data))
  }

}
