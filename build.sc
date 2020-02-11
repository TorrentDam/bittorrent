import mill._, scalalib._, scalafmt.ScalafmtModule
import $file.release, release.ReleaseModule

object bencode extends Module {
  def ivyDeps = Agg(
    ivy"org.scodec::scodec-core:1.11.4", 
    ivy"org.typelevel::cats-core:2.1.0",
  )
  object test extends TestModule
}

object bittorrent extends Module {
  def moduleDeps = List(bencode)
  def ivyDeps = Agg(
    ivy"org.typelevel::cats-effect::2.1.0",
    ivy"org.typelevel::cats-mtl-core:0.7.0",
    ivy"com.olegpy::meow-mtl-effects:0.4.0",
    ivy"io.github.timwspence::cats-stm:0.5.0",
    ivy"co.fs2::fs2-io:2.1.0",
    ivy"io.7mind.izumi::logstage-core:${Versions.logstage}",
    ivy"com.github.julien-truffaut::monocle-core:${Versions.monocle}",
    ivy"com.github.julien-truffaut::monocle-macro:${Versions.monocle}",
  )
  object test extends TestModule
}

object cli extends Module with ReleaseModule {
  def moduleDeps = List(bittorrent)
  def ivyDeps = Agg(
    ivy"com.monovore::decline:1.0.0",
    ivy"ch.qos.logback:logback-classic:1.2.3",
    ivy"com.lihaoyi::pprint:0.5.5",
  )
}

object shared extends Module {
  def ivyDeps = Agg(
    ivy"com.lihaoyi::upickle:0.8.0",
    ivy"org.scodec::scodec-core:1.11.4",
  )
  object js extends JsModule {
    def sources = shared.sources
    def ivyDeps = Agg(
      ivy"com.lihaoyi::upickle::0.8.0",
      ivy"org.scodec::scodec-core::1.11.4",
    )
  }
}

object server extends Module {
  def moduleDeps = List(bittorrent, shared)
  private val http4sVersion = "0.21.0-M6"
  def ivyDeps = Agg(
    ivy"org.http4s::http4s-core:$http4sVersion",
    ivy"org.http4s::http4s-dsl:$http4sVersion",
    ivy"org.http4s::http4s-blaze-server:$http4sVersion",
    ivy"io.7mind.izumi::logstage-adapter-slf4j:${Versions.logstage}",
  )
}

object client extends JsModule {
  def moduleDeps = List(shared.js)
  def ivyDeps = Agg(
    ivy"me.shadaj::slinky-web::0.6.2",
    ivy"co.fs2::fs2-core::2.0.0",
    ivy"org.scodec::scodec-core::1.11.4",
    ivy"org.typelevel::squants::1.6.0"
  )

  def `package`: T[PathRef] = T {
    val bundleFile = T.ctx().dest / "bundle.js"
    fullOpt()
    os
      .proc("npm", "run", "package", "--", s"--output=$bundleFile")
      .call(cwd = millSourcePath, stdout = os.Inherit)
    PathRef(bundleFile)
  }

  def dist: T[PathRef] = T {
    val resourceFiles = resources().flatMap(pathRef => os.list(pathRef.path))
    val distributionFiles = `package`().path +: resourceFiles
    val desitnation = T.ctx().dest
    distributionFiles.foreach { f => os.copy.into(f, desitnation) }
    PathRef(desitnation)
  }

  /** fastOpt deletes whole dest directory which breaks webpack hot reload */
  def compileJs: T[PathRef] = T {
    val fastOptFile = fastOpt()
    val outputFile = T.ctx.dest
    os.copy.into(fastOptFile.path, outputFile, replaceExisting = true)
    PathRef(outputFile)
  }
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "2.13.1"
  def scalacOptions = List(
    "-language:higherKinds",
    "-Ymacro-annotations",
  )

  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.0",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
  trait TestModule extends Tests {
    def ivyDeps = Agg(
      ivy"com.eed3si9n.verify::verify:0.2.0",
    )
    def testFrameworks = Seq("verify.runner.Framework")
  }
}

trait JsModule extends Module with scalajslib.ScalaJSModule {
  def scalaJSVersion = "0.6.31"
  import mill.scalajslib.api.ModuleKind
  def moduleKind = ModuleKind.CommonJSModule
}


object Versions {
  val monocle = "2.0.0"
  val logstage = "0.9.5"
}

