package com.github.lavrov.bittorrent.wire

import cats.Show.Shown
import cats.effect.std.{Queue, Semaphore}
import cats.effect.implicits.*
import cats.effect.kernel.Ref
import cats.effect.{Async, Resource, Temporal}
import cats.implicits.*
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.{Logger, StructuredLogger}

import scala.collection.BitSet
import scala.concurrent.duration.*
import scala.util.chaining.*

object Download {

  private val MaxParallelRequests = 10

  def apply[F[_]](
    swarm: Swarm[F],
    metadata: TorrentMetadata
  )(
    implicit
    F: Async[F],
    logger: StructuredLogger[F]
  ): Resource[F, PiecePicker[F]] = {
    for
      picker <- Resource.eval { PiecePicker(metadata) }
      _ <- apply(swarm, picker).background
    yield
      picker
  }

  def apply[F[_]](
    swarm: Swarm[F],
    piecePicker: PiecePicker[F]
  )(
    implicit
    F: Temporal[F],
    logger: StructuredLogger[F]
  ): F[Unit] =
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
  )(implicit F: Temporal[F], logger: Logger[F]): F[Unit] = {
    for
      requestQueue <- Queue.unbounded[F, Message.Request]
      incompleteRequests <- SignallingRef[F, Set[Message.Request]](Set.empty)
      pickMutex <- Semaphore(1)
      fillQueue = (
          (incompleteRequests, connection.availability, pieces.updates).tupled.discrete
            .evalTap {
              case (requests: Set[Message.Request], availability: BitSet, _) if requests.size < MaxParallelRequests =>
                F.uncancelable { poll =>
                  pickMutex.permit.use { _ =>
                    for
                      request <- pieces.pick(availability, connection.info.address)
                      _ <- request match {
                        case Some(request) =>
                          logger.trace(s"Picked $request") >>
                          incompleteRequests.update(_ + request) >>
                          requestQueue.offer(request)
                        case None =>
                          logger.trace(s"No pieces dispatched for ${connection.info.address}")
                      }
                    yield ()
                  }
                }.void
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
            F.uncancelable { poll =>
              pickMutex.permit.use { _ =>
                logger.trace(s"Complete $request") >>
                pieces.complete(request, bytes) >>
                incompleteRequests.update(_ - request)
              }
            }
          }
      }
      drainQueue =
        Stream.fromQueueUnterminated(requestQueue)
          .parEvalMapUnordered(Int.MaxValue)(sendRequest)
          .compile
          .drain
      _ <-
        fillQueue.race(drainQueue).void
          .guarantee {
            pickMutex.acquire >>
            incompleteRequests.get.flatMap { requests =>
              logger.debug(s"Unpick $requests") >>
              requests.toList.traverse_(pieces.unpick)
            }
          }
    yield ()
  }

  private def whenUnchoked[F[_]](connection: Connection[F])(f: F[Unit])(implicit
    F: Temporal[F],
    logger: Logger[F]
  ): F[Unit] = {
    def unchoked = connection.choked.map(!_)
    def asString(choked: Boolean) = if (choked) "Choked" else "Unchoked"
    F.ref[Option[Boolean]](None)
      .flatMap { current =>
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
  }

  sealed abstract class Error(message: String) extends Throwable(message)
  object Error {
    case class TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case class TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case class InvalidChecksum() extends Error("Invalid checksum")
  }
}
