package com.github.lavrov.bencode.format

import com.github.lavrov.bencode.Bencode
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._

class BencodeFormatSpec extends FlatSpec {

  it should "decode list" in {
    val input = Bencode.BList(
      Bencode.BString("a") ::
        Bencode.BString("b") :: Nil
    )

    val listStringReader: BencodeFormat[List[String]] = implicitly
    listStringReader.read(input) mustBe Right(List("a", "b"))
  }

}
