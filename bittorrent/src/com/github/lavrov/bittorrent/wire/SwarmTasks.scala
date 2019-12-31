package com.github.lavrov.bittorrent.wire

import cats.effect.concurrent.{MVar, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber, Resource, Sync, Timer}
import cats.implicits._
import logstage.LogIO

import scala.concurrent.duration._

object SwarmTasks {

  def download[F[_]](
    swarm: Swarm[F],
    piecePicker: PiecePicker[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): F[Unit] =
    for {
      fibers <- Ref.of(List.empty[Fiber[F, _]])
      cancel = fibers.get.flatMap { list =>
        list.traverse_(_.cancel)
      }
      _ <- swarm.connected.stream
        .evalTap { c =>
          F.uncancelable(
            downloadLoop(c, piecePicker).start.flatMap(f => fibers.update(f :: _))
          )
        }
        .compile
        .drain
        .guarantee(cancel)
    } yield ()

  private def downloadLoop[F[_]](
    connection: Connection[F],
    pieces: PiecePicker[F]
  )(implicit F: Concurrent[F], logger: LogIO[F], timer: Timer[F]): F[Unit] = {
    for {
      logger <- logger.withCustomContext(("address", connection.info.address.toString)).pure[F]
      waitForUpdate <- {
        MVar.empty[F, Unit].flatMap { trigger =>
          val notify = trigger.tryPut()
          val availabilityUpdates =
            connection.availability.discrete.evalTap(_ => notify).compile.drain
          val pieceUpdates = pieces.updates.evalTap(_ => notify).compile.drain
          (availabilityUpdates.start >> pieceUpdates.start).as(trigger.take)
        }
      }
      _ <- connection.interested
      _ <- {
        for {
          _ <- connection.waitUnchoked.timeoutTo(30.seconds, F.raiseError(Error.TimeoutWaitingForUnchoke(30.seconds)))
          a <- connection.availability.get
          request <- pieces.pick(a)
          _ <- logger.info(s"Picked $request")
          _ <- request match {
            case Some(request) =>
              connection
                .request(request)
                .timeoutTo(5.seconds, F.raiseError(Error.TimeoutWaitingForPiece(5.seconds)))
                .attempt
                .flatMap {
                  case Right(bytes) =>
                    logger.info(s"Complete $request") >>
                    pieces.complete(request, bytes)
                  case Left(e) =>
                    logger.info(s"Unpick $request") >>
                    pieces.unpick(request) >>
                    logger.info(s"Closing connection due to $e") >>
                    connection.close >>
                    F.raiseError[Unit](e)
                }
            case None =>
              waitForUpdate
          }
        } yield ()
      }.foreverM[Unit]
    } yield ()
  }

  sealed class Error(message: String) extends Throwable(message)
  object Error {
    case class TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case class TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case class InvalidChecksum() extends Error("Invalid checksum")
  }

  implicit class ConnectionExtension[F[_]](self: Connection[F])(implicit F: Sync[F]) {
    def waitUnchoked: F[Unit] = self.chokedStatus.get.flatMap {
      case false => F.unit
      case true => self.chokedStatus.discrete.find(choked => !choked).compile.lastOrError.void
    }
  }
}
