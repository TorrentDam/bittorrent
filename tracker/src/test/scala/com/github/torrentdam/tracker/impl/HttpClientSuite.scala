package com.github.torrentdam.tracker.impl

import cats.effect.IO
import com.comcast.ip4s.*
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import com.github.torrentdam.bencode.{Bencode, encode}
import com.github.torrentdam.tracker.Client
import org.http4s.Uri
import scodec.bits.ByteVector

class HttpClientSuite extends munit.CatsEffectSuite {

  test("http client"){
    var captureUri: Uri | Null = null
    val client =
      HttpClient( uri =>
        IO{captureUri = uri}.as(
          ByteVector.fromValidHex(
            "64383a696e74657276616c69333535346531323a6d696e20696e74657276616c693335353465353a" +
            "70656572733138303a97f9aeb821adb9274f465ff959ff475721ad33abd6f321adc100dc8c7b985b" +
            "da5c034501c20c42cb21adb0786b0ee501b02009f36e4c5626d73621ad5bdbd4e421ad575f606121" +
            "ad505d755664a02eaffd8f3c6ab240a911bcdfb92bfbabc8d54e3ddef0ae2405a51c5827232ebb05" +
            "1818ca25d708f88e861f28373a5e865f2e8c664c9d051492de7987b9e747fba8ca0284355df51722" +
            "5a86d80050ae5e7d79c8d55432ce8d2def5f1844b0bd463e8cf42a495f65"
          )
        )
      )
    val announceUri = Uri.fromString("http://test.tracker.net/?magnet").toOption.get
    val infoHash = InfoHash.fromString("c071aa6d06101fe3c1d8d3411343cfeb33d91e5f")
    for
      result <- client.get(announceUri, infoHash)
    yield
      assertEquals(
        captureUri,
        Uri.unsafeFromString(
          "http://test.tracker.net/?magnet" +
            "&info_hash=%C0%71%AA%6D%06%10%1F%E3%C1%D8%D3%41%13%43%CF%EB%33%D9%1E%5F" +
            "&uploaded=0" +
            "&downloaded=0" +
            "&left=0" +
            "&port=80" +
            "&compact=1"
        )
      )
      assert(result.isInstanceOf[Client.Response.Success])
      val Client.Response.Success(peers) = result: @unchecked
      assertEquals(
        peers.head,
        PeerInfo(SocketAddress(ip"151.249.174.184", port"8621")),
      )
  }
}
