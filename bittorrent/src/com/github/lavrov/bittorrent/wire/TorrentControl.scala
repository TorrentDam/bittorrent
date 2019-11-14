package com.github.lavrov.bittorrent.wire

import cats.Parallel
import cats.data.Chain
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Request
import fs2.Stream
import logstage.LogIO
import scodec.bits.ByteVector

case class TorrentControl[F[_]](
  download: Stream[F, TorrentControl.CompletePiece]
)

object TorrentControl {

  def apply[F[_]](
    metaInfo: TorrentMetadata.Info,
    connectionManager: ConnectionManager[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): F[TorrentControl[F]] = {
    for {
      incompletePieces <- F.delay { buildQueue(metaInfo) }
    } yield TorrentControl(
      Dispatcher.start(incompletePieces.toList, connectionManager)
    )
  }

  case class CompletePiece(index: Long, begin: Long, bytes: ByteVector)

  case class IncompletePiece(
    index: Long,
    begin: Long,
    size: Long,
    checksum: ByteVector,
    requests: List[Message.Request],
    downloadedSize: Long = 0,
    downloaded: Map[Message.Request, ByteVector] = Map.empty
  ) {
    def add(request: Request, bytes: ByteVector): IncompletePiece =
      copy(
        downloadedSize = downloadedSize + request.length,
        downloaded = downloaded.updated(request, bytes)
      )
    def isComplete: Boolean = size == downloadedSize
    def verified: Option[ByteVector] = {
      val joinedChunks: ByteVector =
        downloaded.toList.sortBy(_._1.begin).map(_._2).reduce(_ ++ _)
      if (joinedChunks.digest("SHA-1") == checksum) joinedChunks.some else none
    }
    def reset: IncompletePiece =
      copy(downloadedSize = 0, downloaded = Map.empty)
  }

  def buildQueue(metaInfo: TorrentMetadata.Info): Chain[IncompletePiece] = {
    import TorrentMetadata.Info

    def downloadFile(
      pieceLength: Long,
      totalLength: Long,
      pieces: ByteVector
    ): Chain[IncompletePiece] = {
      var result = Chain.empty[IncompletePiece]
      def loop(index: Long): Unit = {
        val thisPieceLength =
          math.min(pieceLength, totalLength - index * pieceLength)
        if (thisPieceLength > 0) {
          val list =
            downloadPiece(index, thisPieceLength)
          result = result append IncompletePiece(
            index,
            index * pieceLength,
            thisPieceLength,
            pieces.drop(index * 20).take(20),
            list.toList
          )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    def downloadPiece(pieceIndex: Long, length: Long): Chain[Message.Request] = {
      val chunkSize = 16 * 1024
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val thisChunkSize = math.min(chunkSize, length - index * chunkSize)
        if (thisChunkSize > 0) {
          val begin = index * chunkSize
          result = result append Message.Request(
            pieceIndex,
            begin,
            thisChunkSize
          )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    metaInfo match {
      case info: Info.SingleFile =>
        downloadFile(info.pieceLength, info.length, info.pieces)
      case info: Info.MultipleFiles =>
        downloadFile(
          info.pieceLength,
          info.files.map(_.length).sum,
          info.pieces
        )
    }
  }
}
