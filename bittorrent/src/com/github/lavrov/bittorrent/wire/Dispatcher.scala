package com.github.lavrov.bittorrent.wire

import cats.effect.{Concurrent, Fiber, Resource, Sync, Timer}
import cats.effect.concurrent.{Deferred, MVar, Ref, Semaphore}
import cats.effect.implicits._
import cats.implicits._
import com.github.lavrov.bittorrent.wire.TorrentControl.{CompletePiece, IncompletePiece}
import fs2.Stream
import fs2.concurrent.SignallingRef
import logstage.LogIO

import scala.collection.BitSet
import scala.concurrent.duration._

trait Dispatcher[F[_]] {
  def dispatch(piece: IncompletePiece): F[CompletePiece]
}

object Dispatcher {

  def start[F[_]](
    connectionManager: ConnectionManager[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): Resource[F, Dispatcher[F]] =
    for {
      pendingRequests <- Resource.liftF { Ref.of(Map.empty[Int, CompletePiece => F[Unit]]) }
      ps <- Resource.liftF {
        Pieces(
          piece =>
            pendingRequests
              .modify { value =>
                (value.removed(piece.index.toInt), value(piece.index.toInt))
              }
              .flatMap { cb =>
                cb(piece)
              }
        )
      }
      _ <- Resource {
        for {
          fibers <- Ref.of(List.empty[Fiber[F, _]])
          fiber <- connectionManager.connected.stream
            .evalTap { c =>
              downloadLoop(c, ps).start.flatMap(f => fibers.update(f :: _))
            }
            .compile
            .drain
            .start
          _ <- fibers.update(fiber :: _)
          cancel = fibers.get.flatMap { list =>
            list.traverse_(_.cancel)
          }
        } yield ((), cancel)
      }
    } yield new Dispatcher[F] {
      def dispatch(piece: IncompletePiece): F[CompletePiece] =
        for {
          deferred <- Deferred[F, CompletePiece]
          _ <- pendingRequests.update { value =>
            value.updated(piece.index.toInt, deferred.complete)
          }
          _ <- ps.add(List(piece))
          result <- deferred.get
        } yield result
    }

  private def download[F[_]](
    connection: Connection[F],
    i: IncompletePiece
  )(implicit F: Concurrent[F], timer: Timer[F]): F[CompletePiece] = {
    Stream(i.requests: _*)
      .covary[F]
      .evalMap { r =>
        connection.waitUnchoked
          .timeoutTo(10.seconds, F.raiseError(Error.TimeoutWaitingForUnchoke())) >>
        connection
          .request(r)
          .tupleLeft(r)
          .timeoutTo(1.minute, F.raiseError(Error.TimeoutWaitingForPiece()))
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
      _ <- connection.interested
      _ <- connection.waitUnchoked
      _ <- {
        for {
          a <- connection.availability.get
          p <- pieces.pick(a)
          _ <- p match {
            case Some(p) =>
              download(connection, p).attempt.flatMap {
                case Right(cp) => pieces.complete(cp)
                case Left(e) =>
                  pieces.unpick(p.index.toInt) >>
                  logger.info(s"Closing connection due to $e") >>
                  connection.close >>
                  F.raiseError[Unit](e)
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
      onComplete: CompletePiece => F[Unit]
    )(implicit F: Concurrent[F]): F[Pieces[F]] =
      for {
        stateRef <- SignallingRef(State(Map.empty))
        pickMutex <- Semaphore(1)
      } yield new PiecesImpl(stateRef, pickMutex, onComplete)

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

  sealed trait Error extends Throwable
  object Error {
    case class TimeoutWaitingForUnchoke() extends Error
    case class TimeoutWaitingForPiece() extends Error
  }

  implicit class ConnectionOps[F[_]](self: Connection[F])(implicit F: Sync[F]) {
    def waitUnchoked: F[Unit] = self.chokedStatus.get.flatMap {
      case false => F.unit
      case true => self.chokedStatus.discrete.find(choked => !choked).compile.lastOrError.void
    }
  }
}
