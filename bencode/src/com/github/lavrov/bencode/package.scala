package com.github.lavrov.bencode

import scodec.Err
import scodec.bits.BitVector

object `package` {

  def decode(source: BitVector): Either[BencodeCodecError, Bencode] =
    BencodeCodec.instance.decodeOnly.decodeValue(source).toEither.left.map(BencodeCodecError)

  def decodeHead(source: BitVector): Either[BencodeCodecError, (BitVector, Bencode)] =
    BencodeCodec.instance
      .decode(source)
      .toEither
      .map(v => (v.remainder, v.value))
      .left
      .map(BencodeCodecError)

  def encode(value: Bencode): BitVector = BencodeCodec.instance.encode(value).require
}

case class BencodeCodecError(error: Err) extends Throwable(error.messageWithContext)
