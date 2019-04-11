import mill._, scalalib._

object bencode extends Module {
  def ivyDeps = Agg(
    ivy"org.scodec::core:1.10.4",
    ivy"org.typelevel::cats-mtl-core:0.4.0",
  )
  object test extends TestModule
}

object bittorrent extends Module {
  def moduleDeps = List(bencode)
  def ivyDeps = Agg(
    ivy"co.fs2::fs2-io:1.0.2",
    ivy"com.olegpy::meow-mtl:0.2.0",
    ivy"com.github.julien-truffaut::monocle-core:${Versions.monocle}",
    ivy"com.github.julien-truffaut::monocle-macro:${Versions.monocle}",
  )
  object test extends TestModule
}

trait Module extends ScalaModule {
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

