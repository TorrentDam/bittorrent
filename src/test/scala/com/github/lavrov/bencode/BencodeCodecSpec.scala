package com.github.lavrov.bencode

import java.nio.charset.Charset

import com.github.lavrov.bittorrent.MetaInfo
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import scodec.DecodeResult
import scodec.bits.BitVector

class BencodeCodecSpec extends FlatSpec {

  implicit val charset = Charset.forName("UTF-8")

  it should "decode integer" in {
    decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(
      DecodeResult(Bencode.Integer(56L), BitVector.empty))
  }

  it should "decode byte string" in {
    decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(
      DecodeResult(Bencode.String("aa"), BitVector.empty))
  }

  it should "decode list" in {
    decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      DecodeResult(Bencode.List(Bencode.String("a") :: Bencode.String("bb") :: Nil), BitVector.empty))
  }

  it should "decode dictionary" in {
    decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      DecodeResult(Bencode.Dictionary(Map("a" -> Bencode.Integer(6))), BitVector.empty))
  }

  it should "encode string value" in {
    encode(Bencode.String("test")) mustBe BitVector.encodeString("4:test")
  }

  it should "encode list value" in {
    encode(Bencode.List(Bencode.String("test") :: Bencode.Integer(10) :: Nil)) mustBe BitVector.encodeString("l4:testi10ee")
  }

  it should "decode ubuntu torrent" in {
    val source = getClass.getClassLoader.getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent").readAllBytes()
    val Right(result) = decode(source)
    val decodeResult = MetaInfo.MetaInfoReader.read(result.value)
    decodeResult.map(_.announce) mustBe Right("http://torrent.ubuntu.com:6969/announce")
  }
}