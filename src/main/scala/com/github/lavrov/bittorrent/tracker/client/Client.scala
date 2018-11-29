package com.github.lavrov.bittorrent.tracker.client

import cats.effect.IO
import com.github.lavrov.bencode.{Bencode, BencodeCodec}
import scodec.Attempt.Successful
import spinoco.fs2.http.body.BodyDecoder
import spinoco.fs2.http.{HttpClient, HttpRequest}
import spinoco.protocol.http.Uri.{Query, QueryParameter}

class Client(httpClient: HttpClient[IO]) {

  implicit private val bencodeBodyDecoder =
    BodyDecoder.forDecoder(_ => Successful(BencodeCodec.instance))

  def send(request: TrackerRequest): IO[Either[String, TrackerResponse]] = {
    val query = Query(
      QueryParameter.single("info_hash", request.infoHash.decodeAscii.right.get) ::
        Nil
    )
    val uri = request.announce.withQuery(query)
    httpClient
      .request(HttpRequest get uri)
      .compile
      .lastOrError
      .flatMap { response =>
        response.bodyAs[Bencode].map { body =>
          body.toEither.left
            .map(_.message)
            .map { bencode =>
              TrackerResponse(None, 1, None, None, None, None, Nil)
            }
        }
      }
  }

}
