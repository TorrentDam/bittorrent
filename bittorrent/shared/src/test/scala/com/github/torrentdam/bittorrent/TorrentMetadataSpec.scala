package com.github.torrentdam.bittorrent

import com.github.torrentdam.bencode.*
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.torrentdam.bencode.CrossPlatform
import scodec.bits.Bases
import scodec.bits.BitVector
import scodec.bits.ByteVector

class TorrentMetadataSpec extends munit.FunSuite {

  test("encode file class") {
    val result = summon[BencodeFormat[TorrentMetadata.File]].write(TorrentMetadata.File(77, "abc" :: Nil))
    val expectation = Right(
      Bencode.BDictionary(
        "length" -> Bencode.BInteger(77),
        "path" -> Bencode.BList(Bencode.BString("abc") :: Nil)
      )
    )
    assert(result == expectation)
  }

  test("calculate info_hash") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(bc) = decode(BitVector(source)): @unchecked
    val decodedResult = summon[BencodeFormat[TorrentFile]].read(bc)
    val result = decodedResult
      .map(_.info.raw)
      .map(encode)
      .map(CrossPlatfrom.sha1)
      .map(_.toHex(Bases.Alphabets.HexUppercase))
    val expectation = Right("8C4ADBF9EBE66F1D804FB6A4FB9B74966C3AB609")
    assert(result == expectation)
  }

  test("decode either a or b") {
    val input = Bencode.BDictionary(
      "name" -> Bencode.BString("file_name"),
      "piece length" -> Bencode.BInteger(10),
      "pieces" -> Bencode.BString.Empty,
      "length" -> Bencode.BInteger(10)
    )

    assert(
      summon[BencodeFormat[TorrentMetadata]].read(input) == Right(
        TorrentMetadata("file_name", 10, ByteVector.empty, List(TorrentMetadata.File(10, List("file_name"))))
      )
    )

    val input1 = Bencode.BDictionary(
      "name" -> Bencode.BString("test"),
      "piece length" -> Bencode.BInteger(10),
      "pieces" -> Bencode.BString.Empty,
      "files" -> Bencode.BList(
        Bencode.BDictionary(
          "length" -> Bencode.BInteger(10),
          "path" -> Bencode.BList(Bencode.BString("/root") :: Nil)
        ) :: Nil
      )
    )

    assert(
      summon[BencodeFormat[TorrentMetadata]].read(input1) == Right(
        TorrentMetadata("test", 10, ByteVector.empty, TorrentMetadata.File(10, "/root" :: Nil) :: Nil)
      )
    )
  }

  test("decode dictionary") {
    val input = Bencode.BDictionary(
      "name" -> Bencode.BString("file_name"),
      "piece length" -> Bencode.BInteger(10),
      "pieces" -> Bencode.BString(ByteVector(10)),
      "length" -> Bencode.BInteger(10)
    )

    assert(
      summon[BencodeFormat[TorrentMetadata]].read(input) == Right(
        TorrentMetadata("file_name", 10, ByteVector(10), List(TorrentMetadata.File(10, List("file_name"))))
      )
    )
  }

  test("decode ubuntu torrent") {
    assert(decode(BitVector.encodeAscii("i56e").toOption.get) == Right(Bencode.BInteger(56L)))
    assert(decode(BitVector.encodeAscii("2:aa").toOption.get) == Right(Bencode.BString("aa")))
    assert(
      decode(BitVector.encodeAscii("l1:a2:bbe").toOption.get) == Right(
        Bencode.BList(Bencode.BString("a") :: Bencode.BString("bb") :: Nil)
      )
    )
    assert(
      decode(BitVector.encodeAscii("d1:ai6ee").toOption.get) == Right(
        Bencode.BDictionary("a" -> Bencode.BInteger(6))
      )
    )
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = decode(BitVector(source)): @unchecked
    val decodeResult = summon[BencodeFormat[TorrentFile]].read(result)
    assert(decodeResult.isRight)
  }

}
