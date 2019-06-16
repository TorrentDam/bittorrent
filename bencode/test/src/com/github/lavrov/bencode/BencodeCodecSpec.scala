package com.github.lavrov.bencode

import java.nio.charset.Charset

import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import scodec.bits.{Bases, BitVector}

class BencodeCodecSpec extends FlatSpec {

  implicit val charset = Charset.forName("UTF-8")

  it should "decode integer" in {
    decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(Bencode.BInteger(56L))
  }

  it should "decode byte string" in {
    decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(Bencode.BString("aa"))
  }

  it should "decode list" in {
    decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      Bencode.BList(Bencode.BString("a") :: Bencode.BString("bb") :: Nil)
    )
  }

  it should "decode dictionary" in {
    decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      Bencode.BDictionary(Map("a" -> Bencode.BInteger(6)))
    )
  }

  it should "encode string value" in {
    encode(Bencode.BString("test")) mustBe BitVector.encodeString("4:test").right.get
  }

  it should "encode list value" in {
    encode(Bencode.BList(Bencode.BString("test") :: Bencode.BInteger(10) :: Nil)) mustBe BitVector
      .encodeString("l4:testi10ee")
      .right
      .get
  }
}
