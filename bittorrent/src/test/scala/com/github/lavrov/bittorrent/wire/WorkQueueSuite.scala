package com.github.lavrov.bittorrent.wire

import cats.effect.{IO, Resource}
import cats.effect.std.CountDownLatch
import cats.effect.syntax.temporal.genTemporalOps_
import com.github.lavrov.bittorrent.wire.RequestDispatcher.WorkQueue
import com.github.lavrov.bittorrent.wire.RequestDispatcher.WorkQueue.EmptyQueue
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

class WorkQueueSuite extends munit.CatsEffectSuite {

  test("return request")(
    for
      workQueue <- WorkQueue(Seq(1), _ => IO.unit)
      request <- workQueue.nextRequest.use((request, _) => IO.pure(request))
    yield
      assertEquals(request, 1)
  )

  test("put request back into queue if it was not completed")(
    for
      workQueue <- WorkQueue(Seq(1, 2), _ => IO.unit)
      request0 <- workQueue.nextRequest.use((request, _) => IO.pure(request))
      request1 <- workQueue.nextRequest.use((request, _) => IO.pure(request))
    yield
      assertEquals(request0, 1)
      assertEquals(request1, 1)
  )
  test("delete request from queue if it was completed")(
    for
      workQueue <- WorkQueue(Seq(1, 2), _ => IO.unit)
      request0 <- workQueue.nextRequest.use((request, promise) => promise.complete(()).as(request))
      request1 <- workQueue.nextRequest.use((request, _) => IO.pure(request))
    yield
      assertEquals(request0, 1)
      assertEquals(request1, 2)
  )
  test("throw PieceComplete when last request was fulfilled")(
    for
      workQueue <- WorkQueue(Seq(1), _ => IO.unit)
      request <- workQueue.nextRequest.use((request, promise) => promise.complete(ByteVector.empty).as(request))
      result <- workQueue.nextRequest.use((request, _) => IO.pure(request)).attempt
    yield
      assertEquals(request, 1)
      assertEquals(result, Left(WorkQueue.PieceComplete))
  )
  test("throw EmptyQueue when queue is empty")(
    for
      workQueue <- WorkQueue(Seq(1), _ => IO.unit)
      finishFirst <- CountDownLatch[IO](1)
      fiber0 <- workQueue.nextRequest.use((request, _) => finishFirst.await.as(request)).start
      fiber1 <- workQueue.nextRequest.use((request, _) => IO.pure(request)).start
      _ <- finishFirst.release
      request0 <- fiber0.joinWithNever
      request1 <- fiber1.joinWithNever.attempt
    yield
      assertEquals(request0, 1)
      assertEquals(request1, Left(EmptyQueue))
  )
}
