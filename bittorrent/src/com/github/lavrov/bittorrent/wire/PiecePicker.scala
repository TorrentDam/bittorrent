package com.github.lavrov.bittorrent.wire

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Semaphore
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.wire.SwarmTasks.Error
import com.github.lavrov.bittorrent.wire.Torrent.{CompletePiece, IncompletePiece}
import fs2.Stream
import fs2.concurrent.SignallingRef
import logstage.LogIO
import scodec.bits.ByteVector

import scala.collection.BitSet
import scala.concurrent.duration._

trait PiecePicker[F[_]] {
  def add(pieces: List[IncompletePiece]): F[Unit]
  def pick(availability: BitSet): F[Option[Message.Request]]
  def unpick(request: Message.Request): F[Unit]
  def complete(request: Message.Request, bytes: ByteVector): F[Unit]
  def updates: Stream[F, Unit]
}
object PiecePicker {
  def apply[F[_]](
    onComplete: CompletePiece => F[Unit]
  )(implicit F: Concurrent[F], logger: LogIO[F], timer: Timer[F]): F[PiecePicker[F]] =
    for {
      stateRef <- SignallingRef(State(Map.empty, Set.empty))
      _ <- {
        stateRef.get.flatMap { state =>
          logger.info(s"PiecePickerPending ${state.pending}") >> timer.sleep(5.seconds)
        }
      }.foreverM[Unit].start
      pickMutex <- Semaphore(1)
    } yield new Impl(stateRef, pickMutex, onComplete)

  case class State(
    incomplete: Map[Int, IncompletePiece],
    pending: Set[Message.Request]
  )

  private class Impl[F[_]](
    stateRef: SignallingRef[F, State],
    mutex: Semaphore[F],
    onComplete: CompletePiece => F[Unit]
  )(implicit F: Concurrent[F], logger: LogIO[F])
      extends PiecePicker[F] {

    def add(pieces: List[IncompletePiece]): F[Unit] =
      stateRef.update { state =>
        state.copy(
          incomplete = state.incomplete ++ pieces.view.map(p => (p.index.toInt, p))
        )
      }

    def pick(availability: BitSet): F[Option[Message.Request]] =
      mutex.withPermit {
        for {
          state <- stateRef.get
          result = state.incomplete.find { case (i, p) => availability(i) && p.requests.nonEmpty }
          _ <- if (result.isEmpty) {
            val avail = availability.iterator.mkString(",")
            val piecesIncomp = state.incomplete.iterator.map { case (i, p) => s"$i-${p.requests.size}" }.mkString(",")
            logger.info(s"No pieces $avail and $piecesIncomp")
          }
          else F.unit
          request <- result.traverse {
            case (i, p) =>
              val request = p.requests.head
              val updatedPiece = p.copy(requests = p.requests.tail)
              val updatedState = State(state.incomplete.updated(i, updatedPiece), state.pending + request)
              stateRef.set(updatedState).as(request)
          }
        } yield request
      }

    def unpick(request: Message.Request): F[Unit] = {
      stateRef.update { state =>
        val index = request.index.toInt
        val piece = state.incomplete(index)
        val updatedPiece = piece.copy(requests = request :: piece.requests)
        State(state.incomplete.updated(index, updatedPiece), state.pending - request)
      }
    }

    def complete(request: Message.Request, bytes: ByteVector): F[Unit] = {
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
            .map(CompletePiece(piece.index, piece.begin, _))
            .flatMap(onComplete)
        }
      } yield ()
    }

    def updates: Stream[F, Unit] = stateRef.discrete.as(())
  }
}
