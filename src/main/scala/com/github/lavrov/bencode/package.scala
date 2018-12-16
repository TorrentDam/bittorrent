package com.github.lavrov.bencode

import java.nio.charset.Charset

import scodec.bits.BitVector
import scodec.{DecodeResult, Err}

object `package` {

  implicit private[bencode] val charset = Charset.forName("US-ASCII")

  def decode(source: BitVector): Either[Err, Bencode] = BencodeCodec.instance.decodeOnly.decodeValue(source).toEither

  def decode(source: Array[Byte]): Either[Err, Bencode] = decode(BitVector(source))

  def encode(value: Bencode): BitVector = BencodeCodec.instance.encode(value).toEither.right.get
}
