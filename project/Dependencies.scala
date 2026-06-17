import sbt._

object Dependencies {
  // Versions
  val CatsEffectVer = "3.6.1"
  val Fs2Ver = "3.11.0"
  val CirceVer = "0.14.12"
  val Http4sVer = "0.23.30"
  val SttpVer = "4.0.0-M1"
  val JLine3Ver = "3.29.0"
  val ScoptVer = "4.1.0"
  val OsLibVer = "0.11.3"
  val MunitVer = "1.1.0"
  val MunitCEVer = "2.0.0"

  // CLI
  val scopt = "com.github.scopt" %% "scopt" % ScoptVer

  // Terminal
  val jline3Terminal = "org.jline" % "jline-terminal" % JLine3Ver
  val jline3Reader = "org.jline" % "jline-reader" % JLine3Ver

  // HTTP
  val sttpCore = "com.softwaremill.sttp.client4" %% "core" % SttpVer
  val sttpFs2Backend = "com.softwaremill.sttp.client4" %% "fs2" % SttpVer

  // Streaming
  val fs2Core = "co.fs2" %% "fs2-core" % Fs2Ver

  // Effect
  val catsEffect = "org.typelevel" %% "cats-effect" % CatsEffectVer

  // JSON
  val circeCore = "io.circe" %% "circe-core" % CirceVer
  val circeParser = "io.circe" %% "circe-parser" % CirceVer
  val circeGeneric = "io.circe" %% "circe-generic" % CirceVer
  val circeYaml = "io.circe" %% "circe-yaml" % "0.14.2"

  // Process / File
  val osLib = "com.lihaoyi" %% "os-lib" % OsLibVer

  // http4s (gateway server)
  val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % Http4sVer
  val http4sDsl         = "org.http4s" %% "http4s-dsl"          % Http4sVer
  val http4sCirce       = "org.http4s" %% "http4s-circe"        % Http4sVer

  // Actor
  val PekkoVer = "1.1.3"
  val pekkoActorTyped = "org.apache.pekko" %% "pekko-actor-typed" % PekkoVer
  val pekkoActorTestkitTyped = "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVer % Test

  // Logging
  val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.5.16"
  val logbackCore    = "ch.qos.logback" % "logback-core" % "1.5.16"

  // Browser automation
  val PlaywrightVer = "1.52.0"
  val playwright = "com.microsoft.playwright" % "playwright" % PlaywrightVer

  // Diff
  val diffUtils = "io.github.java-diff-utils" % "java-diff-utils" % "4.12"

  // Testing
  val munit = "org.scalameta" %% "munit" % MunitVer % Test
  val munitCatsEffect = "org.typelevel" %% "munit-cats-effect" % MunitCEVer % Test
}
