package com.github.torrentdam.bittorrent

import com.comcast.ip4s.*
import com.github.torrentdam.bencode.format.*
import scodec.bits.ByteVector

class CompactPeerSpec extends munit.FunSuite:

  import CompactPeer.given

  private def ipv4Peer(ip: String, port: Int): PeerInfo =
    PeerInfo(SocketAddress(Ipv4Address.fromString(ip).get, Port.fromInt(port).get))

  private def ipv6Peer(ip: String, port: Int): PeerInfo =
    PeerInfo(SocketAddress(Ipv6Address.fromString(ip).get, Port.fromInt(port).get))

  test("InetSocketAddressCodec round-trips IPv4 addresses") {
    val address = SocketAddress(Ipv4Address.fromString("192.168.1.1").get, Port.fromInt(6881).get)
    val encoded = CompactPeer.InetSocketAddressCodec.encode(address).require
    val decoded = CompactPeer.InetSocketAddressCodec.decodeValue(encoded).require
    assertEquals(decoded, address)
    assertEquals(encoded.toByteVector, ByteVector.fromValidHex("c0a801011ae1"))
  }

  test("InetSocketAddress6Codec round-trips IPv6 addresses") {
    val address = SocketAddress(Ipv6Address.fromString("2001:db8::1").get, Port.fromInt(6884).get)
    val encoded = CompactPeer.InetSocketAddress6Codec.encode(address).require
    val decoded = CompactPeer.InetSocketAddress6Codec.decodeValue(encoded).require
    assertEquals(decoded, address)
  }

  test("CompactPeerInfoCodec round-trips a peer") {
    val peer = ipv4Peer("10.0.0.2", 6882)
    val encoded = CompactPeer.CompactPeerInfoCodec.encode(peer).require
    val decoded = CompactPeer.CompactPeerInfoCodec.decodeValue(encoded).require
    assertEquals(decoded, peer)
  }

  test("CompactPeer6InfoCodec round-trips an IPv6 peer") {
    val peer = ipv6Peer("2001:db8::1", 6884)
    val encoded = CompactPeer.CompactPeer6InfoCodec.encode(peer).require
    val decoded = CompactPeer.CompactPeer6InfoCodec.decodeValue(encoded).require
    assertEquals(decoded, peer)
  }

  test("CompactPeerListCodec round-trips a list of peers") {
    val peers = List(ipv4Peer("192.168.1.1", 6881), ipv4Peer("10.0.0.2", 6882))
    val encoded = CompactPeer.CompactPeerListCodec.encode(peers).require
    val decoded = CompactPeer.CompactPeerListCodec.decodeValue(encoded).require
    assertEquals(decoded, peers)
  }

  test("CompactPeer6ListCodec round-trips a list of IPv6 peers") {
    val peers = List(ipv6Peer("2001:db8::1", 6884), ipv6Peer("2001:db8::2", 6885))
    val encoded = CompactPeer.CompactPeer6ListCodec.encode(peers).require
    val decoded = CompactPeer.CompactPeer6ListCodec.decodeValue(encoded).require
    assertEquals(decoded, peers)
  }

  test("BencodeFormat[PeerInfo] round-trips a single peer as a bencoded string") {
    val peer = ipv4Peer("192.168.1.1", 6881)
    val format = summon[BencodeFormat[PeerInfo]]
    val written = format.write(peer).toOption.get
    val read = format.read(written)
    assertEquals(read, Right(peer))
  }

  test("CompactPeerListFormat round-trips a peer list as a single bencoded string") {
    val peers = List(ipv4Peer("192.168.1.1", 6881), ipv4Peer("10.0.0.2", 6882))
    val format = CompactPeer.CompactPeerListFormat
    val written = format.write(peers).toOption.get
    val read = format.read(written)
    assertEquals(read, Right(peers))
  }

  test("CompactPeer6ListFormat round-trips an IPv6 peer list as a single bencoded string") {
    val peers = List(ipv6Peer("2001:db8::1", 6884))
    val format = CompactPeer.CompactPeer6ListFormat
    val written = format.write(peers).toOption.get
    val read = format.read(written)
    assertEquals(read, Right(peers))
  }

end CompactPeerSpec