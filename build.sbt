lazy val root = project.in(file("."))
  .aggregate(
    common.jvm, common.js, dht, bittorrent, tracker
  )

inThisBuild(
  List(
    scalaVersion := "3.1.0",
    scalacOptions ++= List(
      "-source:future",
      "-Ykind-projector:underscores",
    ),
    libraryDependencies ++= List(
      Deps.`munit-cats-effect`.value % Test,
      Deps.`log4cats-noop`.value % Test,
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

lazy val common = crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure)
  .settings(
    libraryDependencies ++= Seq(
      Deps.`scodec-bits`.value,
      Deps.`cats-effect`.value,
      Deps.ip4s.value,
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

lazy val tracker = project
  .dependsOn(common.jvm)
  .settings(
    libraryDependencies ++= Seq(
      Deps.bencode,
      Deps.`http4s-client`,
      Deps.`http4s-blaze-client` % Test,
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
  val fs2 = "3.2.4"
  val monocle = "3.0.0"
  val log4cats = "2.1.1"
  val `scodec-bits` = "1.1.27"
  val bencode = "1.0.0"
  val decline = "2.2.0"
  val logback = "1.2.10"
  val http4s = "1.0.0-M30"
}

lazy val Deps = new {

  val `cats-core` = Def.setting("org.typelevel" %%% "cats-core" % Versions.cats)
  val `cats-effect` = Def.setting("org.typelevel" %%% "cats-effect" % Versions.`cats-effect`)

  val ip4s = Def.setting("com.comcast" %%% "ip4s-core" % Versions.ip4s)

  val `fs2-io` = "co.fs2" %% "fs2-io" % Versions.fs2

  val `scodec-bits` = Def.setting("org.scodec" %%% "scodec-bits" % Versions.`scodec-bits`)

  val log4cats = "org.typelevel" %% "log4cats-slf4j" % Versions.log4cats
  val `log4cats-noop` = Def.setting("org.typelevel" %%% "log4cats-noop" % Versions.log4cats)
  val `logback-classic` = "ch.qos.logback" % "logback-classic" % Versions.logback

  val `monocle-core` = "dev.optics" %% "monocle-core" % Versions.monocle
  val `monocle-macro` = "dev.optics" %% "monocle-macro" % Versions.monocle

  val bencode = "io.github.torrentdam.bencode" %% "bencode" % Versions.bencode

  val `munit-cats-effect` = Def.setting("org.typelevel" %%% "munit-cats-effect-3" % "1.0.5")

  val decline = "com.monovore" %% "decline-effect" % Versions.decline

  val `http4s-client` = "org.http4s" %% "http4s-client" % Versions.http4s
  val `http4s-blaze-client` = "org.http4s" %% "http4s-blaze-client" % Versions.http4s
}

