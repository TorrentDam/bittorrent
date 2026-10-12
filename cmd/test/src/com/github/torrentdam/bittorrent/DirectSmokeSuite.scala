package com.github.torrentdam.bittorrent

import cats.effect.IO
import cats.effect.Resource
import cats.effect.ResourceIO
import cats.effect.direct.*
import com.comcast.ip4s.SocketAddress
import munit.CatsEffectSuite
import scala.concurrent.duration.DurationInt

class DirectSmokeSuite extends munit.CatsEffectSuite {

  test("async[IO] across async boundaries") {
    val program = async[IO] {
      IO.sleep(50.millis).await
      IO.sleep(50.millis).await
      42
    }
    program.map(v => assertEquals(v, 42))
  }

  test("async[ResourceIO] basic") {
    val program = async[ResourceIO] {
      Resource.eval(IO.sleep(50.millis)).await
      Resource.eval(IO.pure(1)).await
      42
    }
    program.use(IO.pure).map(v => assertEquals(v, 42))
  }

  test("async[IO] with DNS resolve") {
    val program = async[IO] {
      val sa = SocketAddress.fromStringIp("127.0.0.1:6881").get
      val addr = sa.resolve[IO].await
      addr.toString
    }
    program.map(v => assert(v.nonEmpty))
  }
}
