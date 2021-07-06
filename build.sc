import $ivy.`com.lihaoyi::mill-contrib-artifactory:$MILL_VERSION`

import mill._
import scalalib._
import scalafmt.ScalafmtModule
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
    Deps.log4cats,
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
    Deps.log4cats,
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
      MavenRepository(
        "https://maven.pkg.github.com/TorrentDam/bencode",
        T.env.get("GITHUB_TOKEN").map { token =>
          coursier.core.Authentication("lavrov", token)
        }
      )
    )
  }
  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.3",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
  trait TestModule extends Tests with mill.scalalib.TestModule.Munit {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit:0.7.23",
    )
  }
}

trait JsModule extends Module with scalajslib.ScalaJSModule {
  def scalaJSVersion = "1.5.1"
  import mill.scalajslib.api.ModuleKind
  def moduleKind = ModuleKind.CommonJSModule
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
  val cats = "2.6.1"
  val `cats-effect` = "2.5.1"
  val fs2 = "2.5.8"
  val monocle = "2.0.0"
  val log4cats = "1.3.1"
  val `scodec-bits` = "1.1.25"
  val upickle = "1.0.0"
  val bencode = "0.2.0"
  val requests = "0.5.1"
}

object Deps {

  val `cats-core` = ivy"org.typelevel::cats-core::${Versions.cats}"
  val `cats-effect` = ivy"org.typelevel::cats-effect::${Versions.`cats-effect`}"

  val `fs2-io` = ivy"co.fs2::fs2-io::${Versions.fs2}"

  val `scodec-bits` = ivy"org.scodec::scodec-bits::${Versions.`scodec-bits`}"

  val log4cats = ivy"org.typelevel::log4cats-slf4j::${Versions.log4cats}"

  val `monocle-core` = ivy"com.github.julien-truffaut::monocle-core::${Versions.monocle}"
  val `monocle-macro` = ivy"com.github.julien-truffaut::monocle-macro::${Versions.monocle}"

  val upickle = ivy"com.lihaoyi::upickle::${Versions.upickle}"

  val bencode = ivy"com.github.torrentdam::bencode::${Versions.bencode}"
}

