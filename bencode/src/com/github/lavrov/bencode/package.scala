package com.github.lavrov.bencode

import scodec.Err
import scodec.bits.BitVector

object `package` {

  def decode(source: BitVector): Either[Err, Bencode] =
    BencodeCodec.instance.decodeOnly.decodeValue(source).toEither

  def encode(value: Bencode): BitVector = BencodeCodec.instance.encode(value).require
}
