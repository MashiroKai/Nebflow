package nebflow.llm

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** WebSearch P2 (2026-08-25, docs/Nebflow/20260825_websearch-fetch-optimization.md
  * §Phase 2): 搜索与模型额度解耦 — Tier 2a standalone search API. The core
  * acceptance P2-1 (standalone search works while the model pipeline is dead,
  * zero LlmHandle sends) is exercised against a local mock HTTP server; the
  * real-endpoint variant is an isolated-instance smoke (P2-2). */
class StandaloneSearchSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  // ── scripted LlmHandle (same pattern as SearchProviderResolverSpec) ───

  private class ScriptedLlm(scripts: List[LlmResponse]) extends LlmHandle[IO]:
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      requests.modify { list => (req :: list, list.size) }.flatMap { idx =>
        scripts.lift(idx) match
          case Some(resp) => IO.pure(resp)
          case None       => IO.raiseError(new RuntimeException(s"unexpected LLM send #$idx"))
      }
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("sendStream not expected here"))

  private def meta(providerId: String) =
    LlmMeta(sessionId = "s", agentId = "a", providerId = providerId, model = "m", durationMs = 1)

  private val zhipuModel = Some(AgentModelConfig(Some("zhipu/glm-5.3"), Nil))
  private val deepseekModel = Some(AgentModelConfig(Some("deepseek/v4"), Nil))

  private val zhipuInfo = Json.arr(
    Json.obj("title" -> "t".asJson, "url" -> "https://example.com/z".asJson, "content" -> "s".asJson)
  )

  private val mockZhipuResult =
    """{"search_result":[{"title":"Scala 3.5 release notes","content":"What's new in 3.5","link":"https://example.com/scala35"},
       |{"title":"No-URL entry","content":"must be dropped"}]}""".stripMargin

  /** Local mock HTTP server for the standalone search endpoint. Returns
    * (port, server) — the caller must stop the server. The handler captures
    * request bodies into `reqBodies`. Cached thread pool (JDK HttpServer's
    * default single-thread executor would serialize concurrent requests). */
  private def mockServer(
      statusCode: Int,
      responseBody: String,
      reqBodies: java.util.List[String]
  ): IO[(Int, com.sun.net.httpserver.HttpServer)] =
    IO.blocking {
      val server = com.sun.net.httpserver.HttpServer
        .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/",
        (ex: com.sun.net.httpserver.HttpExchange) => {
          val body = new String(ex.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          reqBodies.add(body)
          val out = responseBody.getBytes("UTF-8")
          ex.getResponseHeaders.add("Content-Type", "application/json")
          ex.sendResponseHeaders(statusCode, out.length)
          ex.getResponseBody.write(out)
          ex.close()
        }
      )
      server.start()
      (server.getAddress.getPort, server)
    }

  // ── P2-4: config wiring ─────────────────────────────────────────────

  test("P2-4: standaloneConfigFrom maps the nebflow.json search block (zhipu, overrides honored)") {
    val cfg = SearchProviderResolver.standaloneConfigFrom(
      SearchConfig("zhipu", "sk-test", Some("search_pro"), None, Some("http://127.0.0.1:9999/ws"), Some(true))
    )
    assertEquals(
      cfg,
      Some(StandaloneSearchConfig("zhipu", "sk-test", "http://127.0.0.1:9999/ws", "search_pro"))
    )
  }

  test("P2-4: standaloneConfigFrom defaults (endpoint + engine) when only provider/apiKey given") {
    val cfg = SearchProviderResolver.standaloneConfigFrom(SearchConfig("zhipu", "sk-test"))
    assertEquals(
      cfg,
      Some(
        StandaloneSearchConfig(
          "zhipu",
          "sk-test",
          StandaloneSearchConfig.DefaultZhipuBaseUrl,
          StandaloneSearchConfig.DefaultEngine
        )
      )
    )
  }

  test("P2-4: standaloneConfigFrom degrades to None — disabled / empty key / unknown provider") {
    assertEquals(SearchProviderResolver.standaloneConfigFrom(SearchConfig("zhipu", "k", enabled = Some(false))), None)
    assertEquals(SearchProviderResolver.standaloneConfigFrom(SearchConfig("zhipu", "  ")), None)
    assertEquals(SearchProviderResolver.standaloneConfigFrom(SearchConfig("bocha", "k")), None)
  }

  test("P2-4: resolveStandalone route table") {
    assertEquals(
      SearchProviderResolver.resolveStandalone("zhipu"),
      Some(SearchRoute.StandaloneApi("zhipu", StandaloneSearchKind.ZhipuWebSearch))
    )
    assertEquals(SearchProviderResolver.resolveStandalone("bocha"), None)
    assertEquals(SearchProviderResolver.resolveStandalone(""), None)
  }

  test("P2-4: standaloneRequestBody carries search_query/search_engine/count (wire contract)") {
    val body = SearchProviderResolver.standaloneRequestBody("scala 3.5", "search_std").noSpaces
    assert(body.contains("\"search_query\":\"scala 3.5\""), body)
    assert(body.contains("\"search_engine\":\"search_std\""), body)
    assert(body.contains("\"count\":8"), body)
  }

  // ── P2-5: error classification ──────────────────────────────────────

  test("P2-5: classifyStandaloneError — auth / quota / server / timeout, body never swallowed") {
    assertEquals(
      SearchProviderResolver.classifyStandaloneError(401, "invalid key", timeout = false),
      "standalone search API auth failed (HTTP 401)"
    )
    val quota = SearchProviderResolver.classifyStandaloneError(429, """{"error":{"code":"1113","message":"余额不足"}}""", timeout = false)
    assert(quota.contains("quota/rate limited"), quota)
    assert(quota.contains("余额不足"), "provider message must survive classification")
    assert(SearchProviderResolver.classifyStandaloneError(500, "boom", timeout = false).contains("server error"))
    assertEquals(SearchProviderResolver.classifyStandaloneError(0, "", timeout = true), "standalone search API timeout")
  }

  test("P2-5: parseStandaloneResults — zhipu envelope, URL-less entries dropped") {
    val entries = SearchProviderResolver.parseStandaloneResults(mockZhipuResult)
    assert(entries.isDefined)
    assertEquals(entries.get.size, 1)
    assertEquals(entries.get.head._2, "https://example.com/scala35")
    assertEquals(entries.get.head._1, "Scala 3.5 release notes")
  }

  // ── P2-1 (core): decoupling — search works with model pipeline dead ──

  test("P2-1: standalone search returns results while the model pipeline is dead — zero LlmHandle sends") {
    val reqBodies = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val llm = new ScriptedLlm(List(LlmResponse("never", Nil, None, meta("zhipu"), Some(zhipuInfo))))
    mockServer(200, mockZhipuResult, reqBodies).bracket { case (port, _) =>
      val cfg = StandaloneSearchConfig("zhipu", "sk-test", s"http://127.0.0.1:$port/search", "search_std")
      SearchProviderResolver
        .executeProviderSearchFor("scala 3.5", Some(llm), "s", "a", zhipuModel, standalone = Some(cfg))
        .flatMap { result =>
          llm.requests.get.map { reqs =>
            assert(result.isDefined, "standalone search must succeed while the model path is untouched")
            assert(result.get.contains("Search source: search-api:zhipu"), result.get)
            assert(result.get.contains("https://example.com/scala35"), result.get)
            assert(!result.get.contains("No-URL entry"), "URL-less entries must be dropped")
            assertEquals(reqs.size, 0, "ZERO LlmHandle.send — search must not depend on model quota/health")
          }
        }
    } { case (_, server) => IO.blocking(server.stop(0)) }.flatMap { _ =>
      // P2-4 wire contract asserted at the HTTP layer (real request reached the mock)
      IO {
        assertEquals(reqBodies.size(), 1)
        assert(reqBodies.get(0).contains("search_query"), reqBodies.get(0))
        assert(reqBodies.get(0).contains("search_engine"), reqBodies.get(0))
        assert(reqBodies.get(0).contains("count"), reqBodies.get(0))
        assert(reqBodies.get(0).contains("Bearer sk-test") == false, "auth goes in the header, not the body")
      }
    }
  }

  test("P2-1: standalone works even with llm=None (no model handle at all)") {
    val reqBodies = new java.util.concurrent.CopyOnWriteArrayList[String]()
    mockServer(200, mockZhipuResult, reqBodies).bracket { case (port, _) =>
      val cfg = StandaloneSearchConfig("zhipu", "sk-test", s"http://127.0.0.1:$port/search", "search_std")
      SearchProviderResolver
        .executeProviderSearchFor("q", None, "s", "a", deepseekModel, standalone = Some(cfg))
        .map { result =>
          assert(result.isDefined)
          assert(result.get.contains("search-api:zhipu"), result.get)
        }
    } { case (_, server) => IO.blocking(server.stop(0)) }
  }

  test("P2-1: no standalone config → graceful degrade to Tier 2b (model builtin), llm called once") {
    val llm = new ScriptedLlm(List(LlmResponse("ans", Nil, None, meta("zhipu"), Some(zhipuInfo))))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", zhipuModel, standalone = None)
      .flatMap { result =>
        assert(result.isDefined, "Tier 2b must still work when no search block is configured")
        assert(result.get.contains("provider:zhipu"), result.get)
        llm.requests.get.map(reqs => assertEquals(reqs.size, 1))
      }
  }

  test("P2-5: standalone API failure (429) → degrades to 2b with diagnostic, health records Down") {
    val reqBodies = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val quotaBody = """{"error":{"code":"1113","message":"余额不足或无可用资源包,请充值。"}}"""
    val llm = new ScriptedLlm(List(LlmResponse("ans", Nil, None, meta("zhipu"), Some(zhipuInfo))))
    val monitor = ProviderHealthMonitor(null)
    // Regression lock (2026-08-25, E2E-caught): the degrade WARN must actually
    // fire — a bare `logger.warn(...)` in the flatMap body is built-not-run
    // (dead-logging), silently swallowing the 429 diagnostic.
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.llm.search").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    mockServer(429, quotaBody, reqBodies).bracket { case (port, _) =>
      val cfg = StandaloneSearchConfig("zhipu", "sk-test", s"http://127.0.0.1:$port/search", "search_std")
      for
        result <- SearchProviderResolver.executeProviderSearchFor(
          "q", Some(llm), "s", "a", zhipuModel, standalone = Some(cfg), health = Some(monitor)
        )
        reqs <- llm.requests.get
        health <- monitor.getSearchHealth
      yield
        // 2a failed with a diagnostic → fell through to 2b (provider builtin still worked)
        assert(result.isDefined, "2a failure must degrade, not fail the tool")
        assert(result.get.contains("provider:zhipu"), result.get)
        assertEquals(reqs.size, 1, "degraded to one 2b sub-request")
        assert(
          health match {
            case SearchApiHealth.Down(reason, _) => reason.contains("quota/rate limited")
            case other => false
          },
          s"health must record the classified 2a failure, got: $health"
        )
    } { case (_, server) => IO.blocking(server.stop(0)) }.flatMap { _ =>
      IO {
        import scala.jdk.CollectionConverters.*
        val fired = appender.list.asScala.toList
          .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
          .map(_.getFormattedMessage)
        lbLogger.detachAppender(appender)
        assert(fired.nonEmpty, "degrade WARN must be emitted (dead-logging regression lock)")
        assert(fired.head.contains("quota/rate limited"), s"WARN must carry the classified reason: ${fired.headOption}")
        assert(fired.head.contains("Tier 2a standalone search failed"), s"WARN must name the failure: ${fired.headOption}")
      }
    }
  }

  test("P2-5: standalone API failure AND 2b incapable (deepseek chain) → None, not a crash") {
    val reqBodies = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val llm = new ScriptedLlm(List(LlmResponse("x", Nil, None, meta("deepseek"))))
    mockServer(500, "boom", reqBodies).bracket { case (port, _) =>
      val cfg = StandaloneSearchConfig("zhipu", "sk-test", s"http://127.0.0.1:$port/search", "search_std")
      SearchProviderResolver
        .executeProviderSearchFor("q", Some(llm), "s", "a", deepseekModel, standalone = Some(cfg))
        .map(result => assertEquals(result, None, "deepseek chain has no builtin search — Tier 3 (caller fallback)"))
    } { case (_, server) => IO.blocking(server.stop(0)) }
  }

  // ── P2-6: health channel ────────────────────────────────────────────

  test("P2-6: fresh monitor search health = Unconfigured; success records Up") {
    val monitor = ProviderHealthMonitor(null)
    for
      before <- monitor.getSearchHealth
      _ <- monitor.recordSearchSuccess()
      after <- monitor.getSearchHealth
    yield
      assertEquals(before, SearchApiHealth.Unconfigured)
      assertEquals(after, SearchApiHealth.Up)
  }

  test("P2-6: recordSearchFailure stores the classified reason + timestamp") {
    val monitor = ProviderHealthMonitor(null)
    for
      _ <- monitor.recordSearchFailure("standalone search API quota/rate limited (HTTP 429): 余额不足")
      health <- monitor.getSearchHealth
    yield
      health match
        case SearchApiHealth.Down(reason, since) =>
          assert(reason.contains("quota/rate limited"), reason)
          assert(since > 0)
        case other => fail(s"expected Down, got: $other")
  }

end StandaloneSearchSpec
