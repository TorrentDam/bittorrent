package com.github.torrentdam.tracker

import cats.effect.IO
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import com.github.torrentdam.tracker.impl.HttpClient
import org.http4s.{Query, QueryParamEncoder, Uri}
import org.http4s.client.Client as Http4sClient
import scodec.bits.ByteVector
import com.github.torrentdam.bencode.{Bencode, BencodeFormatException, decode}
import cats.syntax.all.given
import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import scodec.Codec

trait Client:
  def get(announceUrl: Uri, infoHash: InfoHash): IO[Client.Response]

object Client:

  enum Response:
    case Success(peers: List[PeerInfo])
    case Failure(reason: String)

  def http(http4sClient: Http4sClient[IO]): Client = HttpClient(http4sClient.expect[ByteVector])

end Client

