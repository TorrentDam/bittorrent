import mill._, scalalib._, scalafmt.ScalafmtModule

object bencode extends Module {
  def ivyDeps = Agg(
    ivy"org.scodec::core:1.10.4", 
    ivy"org.typelevel::cats-core:1.6.1",
  )
  object test extends TestModule
}

object bittorrent extends Module {
  def moduleDeps = List(bencode)
  def ivyDeps = Agg(
    ivy"org.typelevel::cats-mtl-core:0.4.0",
    ivy"org.typelevel::cats-effect::1.3.1",
    ivy"io.github.timwspence::cats-stm:0.4.0",
    ivy"co.fs2::fs2-io:1.0.4",
    ivy"com.olegpy::meow-mtl:0.2.0",
    ivy"io.chrisdavenport::log4cats-slf4j:0.3.0",
    ivy"ch.qos.logback:logback-classic:1.2.3",
    ivy"com.monovore::decline:0.5.0",
    ivy"com.github.julien-truffaut::monocle-core:${Versions.monocle}",
    ivy"com.github.julien-truffaut::monocle-macro:${Versions.monocle}",
    ivy"com.lihaoyi::pprint:0.5.5",
    ivy"com.github.bigwheel::util-backports:1.1",
  )
  object test extends TestModule
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "2.12.8"
  def scalacOptions = List(
    "-language:higherKinds",
    "-Ypartial-unification",
  )
  trait TestModule extends Tests {
    def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:3.0.5",
    )
    def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
}


object Versions {
  val monocle = "1.5.0"
}

