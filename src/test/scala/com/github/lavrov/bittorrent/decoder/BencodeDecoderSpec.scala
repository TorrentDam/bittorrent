package com.github.lavrov.bittorrent.decoder

import atto.ParseResult
import com.github.lavrov.bittorrent.{Info, MetaInfo}
import com.github.lavrov.bittorrent.Info.{File, MultipleFileInfo, SingleFileInfo}
import com.github.lavrov.bittorrent.bencode.Bencode
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._

class BencodeDecoderSpec extends FlatSpec {

  it should "decode dictionary" in {
    val input = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String("10"),
        "length" -> Bencode.Integer(10),
      )
    )

    Info.SingleFileInfoDecoder.decode(input) mustBe Right(SingleFileInfo(10, "10", 10, None))
  }

  it should "decode list" in {
    val input = Bencode.List(
      Bencode.String("a") ::
      Bencode.String("b") :: Nil
    )

    val listStringDecoder: BencodeDecoder[List[String]] = implicitly
    listStringDecoder.decode(input) mustBe Right(List("a", "b"))
  }

  it should "decode either a or b" in {
    val input = Bencode.Dictionary(
      Map(
        "piece length" -> Bencode.Integer(10),
        "pieces" -> Bencode.String("10"),
        "length" -> Bencode.Integer(10),
      )
    )

    Info.InfoDecoder.decode(input) mustBe Right(SingleFileInfo(10, "10", 10, None))

    val input1 = Bencode.Dictionary(
      Map(
        "files" -> Bencode.List(
          Bencode.Dictionary(
            Map(
              "info" -> Bencode.Dictionary(
                Map(
                  "piece length" -> Bencode.Integer(10),
                  "pieces" -> Bencode.String("10"),
                  "length" -> Bencode.Integer(10),
                  "md5sum" -> Bencode.String("tadam"),
                )
              ),
              "path" -> Bencode.String("/root")
            )
          ) :: Nil
        )
      )
    )

    Info.InfoDecoder.decode(input1) mustBe Right(MultipleFileInfo(File(SingleFileInfo(10, "10", 10, Some("tadam")), "/root") :: Nil))
  }

  it should "decode ubuntu torrent" in {
    val source = getClass.getClassLoader.getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent").readAllBytes()
    val ParseResult.Done(_, result) = com.github.lavrov.bittorrent.bencode.parse(source)
    val decodeResult = MetaInfo.MetaInfoDecoder.decode(result)
    decodeResult.map(_.announce) mustBe Right("http://torrent.ubuntu.com:6969/announce")
  }
}
