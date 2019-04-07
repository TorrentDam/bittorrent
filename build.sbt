lazy val root = project
  .in(file("."))
  .settings(
    name := "bittorrent",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % "1.0.2",
      "org.scodec" %% "core" % "1.10.4",
      "org.typelevel" %% "cats-mtl-core" % "0.4.0",
      "org.scalatest" %% "scalatest" % "3.0.5",
      "com.olegpy" %% "meow-mtl" % "0.2.0",
      "com.github.julien-truffaut" %%  "monocle-core"  % Versions.monocle,
      "com.github.julien-truffaut" %%  "monocle-macro" % Versions.monocle,
    ),
    // compiler settings
    scalaVersion := "2.12.8",
    scalacOptions ++= Seq(
      "-language:higherKinds",
      "-Ypartial-unification",
    ),
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
  )

lazy val Versions = new {
  val monocle = "1.5.0" 
}
