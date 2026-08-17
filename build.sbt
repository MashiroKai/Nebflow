import Dependencies._

ThisBuild / scalaVersion := "3.5.2"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val root = (project in file("."))
  .settings(
    name := "nebflow",
    version := IO.read(file("VERSION")).trim,
    organization := "nebflow",
    // Include VERSION file in JAR so runtime version detection works from any directory
    Compile / unmanagedResources += baseDirectory.value / "VERSION",
    // L1 rebrand: repo-root brand.conf is the ONLY edit point for brand values;
    // package it into the JAR so runtime Branding reads it from the classpath.
    // No second copy under src/main/resources — single source, no drift.
    Compile / unmanagedResources += baseDirectory.value / "brand.conf",
    // P1 web bundle: mount build/ (containing web-dist/) as a resource dir ONLY
    // when explicitly requested via -Dnebflow.webdist=1 (CI: sbt -Dnebflow.webdist=1
    // assembly). A dev machine that once ran scripts/build-web.mjs must not have
    // `sbt run` silently serve the stale dist tree instead of the live sources.
    Compile / unmanagedResourceDirectories ++= {
      val d = baseDirectory.value / "build"
      if (sys.props.contains("nebflow.webdist") && (d / "web-dist" / "index.html").exists()) Seq(d)
      else Seq.empty
    },
    scalaVersion := "3.5.2",

    libraryDependencies ++= Seq(
      // CLI
      scopt,
      // Terminal
      jline3Terminal,
      jline3Reader,
      // HTTP client
      sttpCore,
      sttpFs2Backend,
      // HTTP server (gateway)
      http4sEmberServer,
      http4sDsl,
      http4sCirce,
      // Streaming
      fs2Core,
      // Effect
      catsEffect,
      // JSON
      circeCore,
      circeParser,
      circeGeneric,
      circeYaml,
      // Process / File
      osLib,
      // Logging
      logbackClassic,
      logbackCore,
      // Browser automation (optional — not bundled in distribution, detected at runtime)
      playwright % "provided",
      // Diff
      diffUtils,
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

    Compile / run / mainClass := Some("nebflow.Main"),
    run / fork := true,
    run / connectInput := true,

    // Tests share PathUtil global state — sequential execution prevents interference
    Test / parallelExecution := false,

    // Java options
    javaOptions ++= Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "-XX:+UseZGC",
      "-XX:+ZGenerational",
      "-Xms512m",
      "-Xmx2g",
      "-XX:+UseStringDeduplication",
      "-XX:+AlwaysPreTouch",
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
