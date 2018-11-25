package com.github.lavrov.bencode

import scodec.bits.ByteVector

sealed trait Bencode

object Bencode {

  case class String(value: ByteVector) extends Bencode
  object String {
    def apply(string: java.lang.String): String = new String(ByteVector.encodeString(string).right.get)
    val Emtpy = new String(ByteVector.empty)
  }
  case class Integer(value: scala.Long) extends Bencode
  case class List(values: collection.immutable.List[Bencode]) extends Bencode
  case class Dictionary(values: Map[java.lang.String, Bencode]) extends Bencode
}

