package com.github.lavrov.bittorrent.wire

import java.net.InetSocketAddress

import cats.Eval
import cats.data.Chain
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync, Timer}
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
      pickMutex <- Semaphore(1)
      notifyRef <- SignallingRef(())
      incompletePieces = buildQueue(metadata).toList
    } yield new Impl(State[F](), pickMutex, notifyRef, incompletePieces)

  private case class State[F[_]](
    queue: collection.mutable.TreeMap[Int, InProgressPiece] = collection.mutable.TreeMap.empty,
    pending: collection.mutable.Map[Message.Request, InetSocketAddress] = collection.mutable.Map.empty,
    completions: collection.mutable.Map[Int, ByteVector => F[Unit]] =
      collection.mutable.Map.empty[Int, ByteVector => F[Unit]]
  )

  private class Impl[F[_]](
    state: State[F],
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
          _ <- Sync[F].delay {
            state.completions.update(index, deferred.complete)
            val incompletePiece = incompletePieces(index)
            val inProgress = new InProgressPiece(
              incompletePiece.index,
              incompletePiece.size,
              incompletePiece.checksum,
              incompletePiece.requests.value
            )
            state.queue.update(index, inProgress)
          }
          _ <- notifyRef.set(())
        } yield deferred.get
      }.flatten

    def pick(availability: BitSet, address: InetSocketAddress): F[Option[Message.Request]] =
      synchronized {
        val result = state.queue.find { case (i, p) => availability(i) && p.requests.nonEmpty }
        for {
          request <- result.traverse {
            case (i, p) =>
              Sync[F].delay {
                val request = p.requests.head
                p.requests = p.requests.tail
                state.pending.update(request, address)
                request
              }
          }
          _ <- logger.debug(s"Picking $request")
        } yield request
      }

    def unpick(request: Message.Request): F[Unit] =
      synchronized {
        Sync[F].delay {
          val index = request.index.toInt
          val piece = state.queue(index)
          piece.requests = request :: piece.requests
          state.pending.remove(request)
        } >>
        notifyRef.set(())
      }

    def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
      synchronized {
        for {
          piece <- Sync[F].delay {
            val index = request.index.toInt
            val piece = state.queue(index)
            piece.add(request, bytes)
            if (piece.isComplete) {
              state.queue.remove(index)
            }
            state.pending.remove(request)
            piece
          }
          _ <- piece.bytes.traverse_ { bytes =>
            for {
              complete <- Sync[F].delay {
                state.completions.remove(piece.index.toInt)
              }
              _ <- complete.traverse_(_(bytes))
            } yield ()
          }
        } yield ()
      }

    def updates: Stream[F, Unit] = notifyRef.discrete

    def pending: F[Map[Message.Request, InetSocketAddress]] = synchronized {
      state.pending.toMap.pure[F]
    }
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
  private class InProgressPiece(
    val index: Long,
    size: Long,
    checksum: ByteVector,
    var requests: List[Message.Request],
    var downloadedSize: Long = 0,
    downloaded: collection.mutable.Map[Int, ByteVector] = collection.mutable.TreeMap.empty
  ) {

    def add(request: Message.Request, bytes: ByteVector): Unit = {
      downloadedSize = downloadedSize + request.length
      downloaded.update(request.begin.toInt, bytes)
    }

    def isComplete: Boolean = size == downloadedSize

    def bytes: Option[ByteVector] = {
      if (isComplete) {
        val joined = downloaded.values.reduce(_ ++ _)
        Some(joined)
      }
      else None
    }

    def verified: Option[ByteVector] = {
      bytes.filter(_.digest("SHA-1") == checksum)
    }

    def reset(): Unit = {
      downloadedSize = 0
      downloaded.clear()
    }
  }

  case class CompletePiece(index: Long, bytes: ByteVector)
}
