package com.github.lavrov.whirpool.decoder
import com.github.lavrov.whirpool.Info
import com.github.lavrov.whirpool.Info.FileInfo
import com.github.lavrov.whirpool.bencode.Bencode
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._

class BencodeDecoderSpec extends FlatSpec {

  it should "test" in {
    val input = Bencode.Dictionary(
      Map(
        "pieceLength" -> Bencode.Integer(10),
        "pieces" -> Bencode.Integer(10),
        "length" -> Bencode.Integer(10),
        "md5sum" -> Bencode.String("tadam"),
      )
    )

    Info.FileInfoDecoder.decode(input) mustBe Right(FileInfo(10, 10, 10, "tadam"))
  }
}
