package com.github.lavrov.bencode.reader

import com.github.lavrov.bencode
import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.{Info, MetaInfo}
import com.github.lavrov.bittorrent.Info.{File, MultipleFileInfo, SingleFileInfo}
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import scodec.DecodeResult
import scodec.bits.{BitVector, ByteVector}

class BencodeReaderSpec extends FlatSpec {

  BencodeReader

  it should "decode dictionary" in {
    val input = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String(ByteVector(10)),
        "length" -> Bencode.Integer(10),
      )
    )

    Info.SingleFileInfoReader.read(input) mustBe Right(SingleFileInfo(10, ByteVector(10), 10, None))
  }

  it should "decode list" in {
    val input = Bencode.List(
      Bencode.String("a") ::
      Bencode.String("b") :: Nil
    )

    val listStringReader: BencodeReader[List[String]] = implicitly
    listStringReader.read(input) mustBe Right(List("a", "b"))
  }

  it should "decode either a or b" in {
    val input = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String.Emtpy,
        "length" -> Bencode.Integer(10),
      )
    )

    Info.InfoReader.read(input) mustBe Right(SingleFileInfo(10, ByteVector.empty, 10, None))

    val input1 = Bencode.Dictionary(
      Map(
        "files" -> Bencode.List(
          Bencode.Dictionary(
            Map(
              "info" -> Bencode.Dictionary(
                Map(
                  "piece length" -> Bencode.Integer(10),
                  "pieces" -> Bencode.String.Emtpy,
                  "length" -> Bencode.Integer(10),
                  "md5sum" -> Bencode.String.Emtpy,
                )
              ),
              "path" -> Bencode.String("/root")
            )
          ) :: Nil
        )
      )
    )

    Info.InfoReader.read(input1) mustBe Right(MultipleFileInfo(File(SingleFileInfo(10, ByteVector.empty, 10, Some(ByteVector.empty)), "/root") :: Nil))
  }

  it should "decode ubuntu torrent" in {
    bencode.decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(
      DecodeResult(Bencode.Integer(56L), BitVector.empty))
    bencode.decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(
      DecodeResult(Bencode.String("aa"), BitVector.empty))
    bencode.decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      DecodeResult(Bencode.List(Bencode.String("a") :: Bencode.String("bb") :: Nil), BitVector.empty))
    bencode.decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      DecodeResult(Bencode.Dictionary(Map("a" -> Bencode.Integer(6))), BitVector.empty))
    val source = getClass.getClassLoader.getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent").readAllBytes()
    val Right(result) = bencode.decode(source)
    val decodeResult = MetaInfo.MetaInfoReader.read(result.value)
    decodeResult.map(_.announce) mustBe Right("http://torrent.ubuntu.com:6969/announce")
  }
}
