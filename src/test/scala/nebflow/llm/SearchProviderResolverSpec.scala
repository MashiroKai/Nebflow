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
