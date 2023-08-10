package com.github.torrentdam.bittorrent.wire

import cats.effect.cps.*
import cats.data.Chain
import cats.effect.kernel.Deferred
import cats.effect.std.Dequeue
import cats.effect.std.Semaphore
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.protocol.message.Message.Request
import com.github.torrentdam.bittorrent.PeerInfo
import com.github.torrentdam.bittorrent.TorrentMetadata
import com.github.torrentdam.bittorrent.protocol.message.Message
import com.github.torrentdam.bittorrent.CrossPlatform
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.Stream
import java.util.UUID
import org.legogroup.woof.given
import org.legogroup.woof.Logger

import scala.collection.immutable.BitSet
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.DurationInt
import scodec.bits.ByteVector

trait RequestDispatcher {
  def downloadPiece(index: Long): IO[ByteVector]

  def stream(
    availability: IO[BitSet],
    speedClass: IO[SpeedClass]
  ): Resource[IO, (Message.Request, Deferred[IO, ByteVector])]
}

object RequestDispatcher {
  val ChunkSize: Int = 16 * 1024

  private type RequestQueue = WorkQueue[Message.Request, ByteVector]

  def apply(metadata: TorrentMetadata)(using logger: Logger[IO]): Resource[IO, RequestDispatcher] =
    for
      queue <- Resource.eval(IO.ref(TreeMap.empty[Long, RequestQueue]))
      queueReverse <- Resource.eval(IO.ref(TreeMap.empty[Long, RequestQueue](using Ordering[Long].reverse)))
      workGenerator = WorkGenerator(metadata)
    yield Impl(workGenerator, queue, queueReverse)

  private class Impl(
    workGenerator: WorkGenerator,
    queue: Ref[IO, TreeMap[Long, RequestQueue]],
    queueReverse: Ref[IO, TreeMap[Long, RequestQueue]]
  )(using Logger[IO]) extends RequestDispatcher {
    def downloadPiece(index: Long): IO[ByteVector] =
      val pieceWork = workGenerator.pieceWork(index)
      val attempt = async[IO] {
        try
          val result = IO.deferred[Map[Request, ByteVector]].await
          val requestQueue = WorkQueue(pieceWork.requests.toList, result.complete).await
          queue.update(_.updated(index, requestQueue)).await
          queueReverse.update(_.updated(index, requestQueue)).await
          val completedBlocks = result.get.await
          val pieceBytes = completedBlocks.toList.sortBy(_._1.begin).map(_._2).foldLeft(ByteVector.empty)(_ ++ _)
          pieceBytes
        finally
          queue.update(_ - index).await
          queueReverse.update(_ - index).await
      }
      attempt.flatMap(bytes =>
        if CrossPlatform.sha1(bytes) == pieceWork.checksum then IO.pure(bytes)
        else
          Logger[IO].warn(s"Piece $index failed checksum") >> attempt
      )

    def stream(
      availability: IO[BitSet],
      speedClass: IO[SpeedClass]
    ): Resource[IO, (Message.Request, Deferred[IO, ByteVector])] =
      def pickFrom(trackers: List[RequestQueue]): Resource[IO, (Message.Request, Deferred[IO, ByteVector])] =
        trackers match
          case Nil =>
            Resource.eval(IO.raiseError(NoPieceAvailable))
          case tracker :: rest =>
            tracker.nextRequest.recoverWith(_ => pickFrom(rest))

      def singlePass =
        for
          speedClass <- Resource.eval(speedClass)
          inProgress <- Resource.eval(
            speedClass match
              case SpeedClass.Fast => queue.get
              case SpeedClass.Slow => queueReverse.get // take piece with the highest index first
          )
          availability <- Resource.eval(availability)
          matched = inProgress.collect { case (index, tracker) if availability(index.toInt) => tracker }.toList
          result <- pickFrom(matched)
        yield result

      def polling: Resource[IO, (Message.Request, Deferred[IO, ByteVector])] =
        singlePass.recoverWith(_ => Resource.eval(IO.sleep(1.seconds)) >> polling)

      polling
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

  trait WorkQueue[Work, Result] {
    def nextRequest: Resource[IO, (Work, Deferred[IO, Result])]
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
      yield new {
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
