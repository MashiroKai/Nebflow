import Dependencies._

lazy val root = (project in file("."))
  .settings(
    name := "nebflow",
    version := "1.0.0",
    scalaVersion := "3.5.2",
    organization := "nebflow",

    libraryDependencies ++= Seq(
      // CLI
      scopt,
      // Terminal
      jline3Terminal,
      jline3Reader,
      // HTTP
      sttpCore,
      sttpFs2Backend,
      // Streaming
      fs2Core,
      // Effect
      catsEffect,
      // JSON
      circeCore,
      circeParser,
      circeGeneric,
      // Process / File
      osLib,
      // Logging
      sl4jNop,
      // Testing
      munit,
      munitCatsEffect,
    ),

    // Compiler options
    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-Xfatal-warnings",
    ),

    run / fork := true,

    // Java options
    javaOptions ++= Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    ),

    // Assembly settings (fat JAR fallback)
    assembly / assemblyMergeStrategy := {
      case x if x.endsWith("module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    assembly / mainClass := Some("nebflow.Main"),
  )
  .enablePlugins(AssemblyPlugin)
