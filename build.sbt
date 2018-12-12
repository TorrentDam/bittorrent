lazy val root = project
  .in(file("."))
  .settings(
    name := "bittorrent",
    version := "0.1.0",
    scalaVersion := "2.12.7",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % "1.0.0",
      "org.scodec" %% "core" % "1.10.4",
      "io.estatico" %% "newtype" % "0.4.2",
      "org.scalatest" %% "scalatest" % "3.0.5",
    ),
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
