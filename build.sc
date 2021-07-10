import $ivy.`com.lihaoyi::mill-contrib-artifactory:$MILL_VERSION`

import mill._
import scalalib._
import scalafmt.ScalafmtModule
import coursier.maven.MavenRepository
import mill.contrib.artifactory.ArtifactoryPublishModule


object common extends Module with Publishing {
  def ivyDeps = Agg(
    Deps.`scodec-bits`,
    Deps.ip4s,
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
    Deps.`scodec-bits`,
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

object cmd extends Module {
  def moduleDeps = List(dht, bittorrent)
  def ivyDeps = Agg(
    Deps.decline,
    Deps.`logback-classic`,
  )
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "3.0.1"
  def scalacOptions = Seq(
    "-source:future",
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
  trait TestModule extends Tests with mill.scalalib.TestModule.Munit {
    def ivyDeps = Agg(
      Deps.`munit-cats-effect`,
      Deps.`log4cats-noop`
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

  def publishVersion = T {
    T.ctx.env.getOrElse("GITHUB_REF", "1.0.0").stripPrefix("refs/tags/")
  }
}

object Versions {
  val cats = "2.6.1"
  val `cats-effect` = "3.1.1"
  val ip4s = "3.0.3"
  val fs2 = "3.0.6"
  val monocle = "3.0.0"
  val log4cats = "2.1.1"
  val `scodec-bits` = "1.1.27"
  val upickle = "1.0.0"
  val bencode = "1.0.2"
  val requests = "0.5.1"
  val decline = "2.1.0"
}

object Deps {

  val `cats-core` = ivy"org.typelevel::cats-core::${Versions.cats}"
  val `cats-effect` = ivy"org.typelevel::cats-effect::${Versions.`cats-effect`}"

  val ip4s = ivy"com.comcast::ip4s-core::${Versions.ip4s}"

  val `fs2-io` = ivy"co.fs2::fs2-io::${Versions.fs2}"

  val `scodec-bits` = ivy"org.scodec::scodec-bits::${Versions.`scodec-bits`}"

  val log4cats = ivy"org.typelevel::log4cats-slf4j::${Versions.log4cats}"
  val `log4cats-noop` = ivy"org.typelevel::log4cats-noop::${Versions.log4cats}"
  val `logback-classic` = ivy"ch.qos.logback:logback-classic:1.2.3"

  val `monocle-core` = ivy"dev.optics::monocle-core::${Versions.monocle}"
  val `monocle-macro` = ivy"dev.optics::monocle-macro::${Versions.monocle}"

  val upickle = ivy"com.lihaoyi::upickle::${Versions.upickle}"

  val bencode = ivy"com.github.torrentdam::bencode::${Versions.bencode}"

  val `munit-cats-effect` = ivy"org.typelevel::munit-cats-effect-3::1.0.5"

  val decline = ivy"com.monovore::decline-effect:${Versions.decline}"
}

