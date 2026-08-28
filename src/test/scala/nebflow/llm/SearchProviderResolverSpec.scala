package nebflow.llm

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.*

import scala.concurrent.duration.*

/** WebSearch P0 (E2-4): resolver table + echo byte-identity + Tier 2 executor
  * against a scripted LlmHandle — including the anti-hallucination evidence
  * rule (no structured searchInfo and no kimi round-trip ⇒ degrade to None)
  * and the kimi echo round-trip budget. */
class SearchProviderResolverSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  // ── E2-4: resolve() table ────────────────────────────────────────────

  test("resolve: five-provider table (zhipu/kimi/qwen → Tier 2; deepseek/unknown → Tier 3)") {
    assertEquals(SearchProviderResolver.resolve("zhipu"), SearchRoute.ProviderSearch("zhipu", ProviderSearchKind.ZhipuWebSearchTool))
    assertEquals(SearchProviderResolver.resolve("kimi"), SearchRoute.ProviderSearch("kimi", ProviderSearchKind.KimiBuiltinWebSearch))
    assertEquals(SearchProviderResolver.resolve("qwen"), SearchRoute.ProviderSearch("qwen", ProviderSearchKind.QwenEnableSearch))
    assertEquals(SearchProviderResolver.resolve("deepseek"), SearchRoute.BuiltinAggregated)
    assertEquals(SearchProviderResolver.resolve("totally-unknown"), SearchRoute.BuiltinAggregated)
    // The user's 107 gateway (USTC, Anthropic protocol) → Tier 3.
    assertEquals(SearchProviderResolver.resolve("107"), SearchRoute.BuiltinAggregated)
  }

  test("capabilityFor: baseUrl host is the stronger signal (custom provider ids)") {
    assertEquals(
      SearchProviderResolver.capabilityFor("my-glm-proxy", "https://open.bigmodel.cn/api/paas/v4"),
      Some(ProviderSearchKind.ZhipuWebSearchTool)
    )
    assertEquals(
      SearchProviderResolver.capabilityFor("k", "https://api.moonshot.cn/v1"),
      Some(ProviderSearchKind.KimiBuiltinWebSearch)
    )
    assertEquals(
      SearchProviderResolver.capabilityFor("ali", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
      Some(ProviderSearchKind.QwenEnableSearch)
    )
    // Neither id nor baseUrl matches → no capability.
    assertEquals(SearchProviderResolver.capabilityFor("ustc-gw", "https://llm.example.com/v1"), None)
  }

  test("capabilityFor: id family tokens (interception path has no baseUrl)") {
    // OpenAI-compatible companion ids users build next to an anthropic-
    // protocol config — resolved from the model chain head WITHOUT baseUrl.
    assertEquals(
      SearchProviderResolver.capabilityFor("qwen-openai", ""),
      Some(ProviderSearchKind.QwenEnableSearch)
    )
    assertEquals(
      SearchProviderResolver.capabilityFor("zhipu-openai", ""),
      Some(ProviderSearchKind.ZhipuWebSearchTool)
    )
    assertEquals(SearchProviderResolver.capabilityFor("my-kimi-proxy", ""), Some(ProviderSearchKind.KimiBuiltinWebSearch))
    // Token equality, not substring: no false positives.
    assertEquals(SearchProviderResolver.capabilityFor("qwenty", ""), None)
    assertEquals(SearchProviderResolver.capabilityFor("107", ""), None)
  }

  test("chainHeadProviderId: preferred, else first fallback") {
    assertEquals(
      SearchProviderResolver.chainHeadProviderId(Some(AgentModelConfig(Some("zhipu/glm-5.3"), List("kimi/k3")))),
      Some("zhipu")
    )
    assertEquals(
      SearchProviderResolver.chainHeadProviderId(Some(AgentModelConfig(None, List("deepseek/v4", "kimi/k3")))),
      Some("deepseek")
    )
  }

  // ── #356: search-aware chain reordering ──────────────────────────────

  test("searchOrderedModel: search-capable providers lead, original relative order preserved") {
    // deepseek (no builtin search) heads the chain; qwen/kimi (capable) are
    // fallbacks → qwen first (original order among capable), kimi second,
    // deepseek sinks to the tail.
    assertEquals(
      SearchProviderResolver.searchOrderedModel(
        Some(AgentModelConfig(Some("deepseek/v4"), List("qwen/qwen3.8-max", "kimi/k3")))
      ),
      Some(AgentModelConfig(Some("qwen/qwen3.8-max"), List("kimi/k3", "deepseek/v4")))
    )
    // capable members keep their relative order among themselves.
    assertEquals(
      SearchProviderResolver.searchOrderedModel(
        Some(AgentModelConfig(Some("deepseek/v4"), List("kimi/k3", "qwen/qwen3.8-max")))
      ),
      Some(AgentModelConfig(Some("kimi/k3"), List("qwen/qwen3.8-max", "deepseek/v4")))
    )
  }

  test("searchOrderedModel: chain head already capable → head unchanged (zero behavior change)") {
    // Stable partition: the incumbent zhipu-headed path must not move.
    assertEquals(
      SearchProviderResolver.searchOrderedModel(
        Some(AgentModelConfig(Some("zhipu/glm-5.3"), List("deepseek/v4", "kimi/k3")))
      ),
      Some(AgentModelConfig(Some("zhipu/glm-5.3"), List("kimi/k3", "deepseek/v4")))
    )
    // Single capable member, no fallbacks → identical config.
    assertEquals(
      SearchProviderResolver.searchOrderedModel(Some(AgentModelConfig(Some("kimi/k3"), Nil))),
      Some(AgentModelConfig(Some("kimi/k3"), Nil))
    )
  }

  test("searchOrderedModel: no search-capable member → chain unchanged (still Tier 3 via head)") {
    assertEquals(
      SearchProviderResolver.searchOrderedModel(
        Some(AgentModelConfig(Some("deepseek/v4"), List("107/glm-5.2-107")))
      ),
      Some(AgentModelConfig(Some("deepseek/v4"), List("107/glm-5.2-107")))
    )
  }

  // ── kimi echo byte-identity ──────────────────────────────────────────

  test("kimiEchoContent: raw arguments preserved byte-identically (formatting, order)") {
    // Spaced / oddly-formatted raw args: re-serialization would compact this.
    val raw = """{  "query" : "scala 3 release notes" ,  "freshness":"week"  }"""
    val input = io.circe.parser.parse(raw).toOption.flatMap(_.asObject).getOrElse(JsonObject())
    val call = ToolCall("call_1", "$web_search", input, Some(raw))
    assertEquals(SearchProviderResolver.kimiEchoContent(call), raw)
  }

  test("kimiEchoContent: malformed raw arguments still echo verbatim (no rescue degradation)") {
    // The round-trip must not depend on the arguments being valid JSON.
    val raw = """{"query": "broken""""
    val call = ToolCall("call_2", "$web_search", JsonObject(), Some(raw))
    assertEquals(SearchProviderResolver.kimiEchoContent(call), raw)
  }

  test("kimiEchoContent: no raw string → compact re-serialization fallback") {
    val call = ToolCall("call_3", "$web_search", JsonObject("query" -> "x".asJson))
    assertEquals(SearchProviderResolver.kimiEchoContent(call), """{"query":"x"}""")
  }

  // ── request-level injection gate (interface.scala delegates here) ────

  private val tool = ToolDefinition("Bash", "run", JsonObject())

  test("searchInjectionFor: all gates") {
    val r = SearchProviderResolver
    // housekeeping opt-out
    assertEquals(
      r.searchInjectionFor(false, Some(List(tool)), LlmProtocol.OpenAI, "zhipu", ""),
      None
    )
    // no tools list (maintenance calls) → clean
    assertEquals(
      r.searchInjectionFor(true, None, LlmProtocol.OpenAI, "zhipu", ""),
      None
    )
    // Anthropic protocol (107/deepseek) → clean
    assertEquals(
      r.searchInjectionFor(true, Some(List(tool)), LlmProtocol.Anthropic, "107", ""),
      None
    )
    // capable + OpenAI + armed
    assertEquals(
      r.searchInjectionFor(true, Some(List(tool)), LlmProtocol.OpenAI, "zhipu", "https://open.bigmodel.cn/api"),
      Some(ProviderSearchKind.ZhipuWebSearchTool)
    )
    // the Tier 2 executor sub-request shape: tools=Some(Nil) still injects
    assertEquals(
      r.searchInjectionFor(true, Some(Nil), LlmProtocol.OpenAI, "kimi", "https://api.moonshot.cn/v1"),
      Some(ProviderSearchKind.KimiBuiltinWebSearch)
    )
    // incapable provider (deepseek OpenAI-compatible) → None
    assertEquals(
      r.searchInjectionFor(true, Some(List(tool)), LlmProtocol.OpenAI, "deepseek", "https://api.deepseek.com/v1"),
      None
    )
  }

  // ── Tier 2 executor (scripted LlmHandle) ─────────────────────────────

  /** Scripted non-streaming handle: answers by call index, records requests. */
  private class ScriptedLlm(scripts: List[LlmResponse]) extends LlmHandle[IO]:
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      requests.modify { list => (req :: list, list.size) }.flatMap { idx =>
        scripts.lift(idx) match
          case Some(resp) => IO.pure(resp)
          case None => IO.raiseError(new RuntimeException(s"unexpected LLM send #$idx"))
      }
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.raiseError[IO](new RuntimeException("sendStream not expected here"))

  private def meta(providerId: String) =
    LlmMeta(sessionId = "s", agentId = "a", providerId = providerId, model = "m", durationMs = 1)

  private val zhipuModel = Some(AgentModelConfig(Some("zhipu/glm-5.3"), Nil))
  private val kimiModel = Some(AgentModelConfig(Some("kimi/kimi-k3"), Nil))
  private val deepseekModel = Some(AgentModelConfig(Some("deepseek/v4"), Nil))

  private val zhipuSearchInfo = Json.arr(
    Json.obj(
      "title" -> "Scala 3.5 release notes".asJson,
      "url" -> "https://example.com/scala35".asJson,
      "content" -> "What's new in 3.5".asJson
    ),
    Json.obj("title" -> "No-URL entry".asJson)
  )

  test("executor: zhipu structured searchInfo → Some with provenance + URL; sub-request armed with empty tools") {
    val llm = new ScriptedLlm(
      List(LlmResponse("answer", Nil, None, meta("zhipu"), Some(zhipuSearchInfo)))
    )
    SearchProviderResolver
      .executeProviderSearchFor("scala 3 release notes", Some(llm), "s", "a", zhipuModel)
      .map { result =>
        assert(result.isDefined, "structured searchInfo must produce a result")
        val r = result.get
        assert(r.contains("Search source: provider:zhipu"), r)
        assert(r.contains("https://example.com/scala35"), "result must carry source URL")
        assert(!r.contains("No-URL entry"), "URL-less entries must be dropped")
      }
      .flatMap { _ =>
        llm.requests.get.map { reqs =>
          assertEquals(reqs.size, 1)
          // Arming contract: executor requests carry Some(Nil) tools so the
          // interface-level injection gate arms provider search.
          assertEquals(reqs.head.tools, Some(Nil))
          // The query reached the prompt.
          assert(reqs.head.messages.exists(_.textContent.contains("scala 3 release notes")))
        }
      }
  }

  test("executor: qwen search_info object shape → normalized entries") {
    val qwenInfo = Json.obj(
      "search_results" -> Json.arr(
        Json.obj("title" -> "t1".asJson, "url" -> "https://example.com/1".asJson, "snippet" -> "s1".asJson)
      )
    )
    val llm = new ScriptedLlm(List(LlmResponse("ans", Nil, None, meta("qwen"), Some(qwenInfo))))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", Some(AgentModelConfig(Some("qwen/qwen3.8-max"), Nil)))
      .map { result =>
        assert(result.isDefined)
        assert(result.get.contains("provider:qwen"))
        assert(result.get.contains("https://example.com/1"))
        assert(result.get.contains("s1"))
      }
  }

  test("searchEntries: zhipu real-entry shape (link field, not url)") {
    // Verified against the live zhipu endpoint 2026-08-23: web_search entries
    // carry `link` (URL) + `content` (snippet), NOT `url`.
    val zhipuReal = Json.arr(
      Json.obj(
        "title" -> "36小时天气预报".asJson,
        "link" -> "https://www.bj.cma.cn/example".asJson,
        "content" -> "多云 西南风3级 最高气温32℃".asJson,
        "media" -> "北京市气象局".asJson,
        "publish_date" -> "2026-08-22".asJson
      )
    )
    val entries = SearchProviderResolver.searchEntries(zhipuReal)
    assert(entries.isDefined, "link-field entries must normalize")
    assertEquals(entries.get.size, 1)
    assertEquals(entries.get.head._2, "https://www.bj.cma.cn/example")
    assert(entries.get.head._3.contains("西南风"))
  }

  test("executor: NO evidence (no searchInfo, no kimi round-trip) → None (anti-hallucination)") {
    val llm = new ScriptedLlm(List(LlmResponse("我记忆中的答案是...", Nil, None, meta("zhipu"), None)))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", zhipuModel)
      .map(result => assertEquals(result, None, "parametric-memory answer must NOT count as search evidence"))
  }

  test("executor: kimi round-trip — echo byte-identical, reply accepted as evidence") {
    val rawArgs = """{ "query" : "moonshot kimi k3" }"""
    val parsed = io.circe.parser.parse(rawArgs).toOption.flatMap(_.asObject).getOrElse(JsonObject())
    val kimiCall = ToolCall("call_k1", SearchProviderResolver.KimiWebSearchToolName, parsed, Some(rawArgs))
    val llm = new ScriptedLlm(
      List(
        LlmResponse("", List(kimiCall), None, meta("kimi")),
        LlmResponse("Kimi K3 发布于…… 来源: https://example.com/kimi-k3", Nil, None, meta("kimi"))
      )
    )
    SearchProviderResolver
      .executeProviderSearchFor("kimi k3", Some(llm), "s", "a", kimiModel)
      .flatMap { result =>
        assert(result.isDefined, "kimi round-trip + non-empty reply is valid evidence")
        assert(result.get.contains("provider:kimi"))
        assert(result.get.contains("https://example.com/kimi-k3"))
        llm.requests.get.map { reqs =>
          assertEquals(reqs.size, 2, "exactly one echo round")
          val second = reqs.head // requests are prepended — head is the LAST request
          // The echo round must contain the tool result, byte-identical to raw.
          val toolResults = second.messages.flatMap { m =>
            m.content.toOption.toList.flatMap(
              _.collect { case ContentBlock.ToolResult(id, c, _) if id == "call_k1" => c }
            )
          }
          assertEquals(toolResults, List(rawArgs), "echo tool result must be byte-identical to model arguments")
        }
      }
  }

  test("executor: kimi round-trip budget — model re-issuing $web_search forever degrades to None") {
    val rawArgs = """{"query":"loop"}"""
    val kimiCall = ToolCall("call_loop", SearchProviderResolver.KimiWebSearchToolName, JsonObject(), Some(rawArgs))
    val endless = List.fill(6)(LlmResponse("", List(kimiCall), None, meta("kimi")))
    val llm = new ScriptedLlm(endless)
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", kimiModel)
      .flatMap { result =>
        assertEquals(result, None, "never-ending round-trip must hit the round budget")
        llm.requests.get.map(reqs => assertEquals(reqs.size, 3, "budget caps at 3 rounds"))
      }
  }

  test("executor: incapable chain head (deepseek) → None, LLM never called") {
    val llm = new ScriptedLlm(List(LlmResponse("x", Nil, None, meta("deepseek"))))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", deepseekModel)
      .flatMap { result =>
        assertEquals(result, None)
        llm.requests.get.map(reqs => assertEquals(reqs.size, 0, "Tier 3 directly — no provider sub-request"))
      }
  }

  test("#356: deepseek-headed chain with kimi fallback → routed to kimi (builtin search), real result") {
    // The MAIN chain head (deepseek) has no builtin search, but the reordered
    // chain leads with kimi — the Tier 2 sub-request lands on kimi, whose
    // structured search evidence produces a real result instead of Tier 3.
    val kimiInfo = Json.obj(
      "search_results" -> Json.arr(
        Json.obj("title" -> "CZT pixel detector".asJson, "url" -> "https://example.com/czt".asJson, "snippet" -> "sub-pixel readout".asJson)
      )
    )
    val llm = new ScriptedLlm(List(LlmResponse("ans", Nil, None, meta("kimi"), Some(kimiInfo))))
    val model = Some(AgentModelConfig(Some("deepseek/v4"), List("kimi/k3")))
    SearchProviderResolver
      .executeProviderSearchFor("CZT pixel detector sub-pixel readout", Some(llm), "s", "a", model)
      .flatMap { result =>
        assert(result.isDefined, "deepseek-headed chain must route to kimi and produce a real result")
        assert(result.get.contains("provider:kimi"), result.get)
        assert(result.get.contains("https://example.com/czt"), result.get)
        llm.requests.get.map { reqs =>
          assertEquals(reqs.size, 1, "one provider sub-request, on the reordered chain")
          // The sub-request carries the REORDERED model config — LlmHandle's
          // candidate pipeline (health/gate/fallback) then lands on kimi.
          assertEquals(reqs.head.agentModel, Some(AgentModelConfig(Some("kimi/k3"), List("deepseek/v4"))))
        }
      }
  }

  test("#356: only non-search providers in chain → Tier 3 directly, LLM never called") {
    // All chain members lack builtin search (deepseek + 107 gateway) — the
    // reordered chain is identical and its head still has no capability.
    val llm = new ScriptedLlm(List(LlmResponse("x", Nil, None, meta("deepseek"))))
    val model = Some(AgentModelConfig(Some("deepseek/v4"), List("107/glm-5.2-107")))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", model)
      .flatMap { result =>
        assertEquals(result, None)
        llm.requests.get.map(reqs => assertEquals(reqs.size, 0, "Tier 3 — no search-capable member"))
      }
  }

  test("#356: chain head already capable → sub-request on the ORIGINAL head (regression-free)") {
    // zhipu heads the chain: reordering keeps zhipu at the head; the
    // sub-request must still carry a zhipu-led reordered config.
    val zhipuInfo = Json.arr(
      Json.obj("title" -> "t".asJson, "url" -> "https://example.com/z".asJson, "content" -> "s".asJson)
    )
    val llm = new ScriptedLlm(List(LlmResponse("ans", Nil, None, meta("zhipu"), Some(zhipuInfo))))
    val model = Some(AgentModelConfig(Some("zhipu/glm-5.3"), List("deepseek/v4", "kimi/k3")))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", model)
      .flatMap { result =>
        assert(result.isDefined)
        assert(result.get.contains("provider:zhipu"))
        llm.requests.get.map(reqs =>
          assertEquals(reqs.head.agentModel, Some(AgentModelConfig(Some("zhipu/glm-5.3"), List("kimi/k3", "deepseek/v4"))))
        )
      }
  }

  test("executor: llm=None → None") {
    SearchProviderResolver
      .executeProviderSearchFor("q", None, "s", "a", zhipuModel)
      .map(result => assertEquals(result, None))
  }

  test("executor: LLM error degrades to None (never fails the WebSearch tool)") {
    val llm = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("provider down"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None) =
        Stream.raiseError[IO](new RuntimeException("unexpected"))
    SearchProviderResolver
      .executeProviderSearchFor("q", Some(llm), "s", "a", zhipuModel)
      .map(result => assertEquals(result, None, "errors degrade, not propagate"))
  }

end SearchProviderResolverSpec
