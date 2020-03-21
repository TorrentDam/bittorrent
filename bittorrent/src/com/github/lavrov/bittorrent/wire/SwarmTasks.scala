package com.github.lavrov.bittorrent.wire

import cats.effect.implicits._
import cats.effect.concurrent.{MVar, Ref, Semaphore}
import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.concurrent.Queue
import logstage.LogIO

import scala.concurrent.duration._

object SwarmTasks {

  private val MaxParallelRequests = 10

  def download[F[_]](
    swarm: Swarm[F],
    piecePicker: PiecePicker[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): F[Unit] =
    swarm.connected.stream
      .parEvalMapUnordered(Int.MaxValue) { c =>
        download(c, piecePicker).attempt
      }
      .compile
      .drain

  private def download[F[_]](
    connection: Connection[F],
    pieces: PiecePicker[F]
  )(implicit F: Concurrent[F], logger: LogIO[F], timer: Timer[F]): F[Unit] = {
    for {
      implicit0(logger: LogIO[F]) <- logger.withCustomContext(("address", connection.info.address.toString)).pure[F]
      demand <- Semaphore(MaxParallelRequests)
      requestQueue <- Queue.unbounded[F, Message.Request]
      incompleteRequests <- Ref.of[F, Set[Message.Request]](Set.empty)
      pickMutex <- Semaphore(1)
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
      fillQueue = (
        for {
          _ <- demand.acquire
          _ <- connection.waitUnchoked.timeoutTo(30.seconds, F.raiseError(Error.TimeoutWaitingForUnchoke(30.seconds)))
          a <- connection.availability.get
          wait <- F.uncancelable {
            pickMutex.withPermit {
              for {
                request <- pieces.pick(a, connection.info.address)
                wait <- request match {
                  case Some(request) =>
                    logger.debug(s"Picked $request") >>
                    incompleteRequests.update(_ + request) >>
                    requestQueue.enqueue1(request).as(false)
                  case None =>
                    logger.debug(s"No pieces dispatched for ${connection.info.address}").as(true)
                }
              } yield wait
            }
          }
          _ <- F.whenA(wait)(waitForUpdate)
        } yield ()
      ).foreverM[Unit]
      sendRequest = { (request: Message.Request) =>
        logger.debug(s"Request $request") >>
        connection
          .request(request)
          .timeoutTo(5.seconds, F.raiseError(Error.TimeoutWaitingForPiece(5.seconds)))
          .flatMap { bytes =>
            F.uncancelable {
              logger.debug(s"Complete $request") >>
              pieces.complete(request, bytes) >>
              incompleteRequests.update(_ - request) >>
              demand.release
            }
          }
      }
      drainQueue = requestQueue.dequeue
        .parEvalMapUnordered(Int.MaxValue)(sendRequest)
        .compile
        .drain
      _ <- (fillQueue race drainQueue).void
        .handleErrorWith { e =>
          pickMutex.acquire >>
          logger.debug(s"Closing connection due to ${e.getMessage}") >>
          incompleteRequests.get.flatMap { requests =>
            logger.debug(s"Unpick $requests") >>
            requests.toList.traverse_(pieces.unpick)
          } >>
          connection.close
        }
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
