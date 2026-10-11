package nebflow.cli

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.circe.Json
import io.circe.parser.*
import munit.FunSuite
import nebflow.shared.PathUtil

import java.net.{InetSocketAddress, ServerSocket}
import java.util.concurrent.Executors

/**
 * `nebflow status` meta fields + exit codes (phase 2 daemon plan §5,
 * StatusCommandSpec). Fixtures: a temp data root with a synthetic .pid +
 * .pid.meta (the pid names the LIVE test JVM), and loopback HttpServer
 * stubs on ephemeral ports for the ours/foreign branches — never :8080.
 */
class StatusCommandSpec extends FunSuite:

  private def freePort(): Int =
    val ss = new ServerSocket()
    ss.bind(new InetSocketAddress("127.0.0.1", 0))
    val p = ss.getLocalPort
    ss.close()
    p

  private def withHttpServer(body: String)(f: Int => Unit): Unit =
    val port = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/api/health",
      (ex: com.sun.net.httpserver.HttpExchange) =>
        val bytes = body.getBytes("UTF-8")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()
    try f(port)
    finally server.stop(0)

  private def withTempRoot(f: => Unit): Unit =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "status-spec-")
    try
      PathUtil.setDataRoot(tmp)
      f
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  /** Point GatewayClient.readPort at `port` for the duration of f. */
  private def withPort(port: Int)(f: => Unit): Unit =
    GatewayClient.setPort(port)
    try f
    finally GatewayClient.resetPort()

  private def runStatus(json: Boolean): CliResult =
    val ctx = CliContext(
      args = Map.empty,
      positionalArgs = Nil,
      json = json,
      quiet = false,
      client = None,
      configDir = PathUtil.dataRoot
    )
    StatusCommand.subcommands.head.run(ctx).unsafeRunSync()

  private def asExit(r: CliResult): (Int, String) = r match
    case CliResult.Exit(code, output) => (code, output)
    case other                        => fail(s"expected CliResult.Exit, got $other")

  test("running instance with launcher meta: full JSON face + exit 0") {
    withTempRoot {
      val pid = java.lang.ProcessHandle.current().pid()
      ProcessManager.writePid(pid)
      PidMeta.writeFreshFor(PidMeta.StartedByLauncher, PidMeta.ModeDaemon, pid)
      val port = freePort()
      withPort(port) {
        val (code, out) = asExit(runStatus(json = true))
        assertEquals(code, 0)
        val j = parse(out).fold(e => fail(s"bad json: $e"), identity)
        assertEquals(j.hcursor.get[Boolean]("running"), Right(true))
        assertEquals(j.hcursor.get[Long]("pid"), Right(pid))
        assertEquals(j.hcursor.get[Int]("port"), Right(port))
        assert(j.hcursor.get[String]("version").isRight)
        assertEquals(j.hcursor.get[Boolean]("daemon"), Right(true))
        assertEquals(j.hcursor.get[String]("startedBy"), Right("launcher"))
        assertEquals(j.hcursor.get[String]("mode"), Right("daemon"))
      }
    }
  }

  test("running instance with sbt meta: daemon=false; text face shows ownership") {
    withTempRoot {
      val pid = java.lang.ProcessHandle.current().pid()
      ProcessManager.writePid(pid)
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt","mode":"foreground"}""")
      withPort(freePort()) {
        val (code, jsonOut) = asExit(runStatus(json = true))
        assertEquals(code, 0)
        val j = parse(jsonOut).toOption.get
        assertEquals(j.hcursor.get[Boolean]("daemon"), Right(false))
        assertEquals(j.hcursor.get[String]("startedBy"), Right("sbt"))

        val (textCode, textOut) = asExit(runStatus(json = false))
        assertEquals(textCode, 0)
        assert(textOut.contains("✓ Gateway running"), textOut)
        assert(textOut.contains("started-by: sbt (foreground)"), textOut)
        assert(!textOut.contains("[daemon]"), textOut)
      }
    }
  }

  test("daemon field is null when no meta exists") {
    withTempRoot {
      ProcessManager.writePid(java.lang.ProcessHandle.current().pid())
      withPort(freePort()) {
        val (code, out) = asExit(runStatus(json = true))
        assertEquals(code, 0)
        val j = parse(out).toOption.get
        assertEquals(j.hcursor.downField("daemon").focus, Some(Json.Null), "daemon must be null without meta")
        assertEquals(j.hcursor.downField("startedBy").focus, Some(Json.Null))
        assertEquals(j.hcursor.downField("mode").focus, Some(Json.Null))
      }
    }
  }

  test("not running on a free port: exit 3") {
    withTempRoot {
      withPort(freePort()) {
        val (code, out) = asExit(runStatus(json = true))
        assertEquals(code, 3)
        val j = parse(out).toOption.get
        assertEquals(j.hcursor.get[Boolean]("running"), Right(false))
        val (textCode, textOut) = asExit(runStatus(json = false))
        assertEquals(textCode, 3)
        assert(textOut.contains("✗ Gateway not running"), textOut)
      }
    }
  }

  test("foreign occupant on the port: exit 4 (abnormal)") {
    withTempRoot {
      withHttpServer("""{"status":"fine","service":"something-else"}""") { port =>
        withPort(port) {
          val (code, out) = asExit(runStatus(json = true))
          assertEquals(code, 4)
          val (textCode, textOut) = asExit(runStatus(json = false))
          assertEquals(textCode, 4)
          assert(textOut.contains("in use by another program"), textOut)
        }
      }
    }
  }

  test("our health body on the port without a pid file: running, exit 0, pid null") {
    withTempRoot {
      withHttpServer("""{"product":"nebflow","status":"ok","version":"9.9.9"}""") { port =>
        withPort(port) {
          val (code, out) = asExit(runStatus(json = true))
          assertEquals(code, 0)
          val j = parse(out).toOption.get
          assertEquals(j.hcursor.get[Boolean]("running"), Right(true))
          assertEquals(j.hcursor.get[Long]("pid").toOption, None)
        }
      }
    }
  }

end StatusCommandSpec
