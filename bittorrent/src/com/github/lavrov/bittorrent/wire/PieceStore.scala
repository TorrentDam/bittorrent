package com.github.lavrov.bittorrent.wire

import cats.effect.Concurrent
import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.{Deferred, Ref}
import scodec.bits.ByteVector

trait PieceStore[F[_]] {
  def get(index: Int): F[ByteVector]
  def put(index: Int, bytes: ByteVector): F[Unit]
}

object PieceStore {

  def apply[F[_]](request: Int => F[Unit])(implicit F: Concurrent[F]): F[PieceStore[F]] = {
    sealed trait Cell
    case class Requested(deferred: Deferred[F, ByteVector]) extends Cell
    case class Complete(bytes: ByteVector) extends Cell
    for {
      pieces <- Ref.of(Map.empty[Int, Cell])
    } yield new PieceStore[F] {
      def get(index: Int): F[ByteVector] =
        pieces.modify { pieces =>
          pieces.get(index) match {
            case Some(cell) =>
              cell match {
                case Complete(bytes) => (pieces, bytes.pure[F])
                case Requested(deferred) => (pieces, deferred.get)
              }
            case None =>
              val deferred = Deferred.unsafe[F, ByteVector]
              (pieces.updated(index, Requested(deferred)), request(index) >> deferred.get)
          }
        }.flatten
      def put(index: Int, bytes: ByteVector): F[Unit] =
        pieces
          .modify { pieces =>
            pieces.get(index) match {
              case Some(Requested(deferred)) => (pieces.updated(index, Complete(bytes)), deferred.some)
              case _ => (pieces, none)
            }
          }
          .flatMap {
            case Some(deferred) => deferred.complete(bytes)
            case _ => F.unit
          }
    }
  }
}
