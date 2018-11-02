package com.github.lavrov.bittorrent.decoder
import com.github.lavrov.bittorrent.Info
import com.github.lavrov.bittorrent.Info.{File, MultipleFileInfo, SingleFileInfo}
import com.github.lavrov.bittorrent.bencode.Bencode
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._

class BencodeDecoderSpec extends FlatSpec {

  it should "decode dictionary" in {
    val input = Bencode.Dictionary(
      Map(
        "pieceLength" -> Bencode.Integer(10),
        "pieces" -> Bencode.Integer(10),
        "length" -> Bencode.Integer(10),
        "md5sum" -> Bencode.String("tadam"),
      )
    )

    Info.SingleFileInfoDecoder.decode(input) mustBe Right(SingleFileInfo(10, 10, 10, "tadam"))
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
        "pieceLength" -> Bencode.Integer(10),
        "pieces" -> Bencode.Integer(10),
        "length" -> Bencode.Integer(10),
        "md5sum" -> Bencode.String("tadam"),
      )
    )

    Info.InfoDecoder.decode(input) mustBe Right(SingleFileInfo(10, 10, 10, "tadam"))

    val input1 = Bencode.Dictionary(
      Map(
        "files" -> Bencode.List(
          Bencode.Dictionary(
            Map(
              "info" -> Bencode.Dictionary(
                Map(
                  "pieceLength" -> Bencode.Integer(10),
                  "pieces" -> Bencode.Integer(10),
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

    Info.InfoDecoder.decode(input1) mustBe Right(MultipleFileInfo(File(SingleFileInfo(10, 10, 10, "tadam"), "/root") :: Nil))
  }
}
