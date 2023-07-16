lazy val root = project
  .in(file("."))
  .aggregate(
    common.jvm, common.js,
    bittorrent.jvm,
    dht.jvm,
    cmd.jvm,
  )

inThisBuild(
  List(
    scalaVersion := "3.3.0",
    scalacOptions ++= List(
      "-source:future",
      "-Ykind-projector:underscores"
    ),
    libraryDependencies ++= List(
      Deps.`munit-cats-effect`.value % Test
    ),
    organization := "io.github.torrentdam.bittorrent",
    version := sys.env.getOrElse("VERSION", "SNAPSHOT"),
    description := "Bittorrent client",
    publishTo := {
      val nexus = "https://s01.oss.sonatype.org/"
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    credentials ++= {
      sys.env.get("SONATYPE_CREDS") match {
        case Some(credentials) =>
          val Array(username, password) = credentials.split(':')
          List(
            Credentials(
              "Sonatype Nexus Repository Manager",
              "s01.oss.sonatype.org",
              username,
              password
            )
          )
        case None => List.empty[Credentials]
      }
    },
    developers := List(
      Developer(
        id = "lavrov",
        name = "Vitaly Lavrov",
        email = "lavrovvv@gmail.com",
        url = url("https://github.com/lavrov")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/TorrentDamDev/bittorrent"),
        "scm:git@github.com:TorrentDamDev/bittorrent.git"
      )
    ),
    licenses := List("Unlicense" -> new URL("https://unlicense.org/")),
    homepage := Some(url("https://torrentdam.github.io/"))
  )
)

lazy val common = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(
    libraryDependencies ++= Seq(
      Deps.`scodec-bits`.value,
      Deps.`cats-effect`.value,
      Deps.ip4s.value,
    )
  )

lazy val bittorrent = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      Deps.bencode.value,
      Deps.`cats-core`.value,
      Deps.`cats-effect`.value,
      Deps.`fs2-io`.value,
      Deps.`monocle-core`.value,
      Deps.`monocle-macro`.value,
      Deps.`woof-core`.value,
      Deps.`cats-effect-cps`.value,
    )
  )

lazy val dht = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      Deps.bencode.value,
      Deps.`scodec-bits`.value,
      Deps.`cats-core`.value,
      Deps.`cats-effect`.value,
      Deps.`fs2-io`.value,
      Deps.`woof-core`.value,
      Deps.`cats-effect-cps`.value,
    )
  )

lazy val files = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .dependsOn(common, bittorrent)
  .settings(
    libraryDependencies ++= Seq(
      Deps.`scodec-bits`.value,
      Deps.`cats-core`.value,
      Deps.`cats-effect`.value,
      Deps.`fs2-io`.value,
      Deps.`woof-core`.value,
      Deps.`cats-effect-cps`.value,
    )
  )

lazy val cmd = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .dependsOn(
    bittorrent,
    dht,
    files,
  )
  .settings(
    libraryDependencies ++= Seq(
      Deps.decline.value,
      Deps.`woof-core`.value,
      Deps.`cats-effect-cps`.value,
    ),
  )
  .enablePlugins(JavaAppPackaging)

lazy val Versions = new {
  val cats = "2.9.0"
  val `cats-effect` = "3.5.0"
  val ip4s = "3.3.0"
  val fs2 = "3.8-a43eaac"
  val epollcat = "0.1.4"
  val monocle = "3.2.0"
  val `scodec-bits` = "1.1.37"
  val bencode = "1.1.0"
  val decline = "2.4.1"
  val woof = "0.6.0"
  val `cats-effect-cps` = "0.4.0"
}

lazy val Deps = new {

  val `cats-core` = Def.setting("org.typelevel" %%% "cats-core" % Versions.cats)
  val `cats-effect` = Def.setting("org.typelevel" %%% "cats-effect" % Versions.`cats-effect`)

  val ip4s = Def.setting("com.comcast" %%% "ip4s-core" % Versions.ip4s)

  val `fs2-io` = Def.setting("co.fs2" %%% "fs2-io" % Versions.fs2)

  val `scodec-bits` = Def.setting("org.scodec" %%% "scodec-bits" % Versions.`scodec-bits`)

  val `woof-core` = Def.setting("org.legogroup" %%% "woof-core" % Versions.woof)

  val `monocle-core` = Def.setting("dev.optics" %%% "monocle-core" % Versions.monocle)
  val `monocle-macro` = Def.setting("dev.optics" %%% "monocle-macro" % Versions.monocle)

  val bencode = Def.setting("io.github.torrentdam.bencode" %%% "bencode" % Versions.bencode)

  val `munit-cats-effect` = Def.setting("org.typelevel" %%% "munit-cats-effect-3" % "1.0.7")

  val decline = Def.setting("com.monovore" %%% "decline-effect" % Versions.decline)

  val `cats-effect-cps` = Def.setting("org.typelevel" %%% "cats-effect-cps" % Versions.`cats-effect-cps`)
}
