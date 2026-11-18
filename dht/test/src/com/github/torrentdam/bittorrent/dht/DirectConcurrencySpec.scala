package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.effect.direct.*
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/**
 * Regression test for the Scala Native delimcc `handler_pop` crash that forced
 * removal of cats-effect-direct (commit 35e3922): concurrent async/await blocks
 * on many fibers at once, mimicking a native DHT node answering a burst of
 * incoming get-peers queries.
 *
 * Fixed upstream by scala-native#5259 (stack-safe one-shot continuations,
 * merged 2026-11-15), included in 0.5.13-20261118 snapshots and later.
 */
class DirectConcurrencySpec extends CatsEffectSuite {

  test("many concurrent async blocks with nested awaits survive") {
    def oneQuery(i: Int): IO[Int] = async[IO] {
      val a = IO.pure(i).await
      IO.sleep(1.milli).await
      val b = IO.async[Int](cb => IO(cb(Right(a + 1))).as(Some(IO.unit))).await
      val c = async[IO] {
        val x = IO.pure(b * 2).await
        x + 1
      }.await
      c
    }

    (1 to 500).toList
      .parTraverse(i => oneQuery(i).timeout(10.seconds))
      .map(results => assertEquals(results.sum, (1 to 500).map(i => (i + 1) * 2 + 1).sum))
  }

  test("async blocks with try/finally under concurrency") {
    def guarded(i: Int): IO[Int] = async[IO] {
      try
        val a = IO.pure(i).await
        IO.sleep(1.milli).await
        a
      finally
        IO.unit.await
    }

    (1 to 200).toList
      .parTraverse(i => guarded(i).timeout(10.seconds))
      .map(results => assertEquals(results.toList, (1 to 200).toList))
  }

  test("sequential awaits inside a single async block") {
    async[IO] {
      val total = (1 to 50).foldLeft(0) { (acc, i) =>
        val next = IO.pure(acc + i).await
        next
      }
      total
    }.map(total => assertEquals(total, (1 to 50).sum))
  }
}
