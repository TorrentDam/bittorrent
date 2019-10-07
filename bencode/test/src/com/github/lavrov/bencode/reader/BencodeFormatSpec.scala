package com.github.lavrov.bencode.format

import verify._

import com.github.lavrov.bencode.Bencode

object BencodeFormatSpec extends BasicTestSuite {

  test("decode list") {
    val input = Bencode.BList(
      Bencode.BString("a") ::
        Bencode.BString("b") :: Nil
    )
    val listStringReader: BencodeFormat[List[String]] = implicitly

    assert(listStringReader.read(input) == Right(List("a", "b")))
  }

}
