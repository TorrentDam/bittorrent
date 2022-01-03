import cats.effect.{IO, IOApp}
import com.github.lavrov.bittorrent.InfoHash
import com.github.torrentdam.bencode.Bencode
import com.github.torrentdam.bencode.Bencode.BDictionary
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.torrentdam.tracker.Client
import org.http4s.Uri
import org.http4s.blaze.client.*
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.global

object main extends IOApp.Simple:
  def run: IO[Unit] =
    BlazeClientBuilder[IO].resource.use { httpClient =>
      val client = Client(httpClient.expect[ByteVector])
      for
        response <- client.get(
          announceUrl = Uri.unsafeFromString("http://bt.t-ru.org/ann?magnet"),
          infoHash = InfoHash.fromString("C071AA6D06101FE3C1D8D3411343CFEB33D91E5F"),
        )
        _ <- IO.println(response)
      yield ()
    }

