import mill._, scalalib._, scalafmt.ScalafmtModule
import $file.release, release.ReleaseModule

object bencode extends Module {
  def ivyDeps = Agg(
    ivy"org.scodec::scodec-core:1.11.4", 
    ivy"org.typelevel::cats-core:2.0.0",
  )
  object test extends TestModule
}

object bittorrent extends Module {
  def moduleDeps = List(bencode)
  def ivyDeps = Agg(
    ivy"org.typelevel::cats-effect::2.0.0",
    ivy"org.typelevel::cats-mtl-core:0.7.0",
    ivy"com.olegpy::meow-mtl:0.3.0-M1",
    ivy"io.github.timwspence::cats-stm:0.5.0",
    ivy"co.fs2::fs2-io:2.0.0",
    ivy"io.7mind.izumi::logstage-core:0.9.5",
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

object server extends Module {
  def moduleDeps = List(bittorrent)
  def ivyDeps = Agg(
    ivy"com.spinoco::fs2-http:0.4.2-SNAPSHOT", // todo
  )
}

object client extends Module with scalajslib.ScalaJSModule {
  import mill.scalajslib.api.ModuleKind
  def scalaJSVersion = "0.6.28"
  def moduleKind = ModuleKind.CommonJSModule
  def ivyDeps = Agg(
    ivy"me.shadaj::slinky-web::0.6.2",
    ivy"co.fs2::fs2-core::2.0.0",
  )

  def scalacOptions = super.scalacOptions() ++ List(
    "-P:scalajs:sjsDefinedByDefault"
  )

  def `package`: T[PathRef] = T {
    val bundleFile = T.ctx().dest / "bundle.js"
    fullOpt()
    os
      .proc("npm", "run", "package", "--", s"--output=$bundleFile")
      .call(millSourcePath)
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
  def scalaVersion = "2.13.0"
  def scalacOptions = List(
    "-language:higherKinds",
    "-Ymacro-annotations",
  )

  def scalacPluginIvyDeps = Agg(
    ivy"com.olegpy::better-monadic-for:0.3.1",
    ivy"org.typelevel::kind-projector:0.10.3",
  )
  trait TestModule extends Tests {
    def ivyDeps = Agg(
      ivy"com.eed3si9n.verify::verify:0.2.0",
    )
    def testFrameworks = Seq("verify.runner.Framework")
  }
}


object Versions {
  val monocle = "2.0.0"
}

