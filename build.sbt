lazy val root = project.in(file("."))
  .aggregate(
    common.jvm, common.js, dht, bittorrent, cmd
  )

inThisBuild(
  List(
    scalaVersion := "3.0.2",
    scalacOptions ++= List(
      "-source:future",
      "-Ykind-projector:underscores",
    ),
    libraryDependencies ++= List(
      Deps.`munit-cats-effect` % Test,
      Deps.`log4cats-noop` % Test,
    ),
    organization := "com.github.torrentdam.bittorrent",
    githubOwner := "TorrentDamDev",
    githubRepository := "bittorrent",
    resolvers += Resolver.githubPackages("TorrentDamDev"),
  )
)

lazy val common = crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure)
  .settings(
    libraryDependencies ++= Seq(
      Deps.`scodec-bits`.value,
      Deps.`cats-effect`.value,
      Deps.ip4s.value,
    )
  )

lazy val dht = project
  .dependsOn(common.jvm)
  .settings(
    libraryDependencies ++= Seq(
      Deps.bencode,
      Deps.`scodec-bits`.value,
      Deps.`cats-core`.value,
      Deps.`cats-effect`.value,
      Deps.`fs2-io`,
      Deps.log4cats,
    )
  )

lazy val bittorrent = project
  .dependsOn(common.jvm, dht)
  .settings(
    libraryDependencies ++= Seq(
      Deps.bencode,
      Deps.`cats-core`.value,
      Deps.`cats-effect`.value,
      Deps.`fs2-io`,
      Deps.`monocle-core`,
      Deps.`monocle-macro`,
      Deps.log4cats,
    )
  )

lazy val cmd = project
  .dependsOn(dht, bittorrent)
  .settings(
    libraryDependencies ++= Seq(
      Deps.decline,
      Deps.`logback-classic`,
    )
  )

lazy val Versions = new {
  val cats = "2.6.1"
  val `cats-effect` = "3.2.8"
  val ip4s = "3.0.3"
  val fs2 = "3.1.2"
  val monocle = "3.0.0"
  val log4cats = "2.1.1"
  val `scodec-bits` = "1.1.27"
  val bencode = "1.0.2"
  val decline = "2.1.0"
}

lazy val Deps = new {

  val `cats-core` = Def.setting("org.typelevel" %%% "cats-core" % Versions.cats)
  val `cats-effect` = Def.setting("org.typelevel" %%% "cats-effect" % Versions.`cats-effect`)

  val ip4s = Def.setting("com.comcast" %%% "ip4s-core" % Versions.ip4s)

  val `fs2-io` = "co.fs2" %% "fs2-io" % Versions.fs2

  val `scodec-bits` = Def.setting("org.scodec" %%% "scodec-bits" % Versions.`scodec-bits`)

  val log4cats = "org.typelevel" %% "log4cats-slf4j" % Versions.log4cats
  val `log4cats-noop` = "org.typelevel" %% "log4cats-noop" % Versions.log4cats
  val `logback-classic` = "ch.qos.logback" % "logback-classic" % "1.2.3"

  val `monocle-core` = "dev.optics" %% "monocle-core" % Versions.monocle
  val `monocle-macro` = "dev.optics" %% "monocle-macro" % Versions.monocle

  val bencode = "com.github.torrentdam" %% "bencode" % Versions.bencode

  val `munit-cats-effect` = "org.typelevel" %% "munit-cats-effect-3" % "1.0.5"

  val decline = "com.monovore" %% "decline-effect" % Versions.decline
}

