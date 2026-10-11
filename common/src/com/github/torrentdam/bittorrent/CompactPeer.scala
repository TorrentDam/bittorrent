package com.github.torrentdam.bittorrent

import com.comcast.ip4s.*
import com.github.torrentdam.bencode.format.*
import scodec.bits.ByteVector
import scodec.codecs.*
import scodec.Codec

object CompactPeer:

  val InetSocketAddressCodec: Codec[SocketAddress[IpAddress]] =
    (bytes(4) :: bytes(2)).xmap(
      { case (address, port) =>
        SocketAddress(
          IpAddress.fromBytes(address.toArray).get,
          Port.fromInt(port.toInt(signed = false)).get
        )
      },
      v => (ByteVector(v.host.toBytes), ByteVector.fromInt(v.port.value, 2))
    )

  val InetSocketAddress6Codec: Codec[SocketAddress[IpAddress]] =
    (bytes(16) :: bytes(2)).xmap(
      { case (address, port) =>
        SocketAddress(
          IpAddress.fromBytes(address.toArray).get,
          Port.fromInt(port.toInt(signed = false)).get
        )
      },
      v => (ByteVector(v.host.toBytes), ByteVector.fromInt(v.port.value, 2))
    )

  val CompactPeerInfoCodec: Codec[PeerInfo] =
    InetSocketAddressCodec.xmap(PeerInfo.apply, _.address)

  val CompactPeer6InfoCodec: Codec[PeerInfo] =
    InetSocketAddress6Codec.xmap(PeerInfo.apply, _.address)

  val CompactPeerListCodec: Codec[List[PeerInfo]] =
    list(CompactPeerInfoCodec)

  val CompactPeer6ListCodec: Codec[List[PeerInfo]] =
    list(CompactPeer6InfoCodec)

  given BencodeFormat[PeerInfo] = encodedString(CompactPeerInfoCodec)

  val CompactPeerListFormat: BencodeFormat[List[PeerInfo]] =
    encodedString(CompactPeerListCodec)

  val CompactPeer6ListFormat: BencodeFormat[List[PeerInfo]] =
    encodedString(CompactPeer6ListCodec)

end CompactPeer
