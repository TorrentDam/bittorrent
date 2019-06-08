package com.github.lavrov.bencode

import scodec.bits.ByteVector

sealed trait Bencode

object Bencode {

  case class String(value: ByteVector) extends Bencode {
    override def toString() = s"String(${value.decodeUtf8.getOrElse("0x" + value.toHex)})"
  }
  object String {
    def apply(string: java.lang.String): String =
      new String(ByteVector.encodeString(string).right.get)
    val Emtpy = new String(ByteVector.empty)
  }
  case class Integer(value: scala.Long) extends Bencode
  case class List(values: collection.immutable.List[Bencode]) extends Bencode {
    override def toString() = s"List(${values.mkString(", ")}"
  }
  case class Dictionary(values: Map[java.lang.String, Bencode]) extends Bencode {
    override def toString() = s"Dictionary(${values.mkString(", ")})"
  }
  object Dictionary {
    def apply(values: (java.lang.String, Bencode)*): Dictionary = new Dictionary(values.toMap)
    val Empty: Dictionary = new Dictionary(Map.empty)
  }
}
