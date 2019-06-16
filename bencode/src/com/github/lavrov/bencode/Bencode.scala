package com.github.lavrov.bencode

import scodec.bits.ByteVector

sealed trait Bencode

object Bencode {

  case class BString(value: ByteVector) extends Bencode
  case class BInteger(value: Long) extends Bencode
  case class BList(values: List[Bencode]) extends Bencode
  case class BDictionary(values: Map[String, Bencode]) extends Bencode

  object BString {
    def apply(string: String): BString =
      new BString(ByteVector.encodeUtf8(string).right.get)
    val Emtpy = new BString(ByteVector.empty)
  }

  object BDictionary {
    def apply(values: (String, Bencode)*): BDictionary = new BDictionary(values.toMap)
    val Empty: BDictionary = new BDictionary(Map.empty)
  }
}
