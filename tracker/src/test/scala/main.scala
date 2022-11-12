import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.effect.{IO, IOApp}
import com.comcast.ip4s.Dns
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import com.github.torrentdam.bencode.Bencode
import com.github.torrentdam.bencode.Bencode.BDictionary
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.torrentdam.tracker.Client
import com.github.torrentdam.tracker.impl.UdpClient
import fs2.io.net.Network
import org.http4s.Uri
import org.http4s.ember.client.*
import scodec.bits.ByteVector
import com.comcast.ip4s.port

import scala.concurrent.ExecutionContext.global

object main extends IOApp.Simple:

  def run: IO[Unit] = runUdp

  def runHttp: IO[Unit] =
    EmberClientBuilder
      .default[IO]
      .build
      .use { httpClient =>
        val client = Client.http(httpClient)
        for
          response <- client.get(
            announceUrl = Uri.unsafeFromString("http://bt.t-ru.org/ann?magnet"),
            infoHash = InfoHash.fromString("C071AA6D06101FE3C1D8D3411343CFEB33D91E5F"),
          )
          _ <- IO.println(response)
        yield ()
      }

  def runUdp: IO[Unit] =
    val client =
      for
        given Random[IO] <- Resource.eval(Random.scalaUtilRandom[IO])
        selfId <- Resource.eval(PeerId.generate)
        socketGroup <- Network[IO].datagramSocketGroup()
        socket <- socketGroup.openDatagramSocket(port = Some(port"4333"))
        client <- Client.udp(selfId, socket)
      yield
        client
    client.use(client =>
      for
        response <- client.get(
          announceUrl = Uri.unsafeFromString("udp://tracker.opentrackr.org:1337"),
          infoHash = InfoHash.fromString("99C4CEDB17FB0C311DEAF97E8EA8AD045DAD6C0E"),
        )
        _ <- IO.println(response)
      yield ()
    )

