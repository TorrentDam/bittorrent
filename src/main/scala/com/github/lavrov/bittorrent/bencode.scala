package com.github.lavrov.bittorrent

import atto._
import Atto._
import syntax.refined._
import eu.timepit.refined.numeric._
import cats.syntax.functor._

object bencode {

  sealed trait Bencode
  object Bencode {
    case class String(value: java.lang.String) extends Bencode
    case class Integer(value: scala.Long) extends Bencode
    case class List(values: collection.immutable.List[Bencode]) extends Bencode
    case class Dictionary(values: Map[java.lang.String, Bencode]) extends Bencode
  }

  private val parser: Parser[Bencode] = {

    val stringParser: Parser[Bencode.String] = int.refined[Positive] <~ char(':') flatMap {
      number => manyN(number.value, anyChar).map(_.mkString).map(Bencode.String)
    }

    val integerParser: Parser[Bencode.Integer] = char('i') ~> long <~ char('e') map {
      number => Bencode.Integer(number)
    }

    val listParser: Parser[Bencode.List] = char('l') ~> many(delay(parser)) <~ char('e') map {
      elems => Bencode.List(elems)
    }

    val keyValueParser = stringParser ~ delay(parser) map { case (Bencode.String(key), value) => (key, value) }

    val dictionaryParser: Parser[Bencode.Dictionary]  = char('d') ~> many(keyValueParser) <~ char('e') map {
      elems => Bencode.Dictionary(elems.toMap)
    }

    stringParser.widen[Bencode] | integerParser.widen | listParser.widen | dictionaryParser.widen
  }

  def parse(source: Array[Byte]): ParseResult[Bencode] = parser parseOnly source.map(_.toChar).mkString

}
