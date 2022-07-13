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
import fs2.concurrent.{Signal, SignallingRef}
import org.legogroup.woof.{Logger, given}
import scodec.bits.ByteVector

import scala.collection.BitSet
import scala.concurrent.duration.*
import scala.util.chaining.*

object Download {

  def apply[F[_]](
    swarm: Swarm[F],
    metadata: TorrentMetadata
  )(
    using
    F: Async[F],
    logger: Logger[F]
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
    using
    F: Temporal[F],
    logger: Logger[F]
  ): F[Unit] =
    import Logger.withLogContext
    swarm.connected.stream
      .evalMap { connection =>
        logger.trace(s"Send Interested") >>
        connection.interested >>
        logger.trace(s"Wait for Unchoked") >>
        whenUnchoked(connection)(download(connection, piecePicker))
        .guaranteeCase { outcome =>
          connection.close >>
          logger.trace(s"Download exited with $outcome")
        }
        .withLogContext("address", connection.info.address.toString)
        .start
      }
      .compile
      .drain

  private def download[F[_]](
    connection: Connection[F],
    pieces: PiecePicker[F]
  )(using F: Temporal[F], logger: Logger[F]): F[Unit] = {
    class Internal(
        requestQueue: Queue[F, Message.Request],
        incompleteRequests: SignallingRef[F, Set[Message.Request]],
        pickMutex: Semaphore[F],
        failureCounter: Ref[F, Int],
        downloadedBytes: SignallingRef[F, Long],
        maxOutstanding: SignallingRef[F, Int]
    ) {
      def complete(request: Message.Request, bytes: ByteVector): F[Unit] =
        F.uncancelable { _ =>
          pickMutex.permit.use { _ =>
            logger.trace(s"Complete $request") >>
            pieces.complete(request, bytes) >>
            incompleteRequests.update(_ - request) >>
            failureCounter.update(_ => 0)
          }
        }

      def fail(request: Message.Request): F[Unit] =
        F.uncancelable { _ =>
          pickMutex.permit.use { _ =>
            logger.trace(s"Fail $request") >>
            incompleteRequests.update(_ - request) >>
            pieces.unpick(request) >>
            failureCounter.updateAndGet(_ + 1).flatMap(count =>
              if (count >= 10) F.raiseError(Error.PeerDoesNotRespond()) else F.unit
            )
          }
        }

      def computeOutstanding =
        downloadedBytes.discrete.groupWithin(Int.MaxValue, 10.seconds)
          .map(ones =>
            scala.math.max(ones.foldLeft(0L)(_ + _) / 10 / PiecePicker.ChunkSize, 1L).toInt
          )
          .evalMap(maxOutstanding.set)
          .compile
          .drain

      def sendRequest(request: Message.Request): F[Unit] =
        connection
          .request(request)
          .flatMap {
            bytes => downloadedBytes.set(bytes.size).as(bytes)
          }
          .timeout(5.seconds)
          .attempt
          .flatMap {
            case Right(bytes) => complete(request, bytes)
            case Left(_) => fail(request)
          }

      def fillQueue: F[Unit] =
        (incompleteRequests, connection.availability, maxOutstanding, pieces.updates).tupled.discrete
          .evalTap {
            case (requests: Set[Message.Request], availability: BitSet, maxParallelRequests, _) =>
              logger.trace(s"In progress ${requests.size}, max parallel req $maxParallelRequests") >>
              F.whenA(requests.size < maxParallelRequests)(
                F.uncancelable { _ =>
                  pickMutex.permit.use { _ =>
                    for
                      request <- pieces.pick(availability, connection.info.address)
                      _ <- logger.trace(s"Picked $request")
                        _ <- request match {
                        case Some(request) =>
                            incompleteRequests.update(_ + request) >>
                            requestQueue.offer(request)
                        case None =>
                          logger.trace(s"No pieces dispatched for ${connection.info.address}")
                      }
                    yield ()
                  }
                }.void
              )
          }
          .compile
          .drain

      def drainQueue: F[Unit] =
        Stream.fromQueueUnterminated(requestQueue)
          .parEvalMapUnordered(Int.MaxValue)(sendRequest)
          .compile
          .drain

      def run: F[Unit] =
        logger.trace(s"Download started") >>
        (fillQueue, drainQueue, computeOutstanding).parTupled.void
          .guarantee {
            pickMutex.acquire >>
              incompleteRequests.get.flatMap { requests =>
                logger.debug(s"Unpick $requests") >>
                  requests.toList.traverse_(pieces.unpick)
              }
          }
    }
    for
      requestQueue <- Queue.unbounded[F, Message.Request]
      incompleteRequests <- SignallingRef[F, Set[Message.Request]](Set.empty)
      pickMutex <- Semaphore(1)
      failureCounter <- Ref.of(0)
      downloadTime <- SignallingRef[F, Long](0L)
      maxOutstanding <- SignallingRef[F, Int](5)
      _ <- Internal(requestQueue, incompleteRequests, pickMutex, failureCounter, downloadTime, maxOutstanding).run
    yield ()
  }

  private def whenUnchoked[F[_]](connection: Connection[F])(f: F[Unit])(
    using
    F: Temporal[F],
    logger: Logger[F]
  ): F[Unit] = {
    def waitChoked = connection.choked.waitUntil(identity)
    def waitUnchoked =
      connection.choked
        .waitUntil(choked => !choked)
        .timeoutTo(30.seconds, F.raiseError(Error.TimeoutWaitingForUnchoke(30.seconds)))

    (waitUnchoked >> (f race waitChoked)).foreverM
  }

  enum Error(message: String) extends Throwable(message):
    case TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case InvalidChecksum() extends Error("Invalid checksum")
    case PeerDoesNotRespond() extends Error("Peer does not respond")
}
