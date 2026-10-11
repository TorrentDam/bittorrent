package com.github.torrentdam.bittorrent

import scodec.bits.ByteVector

object CrossPlatform {

  def sha1(bytes: ByteVector): ByteVector = {
    val data = pad(bytes).toArray
    var h0 = 0x67452301
    var h1 = 0xefcdab89
    var h2 = 0x98badcfe
    var h3 = 0x10325476
    var h4 = 0xc3d2e1f0
    val w = new Array[Int](80)
    val blocks = data.length / 64
    var block = 0
    while block < blocks do {
      var t = 0
      while t < 16 do
        val offset = block * 64 + t * 4
        w(t) = (data(offset) & 0xff) << 24 | (data(offset + 1) & 0xff) << 16 | (data(offset + 2) & 0xff) << 8 |
          (data(offset + 3) & 0xff)
        t += 1
      end while
      while t < 80 do
        w(t) = rotl(w(t - 3) ^ w(t - 8) ^ w(t - 14) ^ w(t - 16), 1)
        t += 1
      end while
      var a = h0
      var b = h1
      var c = h2
      var d = h3
      var e = h4
      t = 0
      while t < 80 do
        val f =
          if t < 20 then (b & c) | (~b & d)
          else if t < 40 then b ^ c ^ d
          else if t < 60 then (b & c) | (b & d) | (c & d)
          else b ^ c ^ d
        val k =
          if t < 20 then 0x5a827999
          else if t < 40 then 0x6ed9eba1
          else if t < 60 then 0x8f1bbcdc
          else 0xca62c1d6
        val temp = rotl(a, 5) + f + e + k + w(t)
        e = d
        d = c
        c = rotl(b, 30)
        b = a
        a = temp
        t += 1
      end while
      h0 += a
      h1 += b
      h2 += c
      h3 += d
      h4 += e
      block += 1
    }
    val out = new Array[Byte](20)
    word(out, 0, h0)
    word(out, 4, h1)
    word(out, 8, h2)
    word(out, 12, h3)
    word(out, 16, h4)
    ByteVector(out)
  }

  private def word(out: Array[Byte], offset: Int, x: Int): Unit = {
    out(offset) = (x >>> 24).toByte
    out(offset + 1) = (x >>> 16).toByte
    out(offset + 2) = (x >>> 8).toByte
    out(offset + 3) = x.toByte
  }

  private def pad(bytes: ByteVector): ByteVector = {
    val bitLength = bytes.length.toLong * 8
    val remainder = (bytes.length + 1) % 64
    val zeros = if remainder <= 56 then 56 - remainder else 120 - remainder
    val length = new Array[Byte](8)
    length(0) = (bitLength >>> 56).toByte
    length(1) = (bitLength >>> 48).toByte
    length(2) = (bitLength >>> 40).toByte
    length(3) = (bitLength >>> 32).toByte
    length(4) = (bitLength >>> 24).toByte
    length(5) = (bitLength >>> 16).toByte
    length(6) = (bitLength >>> 8).toByte
    length(7) = bitLength.toByte
    bytes ++ ByteVector(0x80.toByte) ++ ByteVector.fill(zeros)(0: Byte) ++ ByteVector(length)
  }

  private def rotl(x: Int, n: Int): Int = (x << n) | (x >>> (32 - n))
}
