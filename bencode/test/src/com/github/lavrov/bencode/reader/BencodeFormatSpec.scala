package com.github.lavrov.bencode.reader

import com.github.lavrov.bencode.Bencode
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._

class BencodeFormatSpec extends FlatSpec {

  it should "decode list" in {
    val input = Bencode.List(
      Bencode.String("a") ::
        Bencode.String("b") :: Nil
    )

    val listStringReader: BencodeFormat[List[String]] = implicitly
    listStringReader.read(input) mustBe Right(List("a", "b"))
  }

}