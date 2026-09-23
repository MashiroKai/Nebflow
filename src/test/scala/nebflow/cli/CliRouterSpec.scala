package nebflow.cli

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, ServerSocket}
import java.util.concurrent.Executors

/**
 * CLI router / gateway client contract spec (cliaudit-impl).
 *
 * Pins the behaviour this batch changed, so each fix has a regression net:
 *  - argument parsing — long / short / positional / unknown flag (A12)
 *  - required-parameter validation incl. positional slots (A11)
 *  - dispatch — unknown subcommand, bare multi-subcommand command (A12/V16)
 *  - help rendering — top-level lines and the `Parameters:` section (T1/T3/
 *    T4/T5/T6/T13, A7/A9)
 *  - gateway error surfacing — non-2xx is a failure (A1/V20), 2xx is not
 *  - offline classification (A14/A18), config masking (D6), chat frames (A16)
 *
 * Hermetic: every HTTP endpoint is a loopback `HttpServer` on an ephemeral
 * port that is always stopped, and no test reads the live data root (no
 * `~/.nebflow/auth.json`, no :8080).
 */
class CliRouterSpec extends FunSuite:

  // ===== helpers =====

  private val params = List(
    CliParam("session", Some('s'), "Session ID"),
    CliParam("limit", Some('l'), "Max messages"),
    CliParam("continue", None, "Continue recent session", isFlag = true)
  )

  private def parse(args: String*): Either[String, (Map[String, String], List[String])] =
    CliRouter.parseArgs(args.toList, params)

  private def namedOf(args: String*): Map[String, String] =
    parse(args*).fold(e => fail(s"unexpected parse error: $e"), _._1)

  private def positionalOf(args: String*): List[String] =
    parse(args*).fold(e => fail(s"unexpected parse error: $e"), _._2)

  private def freePort(): Int =
    val ss = new ServerSocket()
    ss.bind(new InetSocketAddress("127.0.0.1", 0))
    val p = ss.getLocalPort
    ss.close()
    p

  /**
   * Loopback mock on an ephemeral port, stopped in a `finally` (a spec-owned
   * process must not outlive its test).
   */
  private def withHttpServer(status: Int, body: String)(f: Int => Unit): Unit =
    val port = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/",
      (ex: com.sun.net.httpserver.HttpExchange) =>
        ex.getRequestBody.readAllBytes()
        val bytes = body.getBytes("UTF-8")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()
    try f(port)
    finally server.stop(0)

  end withHttpServer

  private def json(s: String): Json =
    io.circe.parser.parse(s).fold(e => fail(s"bad fixture: $s ($e)"), identity)

  /**
   * Capture stdout of a router run.
   *
   * `scala.Console.withOut` is NOT enough: the router prints through
   * cats-effect `IO.println` (= `Console[IO]`), which writes to the JVM's
   * `System.out` when the effect runs, bypassing `scala.Console`. Redirecting
   * `System.out` is what actually catches those lines (and `System.err` too, so
   * a stray stack trace cannot leak between assertions).
   */
  private def capture(f: => Unit): String =
    val buf = new ByteArrayOutputStream()
    val ps = new java.io.PrintStream(buf, true, "UTF-8")
    val (oldOut, oldErr) = (System.out, System.err)
    try
      System.setOut(ps)
      System.setErr(ps)
      f
    finally
      ps.flush()
      System.setOut(oldOut)
      System.setErr(oldErr)
    buf.toString("UTF-8")

  private def runCaptured(args: String*): (String, Int) =
    var code = 0
    val out = capture {
      code = CliRouter.run(args.toList).unsafeRunSync().code
    }
    (out, code)

  // ===== 1. parseArgs — the four forms =====

  test("parseArgs: long form") {
    assertEquals(namedOf("--session", "s1"), Map("session" -> "s1"))
  }

  test("parseArgs: short form") {
    assertEquals(namedOf("-s", "s1"), Map("session" -> "s1"))
  }

  test("parseArgs: positional form") {
    assertEquals(positionalOf("s1", "42"), List("s1", "42"))
  }

  test("parseArgs: unknown long flag is an error, not a named param (A12)") {
    assertEquals(parse("--frobnicate", "1"), Left("Unknown flag: --frobnicate"))
  }

  test("parseArgs: unknown short flag is an error (A12)") {
    assertEquals(parse("-Z"), Left("Unknown flag: -Z"))
  }

  test("parseArgs: a numeric short token stays positional (negative values survive)") {
    assertEquals(positionalOf("-1"), List("-1"))
  }

  test("parseArgs: a value-less flag becomes \"true\" (long and short)") {
    assertEquals(namedOf("--continue"), Map("continue" -> "true"))
    assertEquals(namedOf("-l"), Map("limit" -> "true"))
  }

  test("parseArgs: mixed forms keep their sides") {
    assertEquals(namedOf("s1", "--limit", "5"), Map("limit" -> "5"))
    assertEquals(positionalOf("s1", "--limit", "5"), List("s1"))
  }

  // ===== 2. required params + positional slots (A11) =====

  test("A11: positional slots fill required params in declaration order") {
    val set = ConfigCommand.subcommands.find(_.name == "set").get
    assertEquals(CliRouter.missingRequired(set, Map.empty, List("k", "v")), Nil)
    assertEquals(CliRouter.missingRequired(set, Map.empty, List("k")), List("value"))
    assertEquals(CliRouter.missingRequired(set, Map.empty, Nil), List("key", "value"))
  }

  test("A11: ask \"q\" reports the missing required session") {
    val ask = AskCommand.subcommands.head
    assertEquals(CliRouter.missingRequired(ask, Map.empty, List("q")), List("session"))
    assertEquals(CliRouter.missingRequired(ask, Map("session" -> "s1"), List("q")), Nil)
  }

  test("A11: a single required param is filled by one positional (interrupt SESSION)") {
    val interrupt = InterruptCommand.subcommands.head
    assertEquals(CliRouter.missingRequired(interrupt, Map.empty, List("s1")), Nil)
    assertEquals(CliRouter.missingRequired(interrupt, Map.empty, Nil), List("session"))
  }

  test("A11: a named value satisfies a required param declared after a positional-filling one") {
    val rename = SessionCommand.subcommands.find(_.name == "rename").get
    assertEquals(CliRouter.missingRequired(rename, Map("name" -> "z"), List("s1")), Nil)
    assertEquals(CliRouter.missingRequired(rename, Map.empty, List("s1")), List("name"))
  }

  test("A11: short-flag names count as named") {
    val set = ConfigCommand.subcommands.find(_.name == "set").get
    assert(CliRouter.missingRequired(set, Map("key" -> "k", "value" -> "v"), Nil).isEmpty)
  }

  // ===== 3. dispatch =====

  test("A12: an unknown subcommand is an error, not a fallback to the first subcommand") {
    val (out, code) = runCaptured("session", "frobnicate")
    assertEquals(code, 1)
    assert(out.contains("Unknown subcommand: frobnicate"), out)
  }

  test("V16: a bare multi-subcommand command explains the missing subcommand") {
    val (out, code) = runCaptured("chat")
    assertEquals(code, 1)
    assert(out.contains("'chat' needs a subcommand"), out)
    assert(out.contains("send"), out)
    assert(!out.contains("Subcommands:"), out)
  }

  test("A7: `--help` and `-h` print the top-level help and exit 0") {
    val (outLong, codeLong) = runCaptured("--help")
    val (outShort, codeShort) = runCaptured("-h")
    assertEquals(codeLong, 0)
    assertEquals(codeShort, 0)
    assert(outLong.contains("Usage: nebflow <command>"), outLong)
    assert(outShort.contains("Usage: nebflow <command>"), outShort)
  }

  // ===== 4. help rendering (T1/T3/T4/T5/T6/T13, A9/T10) =====

  test("A6/V6: `help <command>` reaches the command help (the argument list the entry point forwards)") {
    val (out, code) = runCaptured("help", "session")
    assertEquals(code, 0)
    assert(out.contains("Parameters:"), out)
    assert(out.contains("--agent, -a"), out)
  }

  test("A8/V8: `help --json` is JSON while plain `help` stays text (the flag must survive to the router)") {
    val (textOut, textCode) = runCaptured("help")
    val (jsonOut, jsonCode) = runCaptured("help", "--json")
    assertEquals(textCode, 0)
    assertEquals(jsonCode, 0)
    assert(!textOut.contains("\"commands\""), textOut)
    assert(
      io.circe.parser.parse(jsonOut).exists(_.hcursor.downField("commands").succeeded),
      jsonOut
    )
  }

  test("T1/T3/T4/T13: the top-level help carries every new line") {
    val (out, code) = runCaptured("help")
    assertEquals(code, 0)
    assert(out.contains("Start the Gateway (same as 'nebflow start')"), out)
    assert(out.contains("Starting / stopping:"), out)
    assert(out.contains("Global flags: --json, --quiet, --home <dir>, --port <n>, --no-browser"), out)
    assert(out.contains("Exit codes: 0 ok · 1 error · 2 unreachable/timeout"), out)
    assert(out.contains("(legacy) same as 'nebflow start'"), out)
  }

  test("T5/T6: a parameter line renders the flag pair plus annotations") {
    val required = CliRouter.parameterLine(CliParam("session", Some('s'), "Session ID", required = true))
    assert(required.contains("--session, -s"), required)
    assert(required.contains("Session ID (required)"), required)
    val defaulted = CliRouter.parameterLine(CliParam("timeout", Some('t'), "Turn timeout", default = Some("1800")))
    assert(defaulted.contains("--timeout, -t"), defaulted)
    assert(defaulted.contains("(default: 1800)"), defaulted)
  }

  test("A9/V9: `session --help` renders Parameters and the declared flags") {
    val (out, code) = runCaptured("session", "--help")
    assertEquals(code, 0)
    assert(out.contains("Parameters:"), out)
    assert(out.contains("--agent, -a"), out)
    assert(out.contains("--session-id"), out)
  }

  test("A9: `--help` after a subcommand still shows the command help") {
    val (out, code) = runCaptured("session", "list", "--help")
    assertEquals(code, 0)
    assert(out.contains("Parameters:"), out)
    assert(out.contains("--agent, -a"), out)
  }

  test("T10/A14: `skill audit` is marked as needing no gateway") {
    val (out, code) = runCaptured("skill", "--help")
    assertEquals(code, 0)
    assert(out.contains("Parameters:"), out)
    assert(out.contains("skill audit"), out)
    assert(out.contains("(no gateway needed)"), out)
  }

  test("T7: the false 'without arguments to show help' claim is gone") {
    val out = capture {
      HelpCommand.subcommands.head.run(CliContext.offline()).unsafeRunSync() match
        case CliResult.Text(lines) => lines.foreach(println)
        case other => fail(s"unexpected help result: $other")
    }
    assert(!out.contains("without arguments to show help"), out)
    assert(out.contains("--help"), out)
  }

  test("A14/A18: offline classification is subcommand-aware") {
    assert(CliRouter.isOffline("skill", "audit"))
    assert(!CliRouter.isOffline("skill", "list"))
    assert(CliRouter.isOffline("skill", "run") == false)
    assert(CliRouter.isOffline("help", "show"))
    assert(CliRouter.isOffline("doctor", "check"))
    assert(!CliRouter.isOffline("session", "list"))
  }

  // ===== 5. gateway error surfacing (A1/V20) =====

  test("A1: a 403 response fails with the T8 wording (no silent empty payload)") {
    withHttpServer(403, """{"error":"Unauthorized"}""") { port =>
      val client = new GatewayClient(s"http://127.0.0.1:$port", "test-token")
      val err = intercept[RuntimeException](client.get("/api/anything").unsafeRunSync())
      assertEquals(err.getMessage, "Gateway request failed (HTTP 403): Unauthorized")
    }
  }

  test("A1: a 200 response is not judged as a failure") {
    withHttpServer(200, """{"ok":true,"sessions":[]}""") { port =>
      val client = new GatewayClient(s"http://127.0.0.1:$port", "test-token")
      assertEquals(client.get("/api/anything").unsafeRunSync(), json("""{"ok":true,"sessions":[]}"""))
      assertEquals(client.post("/api/command", Json.obj()).unsafeRunSync(), json("""{"ok":true,"sessions":[]}"""))
    }
  }

  test("A1: requestError wording uses the body's error field, then a raw fallback") {
    assertEquals(
      GatewayClient.requestError(403, """{"error":"Unauthorized"}"""),
      "Gateway request failed (HTTP 403): Unauthorized"
    )
    assertEquals(
      GatewayClient.requestError(500, """{"message":"boom"}"""),
      "Gateway request failed (HTTP 500): boom"
    )
    assertEquals(GatewayClient.requestError(500, "raw failure"), "Gateway request failed (HTTP 500): raw failure")
  }

  // ===== 6. port resolution (A4/C1) =====

  test("A4/C1: the CLI-side port override wins over env and the default") {
    try
      GatewayClient.setPort(18099)
      assertEquals(GatewayClient.readPort.unsafeRunSync(), 18099)
    finally GatewayClient.resetPort()
  }

  test("A4/C1: without an override, env wins, then the 8080 default") {
    GatewayClient.resetPort()
    val expected = nebflow.core.Branding.env("GATEWAY_PORT").flatMap(_.toIntOption).getOrElse(8080)
    assertEquals(GatewayClient.readPort.unsafeRunSync(), expected)
  }

  // ===== 7. command bodies reached through an injected client (A3) =====

  private def ctxWith(client: GatewayClient, named: Map[String, String], positional: List[String]): CliContext =
    CliContext(named, positional, json = false, quiet = false, client = Some(client), configDir = os.pwd)

  test("A3: `interrupt SESSION` (positional) reaches the wire") {
    withHttpServer(200, """{"status":"ok"}""") { port =>
      val client = new GatewayClient(s"http://127.0.0.1:$port", "test-token")
      val sub = InterruptCommand.subcommands.head
      val res = sub.run(ctxWith(client, Map.empty, List("s1"))).unsafeRunSync()
      assertEquals(res, CliResult.Success)
    }
  }

  test("A3: `config set --key K --value V` (named) is accepted") {
    withHttpServer(200, """{"type":"configUpdated","ok":true}""") { port =>
      val client = new GatewayClient(s"http://127.0.0.1:$port", "test-token")
      val sub = ConfigCommand.subcommands.find(_.name == "set").get
      val res = sub.run(ctxWith(client, Map("key" -> "a.b", "value" -> "false"), Nil)).unsafeRunSync()
      assertEquals(res, CliResult.text("Config updated: a.b = false"))
    }
  }

  // ===== 8. config masking (D6) and mcp config construction (A17) =====

  test("D6: masking hits secret-named keys only") {
    val cfg = json(
      """{"llm":{"providers":{"p":{"apiKey":"sk-secret","baseUrl":"http://x"}}},
        |"authToken":"t","name":"keep"}""".stripMargin
    )
    val red = ConfigCommand.redact(cfg)
    val provider = red.hcursor.downField("llm").downField("providers").downField("p")
    assertEquals(provider.downField("apiKey").as[String].toOption, Some("***"))
    assertEquals(red.hcursor.downField("authToken").as[String].toOption, Some("***"))
    assertEquals(provider.downField("baseUrl").as[String].toOption, Some("http://x"))
    assertEquals(red.hcursor.downField("name").as[String].toOption, Some("keep"))
  }

  test("D6: numeric quota fields are NOT masked (non-secret fields stay as-is)") {
    // `thinkingConfig.budgetTokens` (llm/config.scala:183) is a token BUDGET, not
    // a credential: a bare `contains("token")` test would corrupt it.
    val cfg = json(
      """{"thinkingConfig":{"enabled":true,"budgetTokens":32000},
        |"llm":{"maxTokens":4096,"api_key":"k","password":"p","nested":{"secret":"s"}}}""".stripMargin
    )
    val red = ConfigCommand.redact(cfg)
    assertEquals(red.hcursor.downField("thinkingConfig").downField("budgetTokens").as[Int].toOption, Some(32000))
    assertEquals(red.hcursor.downField("llm").downField("maxTokens").as[Int].toOption, Some(4096))
    assertEquals(red.hcursor.downField("llm").downField("api_key").as[String].toOption, Some("***"))
    assertEquals(red.hcursor.downField("llm").downField("password").as[String].toOption, Some("***"))
    assertEquals(
      red.hcursor.downField("llm").downField("nested").downField("secret").as[String].toOption,
      Some("***")
    )
    // the classifier itself, at its boundaries
    assert(ConfigCommand.isSecretKey("apiKey"))
    assert(ConfigCommand.isSecretKey("auth_token"))
    assert(ConfigCommand.isSecretKey("refreshToken"))
    assert(!ConfigCommand.isSecretKey("budgetTokens"))
    assert(!ConfigCommand.isSecretKey("maxTokens"))
    assert(!ConfigCommand.isSecretKey("enabled"))
  }

  test("A17: mcp config is built through the JSON encoder — a quoted id survives") {
    val entry = Json.obj("command" -> "echo".asJson, "args" -> List("a").asJson)
    val built = ConfigCommand.buildNestedJson(List("mcpServers", "mq\"x"), entry.noSpaces)
    val text = built.noSpaces
    val reparsed = io.circe.parser.parse(text).fold(e => fail(s"invalid JSON emitted: $text ($e)"), identity)
    assertEquals(
      reparsed.hcursor.downField("mcpServers").downField("mq\"x").downField("command").as[String].toOption,
      Some("echo")
    )
  }

  // ===== 9. chat frames (A16) =====

  test("A16: chat uses the real WS vocabulary frames") {
    assertEquals(ChatCommand.sessionFrame(None), json("""{"type":"createSession","name":"New Session"}"""))
    assertEquals(ChatCommand.sessionFrame(Some("s1")), json("""{"type":"switchSession","sessionId":"s1"}"""))
    assertEquals(ChatCommand.sessionFrame(Some("")), json("""{"type":"createSession","name":"New Session"}"""))
  }

  test("A16: the createSession reply's sessionId is consumed") {
    val resp =
      json("""{"type":"sessionList","sessions":[{"id":"new-1","name":"New Session"},{"id":"old","name":"old"}]}""")
    assertEquals(ChatCommand.newSessionId(resp, "New Session"), "new-1")
    assertEquals(ChatCommand.newSessionId(json("""{"status":"ok"}"""), "New Session"), "")
  }

  // ===== 10. logback status silencing (A13) =====

  test("A13: the CLI sets the logback status switch when it is unset") {
    val prev = sys.props.get("logback.statusListenerClass")
    try
      sys.props.remove("logback.statusListenerClass")
      capture(CliRouter.run(Nil).unsafeRunSync())
      assertEquals(
        sys.props.get("logback.statusListenerClass"),
        Some("ch.qos.logback.core.status.NopStatusListener")
      )
    finally
      prev match
        case Some(v) => sys.props("logback.statusListenerClass") = v
        case None => sys.props.remove("logback.statusListenerClass")
  }
end CliRouterSpec
