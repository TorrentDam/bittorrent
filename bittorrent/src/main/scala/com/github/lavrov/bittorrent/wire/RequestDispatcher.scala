package com.github.lavrov.bittorrent.wire

import cats.implicits.*
import cats.data.Chain
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Ref
import cats.effect.kernel.Deferred
import cats.effect.std.Semaphore
import cats.effect.std.Dequeue
import com.github.lavrov.bittorrent.TorrentMetadata
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import org.legogroup.woof.{Logger, given}
import scodec.bits.ByteVector
import com.comcast.ip4s.*
import com.github.lavrov.bittorrent.protocol.message.Message.Request

import java.util.UUID
import scala.collection.immutable.BitSet
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.DurationInt

trait RequestDispatcher {
  def downloadPiece(index: Long): IO[ByteVector]

  def stream(availability: IO[BitSet], cls: IO[ConnectionClass]): Stream[IO, (Message.Request, Deferred[IO, ByteVector])]
}

object RequestDispatcher {
  val ChunkSize: Int = 16 * 1024

  private type PieceWorkQueue = WorkQueue[Message.Request, ByteVector]

  def apply(metadata: TorrentMetadata): Resource[IO, RequestDispatcher] = Resource(
    for
      inProgress <- IO.ref(TreeMap.empty[Long, PieceWorkQueue])
      inProgressReverse <- IO.ref(TreeMap.empty[Long, PieceWorkQueue](using Ordering[Long].reverse))
      workGenerator = WorkGenerator(metadata)
    yield (Impl(workGenerator, inProgress, inProgressReverse), IO.unit)
  )

  private class Impl(
    workGenerator: WorkGenerator,
    inProgress: Ref[IO, TreeMap[Long, PieceWorkQueue]],
    inProgressReverse: Ref[IO, TreeMap[Long, PieceWorkQueue]]
  ) extends RequestDispatcher {
    def downloadPiece(index: Long): IO[ByteVector] =
      (
        for
          result <- IO.deferred[Map[Request, ByteVector]]
          pieceWork = workGenerator.pieceWork(index)
          tracker <- WorkQueue(pieceWork.requests.toList, result.complete)
          _ <- inProgress.update(_.updated(index, tracker))
          _ <- inProgressReverse.update(_.updated(index, tracker))
          result <- result.get
        yield result.toList.sortBy(_._1.begin).map(_._2).foldLeft(ByteVector.empty)(_ ++ _)
      ).guarantee(
        inProgress.update(_ - index) >> inProgressReverse.update(_ - index)
      )

    def stream(availability: IO[BitSet], cls: IO[ConnectionClass]): Stream[IO, (Message.Request, Deferred[IO, ByteVector])] =
      def pickFrom(trackers: List[PieceWorkQueue]): Resource[IO, (Message.Request, Deferred[IO, ByteVector])] =
        trackers match
          case Nil =>
            Resource.eval(IO.raiseError(NoPieceAvailable))
          case tracker :: rest =>
            tracker.nextRequest.recoverWith(_ => pickFrom(rest))

      def singlePass =
        for
          cls <- Resource.eval(cls)
          inProgress <- Resource.eval(
            cls match
              case ConnectionClass.Fast => inProgress.get
              case ConnectionClass.Slow => inProgressReverse.get // take piece with the highest index first
          )
          availability <- Resource.eval(availability)
          matched = inProgress.collect { case (index, tracker) if availability(index.toInt) => tracker }.toList
          result <- pickFrom(matched)
        yield result

      def polling: Resource[IO, (Message.Request, Deferred[IO, ByteVector])] =
        singlePass.recoverWith(_ => Resource.eval(IO.sleep(1.seconds)) >> polling)

      Stream
        .resource(polling)
        .repeat
  }

  class WorkGenerator(pieceLength: Long, totalLength: Long, pieces: ByteVector) {
    def this(metadata: TorrentMetadata) =
      this(
        metadata.pieceLength,
        metadata.files.map(_.length).sum,
        metadata.pieces
      )

    def pieceWork(index: Long): PieceWork =
      val thisPieceLength = math.min(pieceLength, totalLength - index * pieceLength)
      PieceWork(
        thisPieceLength,
        pieces.drop(index * 20).take(20),
        genRequests(index, thisPieceLength)
      )

    def genRequests(pieceIndex: Long, pieceLength: Long): Chain[Message.Request] =
      var result = Chain.empty[Message.Request]

      def loop(requestIndex: Long): Unit = {
        val thisChunkSize = math.min(ChunkSize, pieceLength - requestIndex * ChunkSize)
        if thisChunkSize > 0 then
          val begin = requestIndex * ChunkSize
          result = result.append(
            Message.Request(
              pieceIndex,
              begin,
              thisChunkSize
            )
          )
          loop(requestIndex + 1)
      }

      loop(0)
      result
  }

  case class PieceWork(
    size: Long,
    checksum: ByteVector,
    requests: Chain[Message.Request]
  )

  case object NoPieceAvailable extends Throwable("No piece available")

  trait WorkQueue[Request, Response] {
    def nextRequest: Resource[IO, (Request, Deferred[IO, Response])]
  }

  object WorkQueue {

    def apply[Request, Response](
      requests: Seq[Request],
      onComplete: Map[Request, Response] => IO[Any]
    ): IO[WorkQueue[Request, Response]] =
      require(requests.nonEmpty)
      for
        requestQueue <- Dequeue.unbounded[IO, Request]
        _ <- requests.traverse(requestQueue.offer)
        responses <- IO.ref(Map.empty[Request, Response])
        outstandingCount <- IO.ref(requests.size)
      yield new WorkQueue {
        override def nextRequest: Resource[IO, (Request, Deferred[IO, Response])] =
          Resource(
            for
              _ <- outstandingCount.get.flatMap(n => IO.raiseWhen(n == 0)(PieceComplete))
              request <- requestQueue.tryTake
              request <- request match
                case Some(request) => IO.pure(request)
                case None          => IO.raiseError(EmptyQueue)
              promise <- IO.deferred[Response]
            yield (
              (request, promise),
              for
                bytes <- promise.tryGet
                _ <- bytes match
                  case Some(bytes) =>
                    for
                      _ <- outstandingCount.update(_ - 1)
                      result <- responses.updateAndGet(_.updated(request, bytes))
                      _ <- outstandingCount.get.flatMap(n => IO.whenA(n == 0)(onComplete(result).void))
                    yield ()
                  case None =>
                    requestQueue.offerFront(request)
              yield ()
            )
          )
      }

    case object EmptyQueue extends Throwable
    case object PieceComplete extends Throwable
  }
}
