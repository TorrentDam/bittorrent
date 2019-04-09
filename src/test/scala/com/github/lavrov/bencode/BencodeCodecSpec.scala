package com.github.lavrov.bencode

import java.nio.charset.Charset

import com.github.lavrov.bittorrent.{Info, MetaInfo}
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import scodec.bits.{Bases, BitVector}

class BencodeCodecSpec extends FlatSpec {

  implicit val charset = Charset.forName("UTF-8")

  it should "decode integer" in {
    decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(Bencode.Integer(56L))
  }

  it should "decode byte string" in {
    decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(Bencode.String("aa"))
  }

  it should "decode list" in {
    decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      Bencode.List(Bencode.String("a") :: Bencode.String("bb") :: Nil)
    )
  }

  it should "decode dictionary" in {
    decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      Bencode.Dictionary(Map("a" -> Bencode.Integer(6)))
    )
  }

  it should "encode string value" in {
    encode(Bencode.String("test")) mustBe BitVector.encodeString("4:test").right.get
  }

  it should "encode list value" in {
    encode(Bencode.List(Bencode.String("test") :: Bencode.Integer(10) :: Nil)) mustBe BitVector
      .encodeString("l4:testi10ee")
      .right
      .get
  }

  it should "decode ubuntu torrent" in {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = decode(source)
    val decodeResult = MetaInfo.MetaInfoFormat.read(result)
    decodeResult.isRight mustBe true
  }

  it should "encode file class" in {
    MetaInfo.FileFormat.write(Info.File(77, "abc" :: Nil)) mustBe Right(
      Bencode.Dictionary(
        Map(
          "length" -> Bencode.Integer(77),
          "path" -> Bencode.List(Bencode.String("abc") :: Nil)
        )
      )
    )
  }

  it should "calculate info_hash" in {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = decode(source)
    val decodedResult = MetaInfo.RawInfoFormat.read(result)
    decodedResult
      .map(com.github.lavrov.bittorrent.util.sha1Hash)
      .map(_.toHex(Bases.Alphabets.HexUppercase)) mustBe Right(
      "8C4ADBF9EBE66F1D804FB6A4FB9B74966C3AB609"
    )
  }
}
