package com.github.lavrov.bittorrent.wire

import cats.data.Chain
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.{Ref, Semaphore}
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.wire.SwarmTasks.Error
import fs2.Stream
import fs2.concurrent.SignallingRef
import logstage.LogIO
import scodec.bits.ByteVector

import scala.collection.BitSet
import scala.concurrent.duration._

trait PiecePicker[F[_]] {
  def download(index: Int): F[Unit]
  def pick(availability: BitSet): F[Option[Message.Request]]
  def unpick(request: Message.Request): F[Unit]
  def complete(request: Message.Request, bytes: ByteVector): F[Unit]
  def updates: Stream[F, Unit]
}
object PiecePicker {
  def apply[F[_]](
    metadata: TorrentMetadata,
    onComplete: CompletePiece => F[Unit]
  )(implicit F: Concurrent[F], logger: LogIO[F], timer: Timer[F]): F[PiecePicker[F]] =
    for {
      stateRef <- Ref.of(State(Map.empty, Set.empty))
      pickMutex <- Semaphore(1)
      notifyRef <- SignallingRef(())
      incompletePieces = buildQueue(metadata).toList
    } yield new Impl(stateRef, pickMutex, notifyRef, incompletePieces, onComplete)

  case class State(
    incomplete: Map[Int, IncompletePiece],
    pending: Set[Message.Request]
  )

  private class Impl[F[_]](
    stateRef: Ref[F, State],
    mutex: Semaphore[F],
    notifyRef: SignallingRef[F, Unit],
    incompletePieces: List[IncompletePiece],
    onComplete: CompletePiece => F[Unit]
  )(implicit F: Concurrent[F], logger: LogIO[F])
      extends PiecePicker[F] {

    def download(index: Int): F[Unit] = mutex.withPermit {
      stateRef.update { state =>
        state.copy(
          incomplete = state.incomplete.updated(index, incompletePieces(index))
        )
      } >>
      notifyRef.set(())
    }

    def pick(availability: BitSet): F[Option[Message.Request]] = mutex.withPermit {
      for {
        state <- stateRef.get
        result = state.incomplete.find { case (i, p) => availability(i) && p.requests.nonEmpty }
        request <- result.traverse {
          case (i, p) =>
            val request = p.requests.head
            val updatedPiece = p.copy(requests = p.requests.tail)
            val updatedState = State(state.incomplete.updated(i, updatedPiece), state.pending + request)
            stateRef.set(updatedState).as(request)
        }
      } yield request
    }

    def unpick(request: Message.Request): F[Unit] = mutex.withPermit {
      stateRef.update { state =>
        val index = request.index.toInt
        val piece = state.incomplete(index)
        val updatedPiece = piece.copy(requests = request :: piece.requests)
        State(state.incomplete.updated(index, updatedPiece), state.pending - request)
      } >>
      notifyRef.set(())
    }

    def complete(request: Message.Request, bytes: ByteVector): F[Unit] = mutex.withPermit {
      for {
        piece <- stateRef.modify { state =>
          val index = request.index.toInt
          val piece = state.incomplete(index)
          val updatedPiece = piece.add(request, bytes)
          val updatedIncomplete =
            if (updatedPiece.isComplete)
              state.incomplete.removed(index)
            else
              state.incomplete.updated(index, updatedPiece)
          val updatedState = State(updatedIncomplete, state.pending - request)
          (updatedState, updatedPiece)
        }
        _ <- F.whenA(piece.isComplete) {
          F.fromOption(piece.verified, Error.InvalidChecksum())
            .map(CompletePiece(piece.index, _))
            .flatMap(onComplete)
        }
      } yield ()
    }

    def updates: Stream[F, Unit] = notifyRef.discrete
  }

  def buildQueue(metadata: TorrentMetadata): Chain[IncompletePiece] = {

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

    downloadFile(metadata.pieceLength, metadata.files.map(_.length).sum, metadata.pieces)
  }

  case class IncompletePiece(
    index: Long,
    size: Long,
    checksum: ByteVector,
    requests: List[Message.Request],
    downloadedSize: Long = 0,
    downloaded: Map[Message.Request, ByteVector] = Map.empty
  ) {
    def add(request: Message.Request, bytes: ByteVector): IncompletePiece =
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

  case class CompletePiece(index: Long, bytes: ByteVector)
}
