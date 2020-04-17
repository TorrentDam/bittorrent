package com.github.lavrov.bittorrent.wire

import java.net.InetSocketAddress

import cats.Eval
import cats.data.Chain
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.wire.SwarmTasks.Error
import fs2.Stream
import fs2.concurrent.SignallingRef
import logstage.LogIO
import scodec.bits.ByteVector

import scala.collection.BitSet
import scala.collection.immutable.TreeMap

trait PiecePicker[F[_]] {
  def download(index: Int): F[ByteVector]
  def pick(availability: BitSet, address: InetSocketAddress): F[Option[Message.Request]]
  def unpick(request: Message.Request): F[Unit]
  def complete(request: Message.Request, bytes: ByteVector): F[Unit]
  def updates: Stream[F, Unit]
  def pending: F[Map[Message.Request, InetSocketAddress]]
}
object PiecePicker {
  def apply[F[_]](
    metadata: TorrentMetadata
  )(implicit F: Concurrent[F], logger: LogIO[F], timer: Timer[F]): F[PiecePicker[F]] =
    for {
      stateRef <- Ref.of(State())
      completions <- Ref.of(Map.empty[Int, ByteVector => F[Unit]])
      pickMutex <- Semaphore(1)
      notifyRef <- SignallingRef(())
      incompletePieces = buildQueue(metadata).toList
    } yield new Impl(stateRef, completions, pickMutex, notifyRef, incompletePieces)

  case class State(
    queue: TreeMap[Int, InProgressPiece] = TreeMap.empty,
    pending: Map[Message.Request, InetSocketAddress] = Map.empty
  )

  private class Impl[F[_]](
    stateRef: Ref[F, State],
    completions: Ref[F, Map[Int, ByteVector => F[Unit]]],
    mutex: Semaphore[F],
    notifyRef: SignallingRef[F, Unit],
    incompletePieces: List[IncompletePiece]
  )(implicit F: Concurrent[F], logger: LogIO[F])
      extends PiecePicker[F] {

    private def synchronized[A](fa: F[A]): F[A] =
      mutex.withPermit(F.uncancelable(fa))

    def download(index: Int): F[ByteVector] =
      synchronized {
        for {
          deferred <- Deferred[F, ByteVector]
          _ <- completions.update { completions =>
            completions.updated(index, deferred.complete)
          }
          _ <- stateRef.update { state =>
            val incompletePiece = incompletePieces(index)
            val inProgress = InProgressPiece(
              incompletePiece.index,
              incompletePiece.size,
              incompletePiece.checksum,
              incompletePiece.requests.value
            )
            state.copy(
              queue = state.queue.updated(index, inProgress)
            )
          }
          _ <- notifyRef.set(())
        } yield deferred.get
      }.flatten

    def pick(availability: BitSet, address: InetSocketAddress): F[Option[Message.Request]] =
      synchronized {
        for {
          state <- stateRef.get
          result = state.queue.find { case (i, p) => availability(i) && p.requests.nonEmpty }
          request <- result.traverse {
            case (i, p) =>
              val request = p.requests.head
              val updatedPiece = p.copy(requests = p.requests.tail)
              val updatedState =
                State(state.queue.updated(i, updatedPiece), state.pending.updated(request, address))
              stateRef.set(updatedState).as(request)
          }
          _ <- logger.debug(s"Picking $request")
        } yield request
      }

    def unpick(request: Message.Request): F[Unit] =
      synchronized {
        stateRef.update { state =>
          val index = request.index.toInt
          val piece = state.queue(index)
          val updatedPiece = piece.copy(requests = request :: piece.requests)
          State(state.queue.updated(index, updatedPiece), state.pending - request)
        } >>
        notifyRef.set(())
      }

    def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
      synchronized {
        for {
          piece <- stateRef.modify { state =>
            val index = request.index.toInt
            val piece = state.queue(index)
            val updatedPiece = piece.add(request, bytes)
            val updatedQueue =
              if (updatedPiece.isComplete)
                state.queue.removed(index)
              else
                state.queue.updated(index, updatedPiece)
            val updatedState = State(updatedQueue, state.pending - request)
            (updatedState, updatedPiece)
          }
          _ <- piece.bytes.traverse_ { bytes =>
            for {
              complete <- completions.modify { completions =>
                (completions - piece.index.toInt, completions(piece.index.toInt))
              }
              _ <- complete(bytes)
            } yield ()
          }
        } yield ()
      }

    def updates: Stream[F, Unit] = notifyRef.discrete

    def pending: F[Map[Message.Request, InetSocketAddress]] = stateRef.get.map(_.pending)
  }

  def buildQueue(metadata: TorrentMetadata): Chain[IncompletePiece] = {

    def genPieces(
      pieceLength: Long,
      totalLength: Long,
      pieces: ByteVector
    ): Chain[IncompletePiece] = {
      var result = Chain.empty[IncompletePiece]
      def loop(index: Long): Unit = {
        val thisPieceLength =
          math.min(pieceLength, totalLength - index * pieceLength)
        if (thisPieceLength > 0) {
          result = result.append(
            IncompletePiece(
              index,
              thisPieceLength,
              pieces.drop(index * 20).take(20),
              Eval.always {
                genRequests(index, thisPieceLength).toList
              }
            )
          )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    def genRequests(pieceIndex: Long, length: Long): Chain[Message.Request] = {
      val chunkSize = 16 * 1024
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val thisChunkSize = math.min(chunkSize, length - index * chunkSize)
        if (thisChunkSize > 0) {
          val begin = index * chunkSize
          result = result.append(
            Message.Request(
              pieceIndex,
              begin,
              thisChunkSize
            )
          )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    genPieces(metadata.pieceLength, metadata.files.map(_.length).sum, metadata.pieces)
  }

  case class IncompletePiece(
    index: Long,
    size: Long,
    checksum: ByteVector,
    requests: Eval[List[Message.Request]]
  )
  case class InProgressPiece(
    index: Long,
    size: Long,
    checksum: ByteVector,
    requests: List[Message.Request],
    downloadedSize: Long = 0,
    downloaded: Map[Message.Request, ByteVector] = Map.empty
  ) {

    def add(request: Message.Request, bytes: ByteVector): InProgressPiece =
      copy(
        downloadedSize = downloadedSize + request.length,
        downloaded = downloaded.updated(request, bytes)
      )

    def isComplete: Boolean = size == downloadedSize

    def bytes: Option[ByteVector] = {
      if (isComplete) {
        val joined = downloaded.toSeq.sortBy(_._1.begin).map(_._2).reduce(_ ++ _)
        Some(joined)
      }
      else None
    }

    def verified: Option[ByteVector] = {
      bytes.filter(_.digest("SHA-1") == checksum)
    }

    def reset: InProgressPiece =
      copy(downloadedSize = 0, downloaded = Map.empty)
  }

  case class CompletePiece(index: Long, bytes: ByteVector)
}
