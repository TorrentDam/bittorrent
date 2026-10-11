package com.github.torrentdam.bittorrent.protocol.extensions.pex

import com.comcast.ip4s.*
import com.github.torrentdam.bencode.Bencode
import com.github.torrentdam.bittorrent.PeerInfo
import scodec.bits.ByteVector

class PexMessageSpec extends munit.FunSuite:

  private def ipv4Peer(ip: String, port: Int): PeerInfo =
    PeerInfo(SocketAddress(Ipv4Address.fromString(ip).get, Port.fromInt(port).get))

  private def ipv6Peer(ip: String, port: Int): PeerInfo =
    PeerInfo(SocketAddress(Ipv6Address.fromString(ip).get, Port.fromInt(port).get))

  test("encode and decode round-trip with IPv4 and IPv6 peers") {
    val message = PexMessage(
      added = List(ipv4Peer("192.168.1.1", 6881), ipv4Peer("10.0.0.2", 6882)),
      addedFlags = List(PexFlags(encrypted = true, seeder = false, utp = true, holepunch = false, connectable = false), PexFlags.Empty),
      dropped = List(ipv4Peer("172.16.0.1", 6883)),
      added6 = List(ipv6Peer("2001:db8::1", 6884)),
      dropped6 = List(ipv6Peer("2001:db8::2", 6885))
    )
    val encoded = PexMessage.encode(message)
    val decoded = PexMessage.decode(encoded)
    assertEquals(decoded, Right(message))
  }

  test("decode PEX message with only added and dropped IPv4 fields") {
    val addedBytes = ByteVector.fromValidHex("c0a801011ae1") // 192.168.1.1:6881
    val droppedBytes = ByteVector.fromValidHex("ac1000011ae3") // 172.16.0.1:6883
    val flagsBytes = ByteVector.fromValidHex("01") // encrypted
    val input = Bencode.BDictionary(
      "added" -> Bencode.BString(addedBytes),
      "added.f" -> Bencode.BString(flagsBytes),
      "dropped" -> Bencode.BString(droppedBytes)
    )
    val bc = com.github.torrentdam.bencode.encode(input).toByteVector
    val result = PexMessage.decode(bc)
    val expected = Right(
      PexMessage(
        added = List(ipv4Peer("192.168.1.1", 6881)),
        addedFlags = List(PexFlags(encrypted = true, seeder = false, utp = false, holepunch = false, connectable = false)),
        dropped = List(ipv4Peer("172.16.0.1", 6883)),
        added6 = Nil,
        dropped6 = Nil
      )
    )
    assertEquals(result, expected)
  }

  test("decode PEX message with no optional fields yields empty lists") {
    val input = Bencode.BDictionary(
      "added" -> Bencode.BString(ByteVector.empty)
    )
    val bc = com.github.torrentdam.bencode.encode(input).toByteVector
    val result = PexMessage.decode(bc)
    val expected = Right(PexMessage(Nil, Nil, Nil, Nil, Nil))
    assertEquals(result, expected)
  }

  test("encode omits empty fields") {
    val message = PexMessage(
      added = List(ipv4Peer("192.168.1.1", 6881)),
      addedFlags = List(PexFlags.Empty),
      dropped = Nil,
      added6 = Nil,
      dropped6 = Nil
    )
    val encoded = PexMessage.encode(message)
    val decoded = PexMessage.decode(encoded)
    assertEquals(decoded, Right(message))
  }

  test("PexFlags round-trips through byte encoding") {
    val flags = PexFlags(encrypted = true, seeder = true, utp = false, holepunch = true, connectable = false)
    val byte = PexFlags.toByte(flags)
    assertEquals(PexFlags.fromByte(byte), flags)
  }

  test("PexFlags.Empty encodes to zero byte") {
    assertEquals(PexFlags.toByte(PexFlags.Empty), 0.toByte)
  }

end PexMessageSpec