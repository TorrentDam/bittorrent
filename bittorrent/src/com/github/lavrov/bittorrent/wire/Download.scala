package com.github.lavrov.bittorrent.wire

import cats.Show.Shown
import cats.effect.implicits._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import org.typelevel.log4cats.{Logger, StructuredLogger}

import scala.collection.BitSet
import scala.concurrent.duration._
import scala.util.chaining._

object Download {

  private val MaxParallelRequests = 10

  def download[F[_]](
    swarm: Swarm[F],
    piecePicker: PiecePicker[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: StructuredLogger[F]): F[Unit] =
    swarm.connected.stream
      .parEvalMapUnordered(Int.MaxValue) { connection =>
        logger.addContext(("address", connection.info.address.toString: Shown)).pipe { implicit logger =>
          connection.interested >>
          whenUnchoked(connection)(download(connection, piecePicker))
            .recoverWith {
              case e =>
                logger.debug(s"Closing connection due to ${e.getMessage}") >>
                connection.close
            }
        }
      }
      .compile
      .drain
      .attempt
      .flatMap { result =>
        logger.error(s"Download process exited with $result")
      }

  private def download[F[_]](
    connection: Connection[F],
    pieces: PiecePicker[F]
  )(implicit F: Concurrent[F], logger: Logger[F], timer: Timer[F]): F[Unit] = {
    for {
      requestQueue <- Queue.unbounded[F, Message.Request]
      incompleteRequests <- SignallingRef[F, Set[Message.Request]](Set.empty)
      pickMutex <- Semaphore(1)
      fillQueue = (
          (incompleteRequests, connection.availability, pieces.updates).tupled.discrete
            .evalTap {
              case (requests: Set[Message.Request], availability: BitSet, _) if requests.size < MaxParallelRequests =>
                F.uncancelable {
                  pickMutex.withPermit {
                    for {
                      request <- pieces.pick(availability, connection.info.address)
                      _ <- request match {
                        case Some(request) =>
                          logger.trace(s"Picked $request") >>
                          incompleteRequests.update(_ + request) >>
                          requestQueue.enqueue1(request)
                        case None =>
                          logger.trace(s"No pieces dispatched for ${connection.info.address}")
                      }
                    } yield ()
                  }
                }
              case _ =>
                F.unit
            }
            .compile
            .drain
      ): F[Unit]
      sendRequest = { (request: Message.Request) =>
        logger.trace(s"Request $request") >>
        connection
          .request(request)
          .timeoutTo(5.seconds, F.raiseError(Error.TimeoutWaitingForPiece(5.seconds)))
          .flatMap { bytes =>
            F.uncancelable {
              pickMutex.withPermit {
                logger.trace(s"Complete $request") >>
                pieces.complete(request, bytes) >>
                incompleteRequests.update(_ - request)
              }
            }
          }
      }
      drainQueue =
        requestQueue.dequeue
          .parEvalMapUnordered(Int.MaxValue)(sendRequest)
          .compile
          .drain
      _ <-
        (fillQueue race drainQueue).void
          .guarantee {
            pickMutex.acquire >>
            incompleteRequests.get.flatMap { requests =>
              logger.debug(s"Unpick $requests") >>
              requests.toList.traverse_(pieces.unpick)
            }
          }
    } yield ()
  }

  private def whenUnchoked[F[_]](connection: Connection[F])(f: F[Unit])(implicit
    F: Concurrent[F],
    timer: Timer[F],
    logger: Logger[F]
  ): F[Unit] = {
    def unchoked = connection.choked.map(!_)
    def asString(choked: Boolean) = if (choked) "Choked" else "Unchoked"
    val current = Ref.unsafe[F, Option[Boolean]](None)
    connection.choked.discrete
      .evalTap { choked =>
        current.getAndSet(choked.some).flatMap { previous =>
          val from = previous.map(asString)
          val to = asString(choked)
          logger.debug(s"$from $to")
        }
      }
      .flatMap { choked =>
        if (choked)
          Stream
            .fixedDelay(30.seconds)
            .interruptWhen(unchoked)
            .flatMap { _ =>
              Stream.raiseError[F](Error.TimeoutWaitingForUnchoke(30.seconds))
            }
        else
          Stream
            .eval(f)
            .interruptWhen(connection.choked)
      }
      .compile
      .drain
  }

  sealed class Error(message: String) extends Throwable(message)
  object Error {
    case class TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case class TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case class InvalidChecksum() extends Error("Invalid checksum")
  }
}
