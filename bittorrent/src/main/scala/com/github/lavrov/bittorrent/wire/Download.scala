package com.github.lavrov.bittorrent.wire

import cats.effect.implicits.*
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.effect.std.Supervisor
import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Temporal
import cats.implicits.*
import cats.Show.Shown
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.TorrentMetadata
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.concurrent.Topic
import fs2.Chunk
import fs2.Stream
import org.legogroup.woof.given
import org.legogroup.woof.Logger
import scala.collection.BitSet
import scala.concurrent.duration.*
import scala.util.chaining.*
import scodec.bits.ByteVector

object Download {

  def apply(
    swarm: Swarm,
    piecePicker: RequestDispatcher
  )(using
    logger: Logger[IO]
  ): IO[Unit] =
    import Logger.withLogContext
    Classifier().use(classifier =>
      swarm.connect
        .use(connection =>
          (
            for
              _ <- connection.interested
              _ <- classifier.create.use(speedInfo =>
                whenUnchoked(connection)(
                  download(connection, piecePicker, speedInfo)
                )
              )
            yield ()
          )
            .race(connection.disconnected)
            .withLogContext("address", connection.info.address.toString)
        )
        .attempt
        .foreverM
        .parReplicateA(30)
        .void
    )

  private def download(
    connection: Connection,
    pieces: RequestDispatcher,
    speedInfo: SpeedData
  )(using logger: Logger[IO]): IO[Unit] = {

    def bounded(min: Int, max: Int)(n: Int): Int = math.min(math.max(n, min), max)

    def computeOutstanding(
      downloadedBytes: Topic[IO, Long],
      downloadedTotal: Ref[IO, Long],
      maxOutstanding: SignallingRef[IO, Int]
    ) =
      downloadedBytes
        .subscribe(10)
        .evalTap(size => downloadedTotal.update(_ + size))
        .groupWithin(Int.MaxValue, 10.seconds)
        .map(chunks =>
          bounded(1, 100)(
            (chunks.foldLeft(0L)(_ + _) / 10 / RequestDispatcher.ChunkSize).toInt
          )
        )
        .evalTap(maxOutstanding.set)
        .evalTap(speedInfo.bytes.set)
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

    def nextRequest(semaphore: Semaphore[IO]) =
      semaphore.permit >> pieces.stream(connection.availability.get, speedInfo.cls.get)

    def fireRequests(
      semaphore: Semaphore[IO],
      failureCounter: Ref[IO, Int],
      downloadedBytes: Topic[IO, Long]
    ) =
      Stream
        .resource(nextRequest(semaphore))
        .interruptWhen(
          IO.sleep(1.minute).as(Left(Error.TimeoutWaitingForPiece(1.minute)))
        )
        .repeat
        .map { (request, promise) =>
          Stream.eval(
            sendRequest(request).attempt.flatMap {
              case Right(bytes) =>
                failureCounter.set(0) >> downloadedBytes.publish1(bytes.size) >> promise.complete(bytes)
              case Left(_) =>
                failureCounter
                  .updateAndGet(_ + 1)
                  .flatMap(count =>
                    if count >= 10
                    then IO.raiseError(Error.PeerDoesNotRespond())
                    else IO.unit
                  )
            }
          )
        }
        .parJoinUnbounded
        .compile
        .drain

    for
      failureCounter <- IO.ref(0)
      downloadedBytes <- Topic[IO, Long]
      downloadedTotal <- IO.ref(0L)
      maxOutstanding <- SignallingRef[IO, Int](5)
      semaphore <- Semaphore[IO](5)
      _ <- (
        computeOutstanding(downloadedBytes, downloadedTotal, maxOutstanding),
        updateSemaphore(semaphore, maxOutstanding),
        fireRequests(semaphore, failureCounter, downloadedBytes)
      ).parTupled
        .handleErrorWith(e =>
          downloadedTotal.get.flatMap {
            case 0 => IO.raiseError(e)
            case _ => IO.unit
          }
        )
    yield ()
  }

  private def whenUnchoked(connection: Connection)(f: IO[Unit])(using
    logger: Logger[IO]
  ): IO[Unit] = {
    def waitChoked = connection.choked.waitUntil(identity)
    def waitUnchoked =
      connection.choked
        .waitUntil(choked => !choked)
        .timeoutTo(30.seconds, IO.raiseError(Error.TimeoutWaitingForUnchoke(30.seconds)))

    (waitUnchoked >> (f race waitChoked)).foreverM
  }

  private case class SpeedData(bytes: Ref[IO, Int], cls: Ref[IO, SpeedClass])

  private class Classifier(counter: Ref[IO, Long], state: Ref[IO, Map[Long, SpeedData]]) {
    def create: Resource[IO, SpeedData] = Resource(
      for
        id <- counter.getAndUpdate(_ + 1)
        bytes <- IO.ref(0)
        cls <- IO.ref(SpeedClass.Slow)
        _ <- state.update(_.updated(id, SpeedData(bytes, cls)))
      yield (SpeedData(bytes, cls), state.update(_ - id))
    )
  }
  private object Classifier {
    def apply(): Resource[IO, Classifier] =
      for
        counter <- Resource.eval(IO.ref(0L))
        state <- Resource.eval(IO.ref(Map.empty[Long, SpeedData]))
        _ <- (IO.sleep(10.seconds) >> updateClass(state)).foreverM.background
      yield new Classifier(counter, state)

    private def updateClass(state: Ref[IO, Map[Long, SpeedData]]): IO[Unit] =
      state.get
        .flatMap(_.values.toList.traverse { info =>
          info.bytes.get.tupleRight(info)
        })
        .flatMap(values =>
          val sorted = values.sortBy(_._1)(Ordering[Int].reverse).map(_._2)
          val fastCount = (values.size.toDouble * 0.7).ceil.toInt
          val (fast, slow) = sorted.splitAt(fastCount)
          fast.traverse(_.cls.set(SpeedClass.Fast)) >> slow.traverse(_.cls.set(SpeedClass.Slow))
        )
        .void
  }

  enum Error(message: String) extends Throwable(message):
    case TimeoutWaitingForUnchoke(duration: FiniteDuration) extends Error(s"Unchoke timeout $duration")
    case TimeoutWaitingForPiece(duration: FiniteDuration) extends Error(s"Block request timeout $duration")
    case InvalidChecksum() extends Error("Invalid checksum")
    case PeerDoesNotRespond() extends Error("Peer does not respond")
}

enum SpeedClass {
  case Slow, Fast
}
