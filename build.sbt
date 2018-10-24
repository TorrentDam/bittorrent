lazy val root = project
  .in(file("."))
  .settings(
    name := "whirpool",
    version := "0.1.0",
    scalaVersion := "2.12.7",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % "1.0.0",
      "org.tpolecat" %% "atto-refined" % "0.6.3",
    )
  )
