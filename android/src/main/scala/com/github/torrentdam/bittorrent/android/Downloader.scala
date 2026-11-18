package com.github.torrentdam.bittorrent.android

import android.util.Log
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Random
import cats.syntax.all.*
import com.comcast.ip4s.SocketAddress
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerId
import com.github.torrentdam.bittorrent.PeerInfo
import com.github.torrentdam.bittorrent.files.Writer
import com.github.torrentdam.bittorrent.net.Networks
import com.github.torrentdam.bittorrent.wire.Connection
import com.github.torrentdam.bittorrent.wire.DownloadMetadata
import com.github.torrentdam.bittorrent.wire.Swarm
import com.github.torrentdam.bittorrent.wire.Torrent
import fs2.Chunk
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Flag
import fs2.io.file.Flags
import fs2.io.file.Path
import fs2.io.net.Network
import org.legogroup.woof.ColorPrinter
import org.legogroup.woof.DefaultLogger
import org.legogroup.woof.Filter
import org.legogroup.woof.LogLevel
import org.legogroup.woof.Logger
import org.legogroup.woof.Output
import org.legogroup.woof.Printer
import org.legogroup.woof.given
import scodec.bits.ByteVector

/** Runs a real BitTorrent download using the shared `bittorrent` library (dexed into the APK). */
object Downloader {

  private val Tag = "TorrentDam"

  private given Network[IO] = Networks.custom[IO]

  /** Downloads the torrent `infoHashHex` from the single peer `peerAddress` ("host:port") into
    * `saveDir`. Torrent metadata is fetched from the peer over the wire (ut_metadata), so only
    * the info-hash is needed. Returns "<fileName> <sha256>" of the downloaded payload.
    */
  def download(infoHashHex: String, peerAddress: String, saveDir: String): IO[String] = {
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = ColorPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO]).flatMap { logger =>
      given Logger[IO] = logger
      val program: Resource[IO, String] = Resource.eval(Random.scalaUtilRandom[IO]).flatMap { random =>
          given Random[IO] = random
          for
            infoHash <- Resource.eval(
              IO.fromOption(InfoHash.fromString.lift(infoHashHex))(
                new IllegalArgumentException(s"bad info-hash: $infoHashHex")
              )
            )
            addr <- Resource.eval(
              IO.fromOption(SocketAddress.fromStringIp(peerAddress))(
                new IllegalArgumentException(s"bad peer address: $peerAddress")
              )
            )
            selfPeerId <- Resource.eval(PeerId.generate)
            _ <- Resource.eval(IO(Log.i(Tag, s"DOWNLOAD START infoHash=$infoHashHex peer=$peerAddress saveDir=$saveDir")))
            swarm <- Swarm(
              Stream.emit(PeerInfo(addr)).covary[IO],
              peerInfo => Connection.connect(selfPeerId, peerInfo, infoHash)
            )
            metadata <- Resource.eval(DownloadMetadata(swarm))
            torrent <- Torrent.make(metadata, swarm)
            _ <- Resource.eval(Files[IO].createDirectories(Path(saveDir)))
            _ <- Resource.eval(
              metadata.parsed.files
                .filter(_.path.length > 1)
                .map(_.path.init)
                .distinct
                .traverse(path => Files[IO].createDirectories(path.foldLeft(Path(saveDir))(_ / _)))
            )
            openFiles <- metadata.parsed.files
              .traverse { file =>
                val path = file.path.foldLeft(Path(saveDir))(_ / _)
                Files[IO].writeCursor(path, Flags(Flag.Create, Flag.Write)).tupleLeft(file)
              }
              .map(_.toMap)
            total = (metadata.parsed.pieces.length.toDouble / 20).ceil.toLong
            writer = Writer.fromTorrent(metadata.parsed)
            _ <- Resource.eval(
              Stream
                .range(0L, total)
                .parEvalMap(10)(index => torrent.downloadPiece(index).map(piece => (index, piece)))
                .evalMap { case (index, piece) =>
                  Stream
                    .emits(writer.write(index, piece))
                    .evalMap(write => openFiles(write.file).seek(write.offset).write(Chunk.byteVector(write.bytes)))
                    .compile
                    .drain
                    .flatMap(_ => IO(Log.i(Tag, s"piece ${index + 1}/$total written")))
                }
                .compile
                .drain
            )
            name = metadata.parsed.name
            bytes <- Resource.eval(Files[IO].readAll(Path(saveDir) / name).compile.to(ByteVector))
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toArray).map(b => f"$b%02x").mkString
          yield s"$name $sha256"
      }
      program.use(result => IO(Log.i(Tag, s"DOWNLOAD DONE $result")).as(result))
    }
  }
}
