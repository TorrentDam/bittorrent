package com.github.torrentdam.tracker.impl

import cats.effect.IO
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import com.github.torrentdam.tracker.Client
import com.github.torrentdam.tracker.Client.Response
import org.http4s.{Query, QueryParamEncoder, Uri}
import org.http4s.client.Client as HttpClient
import scodec.bits.ByteVector
import com.github.torrentdam.bencode.{decode, Bencode, BencodeFormatException}
import cats.syntax.all.given
import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import scodec.Codec

trait HttpClient extends Client

object HttpClient:

  def impl(httpGet: Uri => IO[ByteVector]): HttpClient = new:
    def get(announceUrl: Uri, infoHash: InfoHash): IO[Response] =
      val params =
        List(
          "info_hash=" + infoHash.toHex.toUpperCase.grouped(2).mkString("%", "%", ""),
          "uploaded=0",
          "downloaded=0",
          "left=0",
          "port=80",
          "compact=1"
        ).mkString("&")
      val rawUri =
        announceUrl.renderString + (
          if announceUrl.query.isEmpty
          then "?" + params
          else "&" + params
        )
      val uri =
        Uri.unsafeFromString(rawUri)
      for
        body <- httpGet(uri)
        bencode <- IO.fromEither(decode(body.bits))
        response <- IO.fromEither(HttpClient.ResponseReader(bencode))
      yield response

  import scodec.codecs.*
  import com.github.torrentdam.bencode.format.*

  private val InetSocketAddressCodec: Codec[SocketAddress[IpAddress]] = {
    (bytes(4) :: bytes(2)).xmap(
      { case (address, port) =>
        SocketAddress(
          IpAddress.fromBytes(address.toArray).get,
          Port.fromInt(port.toInt(signed = false)).get
        )
      },
      v => (ByteVector(v.host.toBytes), ByteVector.fromInt(v.port.value, 2))
    )
  }

  private val CompactPeerInfoCodec: Codec[PeerInfo] =
    InetSocketAddressCodec.xmap(PeerInfo.apply, _.address)

  private val CompactPeerInfoFormat: BencodeFormat[PeerInfo] = encodedString(CompactPeerInfoCodec)

  private val CompactPeerListFormat: BencodeFormat[List[PeerInfo]] =
    BencodeFormat(
      BencodeFormat.ByteVectorFormat.read.flatMapF(byteVector =>
        byteVector
          .grouped(6)
          .toList
          .traverse(vector =>
            CompactPeerInfoCodec
              .decodeValue(vector.bits)
              .toEither
              .left
              .map(err => BencodeFormatException("Invalid peers field"))
          )
      ),
      BencodeWriter(_ => Left(BencodeFormatException("Not implemented")))
    )

  val ResponseReader: BencodeReader[Response] =
    BencodeReader(value =>
      fieldOptional[String]("failure reason").read(value).flatMap {
        case None         => field("peers")(using CompactPeerListFormat).read(value).map(Response.Success(_))
        case Some(reason) => Right(Response.Failure(reason))
      }
    )
end HttpClient
