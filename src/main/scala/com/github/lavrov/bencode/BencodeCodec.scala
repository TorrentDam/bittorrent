package com.github.lavrov.bencode

import scodec.{Codec, Err}
import scodec.Attempt.{Failure, Successful}
import scodec.bits.ByteVector
import scodec.codecs.{byte, bytes, choice, constant, fallback, lazily, list, provide, variableSizeDelimited, ~}

object BencodeCodec {

  val instance: Codec[Bencode] = {

    val valueCodec = lazily(instance)

    val asciiNumber: Codec[Char] = byte.exmap(
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

    val integerParser: Codec[Bencode.Integer] = (constant('i') ~> positiveNumber('e')).xmap(
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

    val listParser: Codec[Bencode.List] = (constant('l') ~> varLengthList(valueCodec)).xmap(
      elems => Bencode.List(elems),
      list => list.values
    )

    val keyValueParser: Codec[String ~ Bencode] = (stringParser ~ valueCodec).xmap(
      { case (Bencode.String(key), value) => (key.decodeAscii.right.get, value) },
      { case (key, value) => (Bencode.String(ByteVector.encodeString(key).right.get), value) }
    )

    val dictionaryParser: Codec[Bencode.Dictionary] = (constant('d') ~> varLengthList(keyValueParser))
      .xmap(
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

}
