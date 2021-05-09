import $ivy.`com.lihaoyi::mill-contrib-artifactory:$MILL_VERSION`

import mill._
import scalalib._
import scalafmt.ScalafmtModule
import mill.eval.Result
import coursier.maven.MavenRepository
import mill.contrib.artifactory.ArtifactoryPublishModule


object common extends Module with Publishing {
  def ivyDeps = Agg(
    Deps.`scodec-bits`,
  )
  object js extends JsModule with Publishing {
    def sources = common.sources
    def ivyDeps = common.ivyDeps
    def artifactName = common.artifactName
  }
}

object dht extends Module with Publishing {
  def moduleDeps = List(common)
  def ivyDeps = Agg(
    Deps.bencode,
    Deps.`cats-core`,
    Deps.`cats-effect`,
    Deps.`fs2-io`,
    Deps.logstage,
  )
  object test extends TestModule
}

object bittorrent extends Module with Publishing {
  def moduleDeps = List(common, dht)
  def ivyDeps = Agg(
    Deps.bencode,
    Deps.`cats-core`,
    Deps.`cats-effect`,
    Deps.`fs2-io`,
    Deps.`monocle-core`,
    Deps.`monocle-macro`,
    Deps.logstage,
  )
  object test extends TestModule
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "2.13.5"
  def scalacOptions = Seq(
    "-language:higherKinds",
    "-Ymacro-annotations",
  )
  def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://dl.bintray.com/lavrov/maven")
    )
  }
  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.3",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
  trait TestModule extends Tests {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit:0.7.23",
    )
    def testFrameworks = Seq(
      "munit.Framework"
    )
  }
}

trait JsModule extends Module with scalajslib.ScalaJSModule {
  def scalaJSVersion = "1.5.1"
  import mill.scalajslib.api.ModuleKind
  def moduleKind = ModuleKind.CommonJSModule
}

trait NativeImageModule extends ScalaModule {
  private def javaHome = T.input {
    T.ctx().env.get("JAVA_HOME") match {
      case Some(homePath) => Result.Success(os.Path(homePath))
      case None => Result.Failure("JAVA_HOME env variable is undefined")
    }
  }

  private def nativeImagePath = T.input {
    val path = javaHome()/"bin"/"native-image"
    if (os exists path) Result.Success(path)
    else Result.Failure(
      "native-image is not found in java home directory.\n" +
        "Make sure JAVA_HOME points to GraalVM JDK and " +
        "native-image is set up (https://www.graalvm.org/docs/reference-manual/native-image/)"
    )
  }

  def nativeImage = T {
    import ammonite.ops._
    implicit val workingDirectory = T.ctx().dest
    %%(
      nativeImagePath(),
      "-jar", assembly().path,
      "--no-fallback",
      "--initialize-at-build-time=scala",
      "--initialize-at-build-time=scala.runtime.Statics",
      "--enable-all-security-services",
      "--enable-http",
      "--enable-https",
    )
    finalMainClass()
  }
}

trait Publishing extends ArtifactoryPublishModule {
  import mill.scalalib.publish._

  def artifactoryUri  = "https://maven.pkg.github.com/TorrentDam/bittorrent"

  def artifactorySnapshotUri = ""

  def pomSettings = PomSettings(
    description = "BitTorrent",
    organization = "com.github.torrentdam",
    url = "https://github.com/TorrentDam/bittorrent",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("TorrentDam", "bittorrent"),
    developers = Seq(
      Developer("lavrov", "Vitaly Lavrov","https://github.com/lavrov")
    )
  )

  def publishVersion = "0.3.0"
}

object Versions {
  val cats = "2.2.0"
  val `cats-effect` = "2.2.0"
  val fs2 = "2.4.2"
  val monocle = "2.0.0"
  val logstage = "1.0.0-M1"
  val `scodec-bits` = "1.1.25"
  val upickle = "1.0.0"
  val monix = "3.2.2"
  val bencode = "0.2.0"
  val requests = "0.5.1"
}

object Deps {

  val `cats-core` = ivy"org.typelevel::cats-core::${Versions.cats}"
  val `cats-effect` = ivy"org.typelevel::cats-effect::${Versions.`cats-effect`}"

  val `fs2-io` = ivy"co.fs2::fs2-io::${Versions.fs2}"

  val `scodec-bits` = ivy"org.scodec::scodec-bits::${Versions.`scodec-bits`}"

  val logstage = ivy"io.7mind.izumi::logstage-core::${Versions.logstage}"

  val `monocle-core` = ivy"com.github.julien-truffaut::monocle-core::${Versions.monocle}"
  val `monocle-macro` = ivy"com.github.julien-truffaut::monocle-macro::${Versions.monocle}"

  val upickle = ivy"com.lihaoyi::upickle::${Versions.upickle}"

  val bencode = ivy"com.github.torrentdam::bencode::${Versions.bencode}"
}

