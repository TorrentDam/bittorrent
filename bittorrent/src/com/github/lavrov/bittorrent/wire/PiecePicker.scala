package com.github.lavrov.bittorrent.wire

import java.net.InetSocketAddress

import cats.Eval
import cats.data.Chain
import cats.implicits._
import cats.effect.{Concurrent, Sync, Timer}
import cats.effect.concurrent.{Deferred, Semaphore}
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
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
              incompletePiece,
              incompletePiece.requests.value
            )
            state.queue.update(index, inProgress)
          }
          _ <- notifyRef.set(())
        } yield deferred.get
      }.flatten

    def pick(availability: BitSet, address: InetSocketAddress): F[Option[Message.Request]] =
      synchronized {
        for {
          piece <- Sync[F].delay {
            state.queue.find { case (i, p) => availability(i) && p.requests.nonEmpty }.map(_._2)
          }
          request <- piece.traverse { piece =>
            Sync[F].delay {
              val request = piece.requests.head
              piece.requests = piece.requests.tail
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
            val verified = piece.verify(bytes)
            if (verified)
              for {
                complete <- Sync[F].delay {
                  state.completions.remove(piece.piece.index.toInt)
                }
                _ <- complete.traverse_(_(bytes))
              } yield ()
            else
              Sync[F].delay {
                piece.reset()
              }
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
    val piece: IncompletePiece,
    var requests: List[Message.Request],
    var downloadedSize: Long = 0,
    var downloaded: TreeMap[Int, ByteVector] = TreeMap.empty
  ) {

    def add(request: Message.Request, bytes: ByteVector): Unit = {
      downloadedSize = downloadedSize + request.length
      downloaded = downloaded.updated(request.begin.toInt, bytes)
    }

    def isComplete: Boolean = piece.size == downloadedSize

    def bytes: Option[ByteVector] = {
      if (isComplete) {
        val joined = downloaded.values.reduce(_ ++ _)
        Some(joined)
      }
      else None
    }

    def verify(bytes: ByteVector): Boolean = {
      bytes.digest("SHA-1") == piece.checksum
    }

    def reset(): Unit = {
      requests = piece.requests.value
      downloadedSize = 0
      downloaded = downloaded.empty
    }
  }

}
