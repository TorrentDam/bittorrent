package com.github.lavrov.bittorrent.wire

import cats.Show.Shown
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.{Queue, Semaphore}
import cats.effect.implicits.*
import cats.effect.kernel.Ref
import cats.effect.{Async, Resource, Temporal}
import cats.implicits.*
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import fs2.Chunk
import fs2.concurrent.{Signal, SignallingRef}
import org.legogroup.woof.{Logger, given}
import scodec.bits.ByteVector

import scala.collection.BitSet
import scala.concurrent.duration.*
import scala.util.chaining.*

object Download {

  def apply(
    swarm: Swarm[IO],
    piecePicker: RequestDispatcher
  )(using
    logger: Logger[IO]
  ): IO[Unit] =
    import Logger.withLogContext
    swarm.connected.stream
      .parEvalMapUnbounded { connection =>
        logger.trace(s"Send Interested") >>
        connection.interested >>
        whenUnchoked(connection)(download(connection, piecePicker))
          .guaranteeCase { outcome =>
            connection.close >>
            logger.trace(s"Download exited with $outcome")
          }
          .attempt
          .withLogContext("address", connection.info.address.toString)
      }
      .compile
      .drain

  private def download(
    connection: Connection[IO],
    pieces: RequestDispatcher
  )(using logger: Logger[IO]): IO[Unit] = {

    def computeOutstanding(downloadedBytes: SignallingRef[IO, Long], maxOutstanding: SignallingRef[IO, Int]) =
      downloadedBytes.discrete
        .groupWithin(Int.MaxValue, 10.seconds)
        .map(chunks => scala.math.max(chunks.foldLeft(0L)(_ + _) / 10 / RequestDispatcher.ChunkSize, 1L).toInt)
        .evalMap(maxOutstanding.set)
        .compile
        .drain

    def updateSemaphore(semaphore: Semaphore[IO], maxOutstanding: SignallingRef[IO, Int]) =
      maxOutstanding.discrete
        .sliding(2)
        .evalMap { chunk =>
          val (prev, next) = (chunk(0), chunk(1))
          if prev < next then semaphore.releaseN(next - prev)
          else semaphore.acquireN(prev - next)
        }
        .compile
        .drain

    def sendRequest(request: Message.Request): IO[ByteVector] =
      logger.trace(s"Request $request") >>
      connection
        .request(request)
        .timeout(5.seconds)

    def fireRequests(semaphore: Semaphore[IO], failureCounter: Ref[IO, Int], downloadedBytes: Ref[IO, Long]) =
      pieces
        .stream(connection.availability)
        .map { (request, promise) =>
          Stream
            .resource(semaphore.permit)
            .evalMap(_ =>
              sendRequest(request).attempt.flatMap {
                case Right(bytes) =>
                  failureCounter.set(0) >> downloadedBytes.set(bytes.size) >> promise.complete(bytes)
                case Left(_) =>
                  failureCounter
                    .updateAndGet(_ + 1)
                    .flatMap(count => if (count >= 10) IO.raiseError(Error.PeerDoesNotRespond()) else IO.unit)
              }
            )
        }
        .parJoinUnbounded
        .compile
        .drain

    for
      failureCounter <- IO.ref(0)
      downloadedBytes <- SignallingRef[IO, Long](0L)
      maxOutstanding <- SignallingRef[IO, Int](5)
      semaphore <- Semaphore[IO](5)
      _ <- logger.trace(s"Download started")
      _ <- (
        computeOutstanding(downloadedBytes, maxOutstanding),
        updateSemaphore(semaphore, maxOutstanding),
        fireRequests(semaphore, failureCounter, downloadedBytes)
      ).parTupled
    yield ()
  }

  private def whenUnchoked(connection: Connection[IO])(f: IO[Unit])(using
    logger: Logger[IO]
  ): IO[Unit] = {
    def waitChoked = connection.choked.waitUntil(identity)
    def waitUnchoked =
      connection.choked
        .waitUntil(choked => !choked)
        .timeoutTo(30.seconds, IO.raiseError(Error.TimeoutWaitingForUnchoke(30.seconds)))

    (waitUnchoked >> (f race waitChoked)).foreverM
  }

  enum Error(message: String) extends Throwable(message):
    case TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case InvalidChecksum() extends Error("Invalid checksum")
    case PeerDoesNotRespond() extends Error("Peer does not respond")
}
