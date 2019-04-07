package com.github.lavrov.bencode.reader

import com.github.lavrov.bencode
import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.{Info, MetaInfo}
import com.github.lavrov.bittorrent.Info.{File, MultipleFiles, SingleFile}
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import scodec.bits.{BitVector, ByteVector}

class BencodeFormatSpec extends FlatSpec {

  it should "decode dictionary" in {
    val input = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String(ByteVector(10)),
        "length" -> Bencode.Integer(10)
      )
    )

    MetaInfo.SingleFileFormat.read(input) mustBe Right(SingleFile(10, ByteVector(10), 10, None))
  }

  it should "decode list" in {
    val input = Bencode.List(
      Bencode.String("a") ::
        Bencode.String("b") :: Nil
    )

    val listStringReader: BencodeFormat[List[String]] = implicitly
    listStringReader.read(input) mustBe Right(List("a", "b"))
  }

  it should "decode either a or b" in {
    val input = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String.Emtpy,
        "length" -> Bencode.Integer(10)
      )
    )

    MetaInfo.InfoFormat.read(input) mustBe Right(SingleFile(10, ByteVector.empty, 10, None))

    val input1 = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String.Emtpy,
        "files" -> Bencode.List(
          Bencode.Dictionary(
            Map(
              "length" -> Bencode.Integer(10),
              "path" -> Bencode.List(Bencode.String("/root") :: Nil)
            )
          ) :: Nil
        )
      )
    )

    MetaInfo.InfoFormat.read(input1) mustBe Right(
      MultipleFiles(10, ByteVector.empty, File(10, "/root" :: Nil) :: Nil)
    )
  }

  it should "decode ubuntu torrent" in {
    bencode.decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(Bencode.Integer(56L))
    bencode.decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(Bencode.String("aa"))
    bencode.decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      Bencode.List(Bencode.String("a") :: Bencode.String("bb") :: Nil)
    )
    bencode.decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      Bencode.Dictionary(Map("a" -> Bencode.Integer(6)))
    )
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = bencode.decode(source)
    val decodeResult = MetaInfo.MetaInfoFormat.read(result)
    decodeResult.map(_.announce) mustBe Right("http://torrent.ubuntu.com:6969/announce")
  }
}
