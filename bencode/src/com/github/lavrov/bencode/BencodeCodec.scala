package com.github.lavrov.bencode

import scodec.{Attempt, Codec, DecodeResult, Decoder, Encoder, Err, SizeBound}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._

object BencodeCodec {

  val instance: Codec[Bencode] = {

    // reference to `instance` to use it recursively in its definition
    val valueCodec = lazily(instance)

    // [0-9] characters
    val asciiDigit: Codec[Char] = byte.exmap(
      b => {
        val char = b.toChar
        if (char.isDigit) Attempt.Successful(char) else Attempt.Failure(Err(s"$char not a digit"))
      },
      c => Attempt.Successful(c.toByte)
    )

    // sequence of non-negative digits delimited by `delimiter`
    val positiveNumber: Codec[Long] =
      listSuccessful(asciiDigit).exmap[Long](
        {
          case Nil => Attempt.failure(Err("No digits provided"))
          case nonEmpty => Attempt.successful(nonEmpty.mkString.toLong)
        },
        integer => Attempt.successful(integer.toString.toList)
      )

    def number: Codec[Long] = {
      val positiveInverted = positiveNumber.xmap[Long](v => -v, v => -v)
      recover(constant('-'))
        .consume {
          case true => positiveInverted
          case false => positiveNumber
        } { value =>
          value < 0
        }
    }

    // actual bencode codec

    val stringCodec: Codec[Bencode.BString] =
      (positiveNumber <~ constant(':'))
        .consume(
          number =>
            bytes(number.toInt).xmap[Bencode.BString](
              bv => Bencode.BString(bv),
              bs => bs.value
            )
        )(
          _.value.size.toInt
        )

    val integerCodec: Codec[Bencode.BInteger] = (constant('i') ~> number <~ constant('e')).xmap(
      number => Bencode.BInteger(number),
      integer => integer.value
    )

    val listCodec: Codec[Bencode.BList] =
      (constant('l') ~> listSuccessful(valueCodec) <~ constant('e')).xmap(
        elems => Bencode.BList(elems),
        list => list.values
      )

    val keyValueCodec: Codec[String ~ Bencode] = (stringCodec ~ valueCodec).xmap(
      { case (Bencode.BString(key), value) => (key.decodeAscii.right.get, value) },
      { case (key, value) => (Bencode.BString(ByteVector.encodeAscii(key).right.get), value) }
    )

    val dictionaryCodec: Codec[Bencode.BDictionary] =
      (constant('d') ~> listSuccessful(keyValueCodec) <~ constant('e'))
        .xmap(
          elems => Bencode.BDictionary(elems.toMap),
          dict => dict.values.toList
        )

    choice(
      stringCodec.upcast[Bencode],
      integerCodec.upcast[Bencode],
      listCodec.upcast[Bencode],
      dictionaryCodec.upcast[Bencode]
    )
  }

  /**
    * Codec that encodes/decodes a `List[A]` from a `Codec[A]`.
    *
    * When encoding, each `A` in the list is encoded and all of the resulting vectors are concatenated.
    *
    * When decoding, `codec.decode` is called repeatedly until first error or
    * there are no more remaining bits and the value result of each `decode` is returned in the list.
    *
    * @param codec codec to encode/decode a single element of the sequence
    */
  private def listSuccessful[A](codec: Codec[A]): Codec[List[A]] = new Codec[List[A]] {
    def sizeBound: SizeBound = SizeBound.unknown
    def decode(bits: BitVector): Attempt[DecodeResult[List[A]]] =
      decodeCollectSuccessful[List, A](codec, None)(bits)
    def encode(value: List[A]): Attempt[BitVector] =
      Encoder.encodeSeq(codec)(value)
  }

  private def decodeCollectSuccessful[F[_], A](dec: Decoder[A], limit: Option[Int])(
      buffer: BitVector
  )(implicit cbf: collection.generic.CanBuildFrom[F[A], A, F[A]]): Attempt[DecodeResult[F[A]]] = {
    val bldr = cbf()
    var remaining = buffer
    var count = 0
    val maxCount = limit getOrElse Int.MaxValue
    var error = false
    while (count < maxCount && !error && remaining.nonEmpty) {
      dec.decode(remaining) match {
        case Attempt.Successful(DecodeResult(value, rest)) =>
          bldr += value
          count += 1
          remaining = rest
        case Attempt.Failure(_) =>
          error = true
      }
    }
    Attempt.successful(DecodeResult(bldr.result, remaining))
  }
}
