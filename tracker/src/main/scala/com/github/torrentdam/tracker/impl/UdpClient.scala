package com.github.torrentdam.tracker.impl

import cats.effect.IO
import com.github.lavrov.bittorrent.InfoHash
import com.github.torrentdam.tracker.Client
import com.github.torrentdam.tracker.Client.Response
import org.http4s.Uri

class UdpClient(makeRequest: UdpClient.MakeRequest) extends Client:
  def get(announceUrl: Uri, infoHash: InfoHash): IO[Response] = ???

object UdpClient:

  trait Request

  class MakeRequest:
    def apply[Response](request: Request): IO[Response] = ???

end UdpClient
