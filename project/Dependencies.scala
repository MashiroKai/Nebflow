import sbt._

object Dependencies {
  // Versions
  val CatsEffectVer = "3.6.1"
  val Fs2Ver = "3.11.0"
  val CirceVer = "0.14.12"
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

  // Process / File
  val osLib = "com.lihaoyi" %% "os-lib" % OsLibVer

  // Logging (silent backend to avoid warnings)
  val sl4jNop = "org.slf4j" % "slf4j-nop" % "2.0.16"

  // Testing
  val munit = "org.scalameta" %% "munit" % MunitVer % Test
  val munitCatsEffect = "org.typelevel" %% "munit-cats-effect" % MunitCEVer % Test
}
