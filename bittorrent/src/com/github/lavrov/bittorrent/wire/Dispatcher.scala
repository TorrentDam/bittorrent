package com.github.lavrov.bittorrent.wire

import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.{MVar, Semaphore}
import cats.effect.implicits._
import cats.implicits._
import com.github.lavrov.bittorrent.wire.TorrentControl.{CompletePiece, IncompletePiece}
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import logstage.LogIO

import scala.collection.BitSet
import scala.concurrent.duration._

trait Dispatcher[F[_]] {
  def dispatch(pieces: Stream[F, IncompletePiece]): Stream[F, CompletePiece]
}

object Dispatcher {

  def start[F[_]](
    connectionManager: ConnectionManager[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): F[Dispatcher[F]] =
    F.pure {
      new Dispatcher[F] {
        def dispatch(pieces: Stream[F, IncompletePiece]): Stream[F, CompletePiece] = {
          for {
            completePieces <- Stream.eval { Queue.unbounded[F, CompletePiece] }
            ps <- Stream.eval { Pieces(completePieces.enqueue1) }
            _ <- connectionManager.connected.stream
              .evalTap(c => c.interested >> downloadLoop(c, ps).start)
              .spawn
            _ <- pieces.chunks
              .evalTap(chunk => ps.add(chunk.toList))
              .spawn
            completePiece <- completePieces.dequeue
          } yield completePiece
        }
      }
    }
//  : Stream[F, CompletePiece] =
//    Stream.eval {
//      for {
//        completePieces <- Queue.unbounded[F, CompletePiece]
//        pieces <- Pieces(pieces, completePieces.enqueue1)
//        _ <- connectionManager.connected.stream
//          .evalTap(c => c.interested >> downloadLoop(c, pieces).start)
//          .compile
//          .drain
//          .start
//      } yield completePieces.dequeue
//    }.flatten

  private def download[F[_]](
    connection: Connection[F],
    i: IncompletePiece
  )(implicit F: Concurrent[F], timer: Timer[F]): F[CompletePiece] = {
    Stream(i.requests: _*)
      .covary[F]
      .evalTap { _ =>
        connection.chokedStatus.discrete
          .find(choked => !choked)
          .compile
          .lastOrError
          .timeout(10.seconds)
      }
      .parEvalMap(20) { r =>
        connection.request(r).tupleLeft(r)
      }
      .scan(i) { case (a, (r, bytes)) => a.add(r, bytes) }
      .compile
      .lastOrError
      .flatMap { i =>
        F.fromOption(i.verified, new Exception("Checksum validation failed"))
          .map {
            CompletePiece(i.index, i.begin, _)
          }
      }
  }

  private def downloadLoop[F[_]](
    connection: Connection[F],
    pieces: Pieces[F]
  )(implicit F: Concurrent[F], logger: LogIO[F], timer: Timer[F]): F[Unit] = {
    for {
      await <- {
        MVar.empty[F, Unit].flatMap { trigger =>
          val notify = trigger.tryPut()
          val availabilityUpdates =
            connection.availability.discrete.evalTap(_ => notify).compile.drain
          val pieceUpdates = pieces.updates.evalTap(_ => notify).compile.drain
          (availabilityUpdates.start >> pieceUpdates.start).as(trigger.take)
        }
      }
      _ <- {
        for {
          a <- connection.availability.get
          p <- pieces.pick(a)
          _ <- p match {
            case Some(p) =>
              download(connection, p).attempt.flatMap {
                case Right(cp) => pieces.complete(cp)
                case Left(_) => pieces.unpick(p.index.toInt)
              }
            case None => await
          }
        } yield ()
      }.foreverM[Unit]
    } yield ()
  }

  trait Pieces[F[_]] {
    def add(pieces: List[IncompletePiece]): F[Unit]
    def pick(availability: BitSet): F[Option[IncompletePiece]]
    def unpick(index: Int): F[Unit]
    def complete(completePiece: CompletePiece): F[Unit]
    def updates: Stream[F, Unit]
  }
  object Pieces {
    def apply[F[_]](
      completeSink: CompletePiece => F[Unit]
    )(implicit F: Concurrent[F]): F[Pieces[F]] =
      for {
        stateRef <- SignallingRef(State(Map.empty))
        pickMutex <- Semaphore(1)
      } yield new PiecesImpl(stateRef, pickMutex, completeSink)

    case class State(
      incomplete: Map[Int, IncompletePiece],
      pending: Map[Int, IncompletePiece] = Map.empty
    )

    class PiecesImpl[F[_]](
      stateRef: SignallingRef[F, State],
      mutex: Semaphore[F],
      completeSink: CompletePiece => F[Unit]
    )(implicit F: Concurrent[F])
        extends Pieces[F] {

      def add(pieces: List[IncompletePiece]): F[Unit] =
        stateRef.update { state =>
          state.copy(
            incomplete = state.incomplete ++ pieces.view.map(p => (p.index.toInt, p))
          )
        }

      def pick(availability: BitSet): F[Option[IncompletePiece]] =
        mutex.withPermit {
          for {
            state <- stateRef.get
            index = availability.find(state.incomplete.contains)
            piece <- index.traverse { i =>
              val piece = state.incomplete(i)
              val state1 =
                State(state.incomplete - i, state.pending.updated(i, piece))
              stateRef.set(state1).as(piece)
            }
          } yield piece
        }

      def unpick(index: Int): F[Unit] = {
        stateRef.update { state =>
          val p = state.pending(index)
          State(state.incomplete.updated(index, p), state.pending - index)
        }
      }

      def complete(completePiece: CompletePiece): F[Unit] = {
        stateRef.update { state =>
          State(state.incomplete, state.pending - completePiece.index.toInt)
        } >> completeSink(completePiece)
      }

      def updates: Stream[F, Unit] = stateRef.discrete.as(())
    }
  }
}
