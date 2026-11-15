package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser
import munit.CatsEffectSuite

import java.nio.file.Paths

/** Process-reopen proof: a second JVM never auto-replays protected work. */
class AgentRecoveryProcessSpec extends CatsEffectSuite:

  private final case class FixtureResult(
    bootCalls: Int,
    afterResumeCalls: Int,
    outcome: String,
    status: String,
    attemptId: Option[String]
  )

  private def subprocessClasspath: String =
    val fromProperty = Option(System.getProperty("java.class.path")).filter(_.nonEmpty).toList
    val loaderUrls = List.newBuilder[String]
    var loader = Thread.currentThread().getContextClassLoader
    while loader != null do
      loader match
        case urlLoader: java.net.URLClassLoader =>
          loaderUrls ++= urlLoader.getURLs.toList.filter(_.getProtocol == "file").map(url =>
            Paths.get(url.toURI).toString)
        case _ => ()
      loader = loader.getParent
    val codeSources = List(AgentRecoveryProcessFixture.getClass, classOf[cats.effect.IO[?]],
      classOf[io.circe.Json], classOf[os.Path]).flatMap { clazz =>
      Option(clazz.getProtectionDomain).flatMap(domain => Option(domain.getCodeSource))
        .map(source => Paths.get(source.getLocation.toURI).toString)
    }
    (loaderUrls.result() ::: codeSources ::: fromProperty).distinct.mkString(java.io.File.pathSeparator)

  private def runFixture(root: os.Path, mode: String): IO[Option[FixtureResult]] =
    IO.blocking {
      val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString
      val log = root / s"fixture-$mode.log"
      val process = new ProcessBuilder(
        javaBin, "-cp", subprocessClasspath,
        s"-Duser.home=${root}",
        "nebflow.agent.AgentRecoveryProcessFixture",
        root.toString, mode
      ).redirectErrorStream(true).redirectOutput(log.toIO).start()
      val finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
      val output = if os.exists(log) then os.read(log) else ""
      if !finished then
        process.destroy()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        throw new IllegalStateException(s"fixture '$mode' timed out: $output")
      if process.exitValue() != 0 then
        throw new IllegalStateException(s"fixture '$mode' exited ${process.exitValue()}: $output")
      if !os.exists(root / "result.json") then None
      else
        val raw = os.read(root / "result.json")
        Some(parser.parse(raw).toOption.getOrElse(throw new IllegalStateException(s"invalid result: $raw")))
    }.map(_.map { json =>
      FixtureResult(
        json.hcursor.get[Int]("bootCalls").toOption.getOrElse(-1),
        json.hcursor.get[Int]("afterResumeCalls").toOption.getOrElse(-1),
        json.hcursor.get[String]("outcome").toOption.getOrElse(""),
        json.hcursor.get[String]("status").toOption.getOrElse(""),
        json.hcursor.get[String]("attemptId").toOption
      )
    })

  test("separate process reopen makes zero calls until explicit same run resume") {
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-process-clean-"))
      _ <- (IO.blocking(os.makeDir.all(root / "runs")) *>
        runFixture(root, "clean").flatMap(_ => runFixture(root, "recover")).map { maybeResult =>
          val result = maybeResult.getOrElse(fail("recover fixture produced no result"))
          assertEquals(result.bootCalls, 0, "boot discovery must not call the provider")
          assert(result.outcome.startsWith("resumed:"), s"expected explicit resume, got ${result.outcome}")
          assert(result.afterResumeCalls >= 1, "resume must continue from the next legal boundary")
          assertEquals(result.status, "finished")
          assert(result.attemptId.exists(_.contains("resume-request")), result.attemptId.toString)
        }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()
  }

  test("separate process unknown Started operation remains review required without replay") {
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-process-started-"))
      _ <- (IO.blocking(os.makeDir.all(root / "runs")) *>
        runFixture(root, "started").flatMap(_ => runFixture(root, "recover")).map { maybeResult =>
          val result = maybeResult.getOrElse(fail("recover fixture produced no result"))
          assertEquals(result.bootCalls, 0)
          assertEquals(result.afterResumeCalls, 0, "unknown Started must not auto-replay")
          assert(result.outcome.contains("unresolved-operation-review-required"), result.outcome)
          assert(result.status != "finished")
        }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()
  }
