package com.github.lavrov.bittorrent.wire

import cats.effect.Concurrent
import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.{Deferred, Ref}
import scodec.bits.ByteVector

import scala.ref.WeakReference

trait PieceStore[F[_]] {
  def get(index: Int): F[ByteVector]
  def put(index: Int, bytes: ByteVector): F[Unit]
}

object PieceStore {

  def apply[F[_]](request: Int => F[Unit])(implicit F: Concurrent[F]): F[PieceStore[F]] = {
    sealed trait Cell
    case class Requested(deferred: Deferred[F, ByteVector]) extends Cell
    case class Complete(bytes: WeakReference[ByteVector]) extends Cell
    case class State(cells: Map[Int, Cell], complete: List[Int])
    for {
      pieces <- Ref.of(State(Map.empty, List.empty))
    } yield new PieceStore[F] {
      def get(index: Int): F[ByteVector] =
        pieces.modify { pieces =>
          pieces.cells.get(index) match {
            case Some(Complete(WeakReference(bytes))) => (pieces, bytes.pure[F])
            case Some(Requested(deferred)) => (pieces, deferred.get)
            case _ =>
              val deferred = Deferred.unsafe[F, ByteVector]
              val cells = pieces.cells.updated(index, Requested(deferred))
              (pieces.copy(cells = cells), request(index) >> deferred.get)
          }
        }.flatten

      def put(index: Int, bytes: ByteVector): F[Unit] =
        pieces
          .modify { pieces =>
            pieces.cells.get(index) match {
              case Some(Requested(deferred)) =>
                val cells = pieces.cells.updated(index, Complete(WeakReference(bytes)))
                val complete = index :: pieces.complete
                (pieces.copy(cells = cells, complete = complete), (deferred, complete).some)
              case _ => (pieces, none)
            }
          }
          .flatMap {
            case Some((deferred, complete)) =>
              deferred.complete(bytes)
            case _ => F.unit
          }
    }
  }
}
