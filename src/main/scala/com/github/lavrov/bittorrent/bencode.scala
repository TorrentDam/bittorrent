package com.github.lavrov.bittorrent

import java.nio.charset.Charset

import scodec._
import codecs._
import scodec.Attempt.{Failure, Successful}
import scodec.bits.{BitVector, ByteVector}

object bencode {
  implicit private val charset = Charset.forName("US-ASCII")

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

  private val codec: Codec[Bencode] = {

    val valueCodec = lazily(codec)

    val asciiNumber = byte.exmap[Char](
      b => if (b.toChar.isDigit) Successful(b.toChar) else Failure(Err("not a digit")),
      c => Successful(c.toByte)
    )

    def positiveNumber(delimiter: Char) = variableSizeDelimited(constant(delimiter), list(asciiNumber), 8).xmap[Long](
      chars => java.lang.Long.parseLong(chars.mkString),
      integer => integer.toString.toList
    )

    val stringParser: Codec[Bencode.String] =
      positiveNumber(':')
        .consume(
          number =>
            bytes(number.toInt).xmap[Bencode.String](
              bv => Bencode.String(bv),
              bs => bs.value
            )
        )(
          _.value.size.toInt
        )

    val integerParser: Codec[Bencode.Integer] = (constant('i') ~> positiveNumber('e')).xmap[Bencode.Integer](
      number => Bencode.Integer(number),
      integer => integer.value
    )

    def varLengthList[A](codec: Codec[A]): Codec[List[A]] = fallback(constant('e'), codec)
      .consume[List[A]]{
        case Left(_) => provide(Nil)
        case Right(bc) => varLengthList(codec).xmap(
          tail => bc :: tail,
          list => list.tail
        )
      }{
        case Nil => Left(())
        case head :: _ => Right(head)
      }

    val listParser: Codec[Bencode.List] = (constant('l') ~> varLengthList(valueCodec)).xmap[Bencode.List](
        elems => Bencode.List(elems),
        list => list.values
      )

    val keyValueParser = (stringParser ~ valueCodec).xmap[String ~ Bencode](
      { case (Bencode.String(key), value) => (key.decodeAscii.right.get, value) },
      { case (key, value) => (Bencode.String(ByteVector.encodeString(key).right.get), value) }
    )

    val dictionaryParser: Codec[Bencode.Dictionary] = (constant('d') ~> varLengthList(keyValueParser))
      .xmap[Bencode.Dictionary](
        elems => Bencode.Dictionary(elems.toMap),
        dict => dict.values.toList
      )

    choice(
      stringParser.upcast[Bencode],
      integerParser.upcast[Bencode],
      listParser.upcast[Bencode],
      dictionaryParser.upcast[Bencode]
    )
  }

  def decode(source: BitVector): Either[Err, DecodeResult[Bencode]] = codec.decodeOnly.decode(source).toEither

  def decode(source: Array[Byte]): Either[Err, DecodeResult[Bencode]] = decode(BitVector(source))

  def encode(value: Bencode): Either[Err, BitVector] = codec.encode(value).toEither
}
