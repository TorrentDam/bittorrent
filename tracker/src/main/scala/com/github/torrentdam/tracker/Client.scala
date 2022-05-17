package com.github.torrentdam.tracker

import cats.effect.{IO, Resource}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.github.torrentdam.tracker.impl.{HttpClient, UdpClient}
import org.http4s.{Query, QueryParamEncoder, Uri}
import org.http4s.client.Client as Http4sClient
import scodec.bits.ByteVector
import com.github.torrentdam.bencode.{decode, Bencode, BencodeFormatException}
import cats.syntax.all.given
import com.comcast.ip4s.{Dns, IpAddress, Port, SocketAddress}
import fs2.io.net.DatagramSocket
import scodec.Codec

trait Client:
  def get(announceUrl: Uri, infoHash: InfoHash): IO[Client.Response]

object Client:

  enum Response:
    case Success(peers: List[PeerInfo])
    case Failure(reason: String)

  def http(http4sClient: Http4sClient[IO]): HttpClient =
    HttpClient.impl(http4sClient.expect[ByteVector])

  def udp(selfId: PeerId, socket: DatagramSocket[IO])(using Dns[IO]): Resource[IO, UdpClient] =
    UdpClient.impl(selfId, socket)

  def dispatching(httpClient: HttpClient, udpClient: UdpClient): Client = new:
    def get(announceUrl: Uri, infoHash: InfoHash): IO[Client.Response] =
      IO
        .fromOption(announceUrl.scheme)(InvalidUri())
        .map(_.value)
        .flatMap {
          case "http" => httpClient.get(announceUrl, infoHash)
          case "udp"  => udpClient.get(announceUrl, infoHash)
        }

  class InvalidUri extends Throwable

end Client
