package com.github.torrentdam.bittorrent

import scodec.bits.ByteVector

class CrossPlatformSpec extends munit.FunSuite {

  def check(input: ByteVector, expected: String): Unit =
    assertEquals(CrossPlatform.sha1(input).toHex, expected)

  test("sha1 of empty input") {
    check(ByteVector.empty, "da39a3ee5e6b4b0d3255bfef95601890afd80709")
  }

  test("sha1 of abc") {
    check(ByteVector.view("abc".getBytes), "a9993e364706816aba3e25717850c26c9cd0d89d")
  }

  test("sha1 of the quick brown fox") {
    check(ByteVector.view("The quick brown fox jumps over the lazy dog".getBytes), "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12")
  }

  test("sha1 of million a") {
    check(ByteVector.fill(1000000)(0x61: Byte), "34aa973cd4c4daa4f61eeb2bdbad27316534016f")
  }

  test("sha1 across block boundaries") {
    check(ByteVector.fill(55)(0x61: Byte), "c1c8bbdc22796e28c0e15163d20899b65621d65a")
    check(ByteVector.fill(56)(0x61: Byte), "c2db330f6083854c99d4b5bfb6e8f29f201be699")
    check(ByteVector.fill(64)(0x61: Byte), "0098ba824b5c16427bd7a1122a5a442a25ec644d")
  }
}
