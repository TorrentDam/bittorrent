package com.github.lavrov.bittorrent.wire

import java.util.UUID

import cats.Parallel
import cats.data.Chain
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Request
import com.github.lavrov.bittorrent.TorrentMetadata
import fs2.{Pull, Stream}
import logstage.LogIO
import monocle.Lens
import monocle.macros.GenLens
import scodec.bits.ByteVector

case class Downloader[F[_]](
  completePieces: Stream[F, Downloader.CompletePiece]
)

object Downloader {

  case class State(
    incompletePieces: State.IncompletePieces,
    chunkQueue: List[Message.Request] = Nil,
    inProgress: Map[Message.Request, Set[UUID]] = Map.empty,
    inProgressByPeer: Map[UUID, Set[Message.Request]] = Map.empty
  )
  object State {
    type IncompletePieces = List[IncompletePiece]
    val incompletePieces: Lens[State, IncompletePieces] = GenLens[State](_.incompletePieces)
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
      val joinedChunks: ByteVector = downloaded.toList.sortBy(_._1.begin).map(_._2).reduce(_ ++ _)
      if (joinedChunks.digest("SHA-1") == checksum) joinedChunks.some else none
    }
    def reset: IncompletePiece = copy(downloadedSize = 0, downloaded = Map.empty)
  }

  def start[F[_]](
    metaInfo: TorrentMetadata.Info,
    connectionManager: ConnectionManager[F],
    logger: LogIO[F]
  )(implicit F: Concurrent[F], P: Parallel[F], timer: Timer[F]): F[Downloader[F]] = {
    for {
      incompletePieces <- F.delay { buildQueue(metaInfo) }
      requestQueue = incompletePieces.map(_.requests).toList.flatten
      stateRef <- Ref.of[F, State](
        State(incompletePieces.toList, requestQueue)
      )
    } yield Downloader(
      downloadLoop(stateRef, connectionManager)
    )
  }

  def downloadLoop[F[_]](stateRef: Ref[F, State], connectionManager: ConnectionManager[F])(
    implicit F: Concurrent[F]
  ): Stream[F, CompletePiece] = {
    def acquirePiece: F[Option[IncompletePiece]] =
      stateRef.modify { s =>
        s.incompletePieces match {
          case x :: xs => State.incompletePieces.set(xs)(s) -> x.some
          case _ => s -> none
        }
      }
    def releasePiece(incompletePiece: IncompletePiece): F[Unit] =
      stateRef.update(State.incompletePieces.modify(incompletePiece.reset :: _))
    def downloadPiece(
      connection: Connection[F],
      i: IncompletePiece,
      requests: List[Message.Request]
    ): F[CompletePiece] = {
      requests match {
        case x :: xs =>
          connection.request(x).flatMap { bytes =>
            downloadPiece(connection, i.add(x, bytes), xs)
          }
        case _ =>
          F.fromOption(i.verified, new Exception("Checksum validation failed")).map {
            CompletePiece(i.index, i.begin, _)
          }
      }
    }
    def download(connection: Connection[F]): Stream[F, CompletePiece] =
      Stream.eval(acquirePiece).flatMap {
        case Some(p) =>
          Stream.eval(downloadPiece(connection, p, p.requests).attempt).flatMap {
            case Right(cp) => Stream.emit(cp) ++ download(connection)
            case Left(e) => Stream.eval(releasePiece(p)) >> Stream.raiseError(e)
          }
        case _ => Stream.empty
      }

    def pull(connections: Stream[F, Connection[F]]): Pull[F, CompletePiece, Unit] =
      connections.pull.uncons1.flatMap {
        case Some((connection, tail)) =>
          download(connection).pull.echo.handleErrorWith { _ =>
            pull(tail)
          }
        case _ => Pull.done
      }
    pull(connectionManager.connections).stream
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

    def downloadPiece(
      pieceIndex: Long,
      length: Long
    ): Chain[Message.Request] = {
      val chunkSize = 16 * 1024
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val thisChunkSize = math.min(chunkSize, length - index * chunkSize)
        if (thisChunkSize > 0) {
          val begin = index * chunkSize
          result = result append Message.Request(pieceIndex, begin, thisChunkSize)
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    metaInfo match {
      case info: Info.SingleFile => downloadFile(info.pieceLength, info.length, info.pieces)
      case info: Info.MultipleFiles =>
        downloadFile(info.pieceLength, info.files.map(_.length).sum, info.pieces)
    }
  }
}
