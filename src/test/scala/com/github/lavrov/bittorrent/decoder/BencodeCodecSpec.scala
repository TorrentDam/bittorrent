package com.github.lavrov.bittorrent.decoder

import com.github.lavrov.bittorrent.bencode.Bencode
import com.github.lavrov.bittorrent.{MetaInfo, bencode}
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import scodec.DecodeResult
import scodec.bits.BitVector

class BencodeCodecSpec extends FlatSpec {

  it should "decode integer" in {
    bencode.decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(
      DecodeResult(Bencode.Integer(56L), BitVector.empty))
  }

  it should "decode byte string" in {
    bencode.decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(
      DecodeResult(Bencode.String("aa"), BitVector.empty))
  }

  it should "decode list" in {
    bencode.decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      DecodeResult(Bencode.List(Bencode.String("a") :: Bencode.String("bb") :: Nil), BitVector.empty))
  }

  it should "decode dictionary" in {
    bencode.decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      DecodeResult(Bencode.Dictionary(Map("a" -> Bencode.Integer(6))), BitVector.empty))
  }

  it should "decode ubuntu torrent" in {
    val source = getClass.getClassLoader.getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent").readAllBytes()
    val Right(result) = com.github.lavrov.bittorrent.bencode.decode(source)
    val decodeResult = MetaInfo.MetaInfoDecoder.decode(result.value)
    decodeResult.map(_.announce) mustBe Right("http://torrent.ubuntu.com:6969/announce")
  }
}
