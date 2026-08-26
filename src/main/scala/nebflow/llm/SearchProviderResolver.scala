package nebflow.llm

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*
import sttp.client4.*

import scala.concurrent.duration.*

/** How a capable provider wants search armed in an OpenAI-compatible request. */
sealed trait ProviderSearchKind extends Product with Serializable
object ProviderSearchKind:
  /** zhipu GLM: server-side search tool — `{"type":"web_search"}` appended to
    * the tools array; search results surface in the answer + the response's
    * `web_search` field (structured, with URLs). */
  case object ZhipuWebSearchTool extends ProviderSearchKind

  /** qwen (DashScope): request-level flag `enable_search: true`. NOTE
    * (documented nuance): DashScope's OpenAI compatible-mode has been
    * reported to silently ignore this field — the degrade path (no search
    * evidence → Tier 3) is an ACCEPTED outcome, verified at smoke time. */
  case object QwenEnableSearch extends ProviderSearchKind

  /** kimi (Moonshot): `{"type":"builtin_function","function":{"name":"$web_search"}}`
    * appended to tools; mutually exclusive with thinking (forced disabled
    * by the adapter); requires the caller-side echo round-trip. */
  case object KimiBuiltinWebSearch extends ProviderSearchKind
end ProviderSearchKind

/** Which standalone search API adapter to use (Tier 2a, P2 2026-08-25). */
enum StandaloneSearchKind:
  /** zhipu web_search API: POST https://open.bigmodel.cn/api/paas/v4/web_search
    * with the EXISTING zhipu key (Bearer), billed per call (search_std
    * ¥0.01/call) SEPARATELY from model token quotas — a model-quota DOWN
    * (zhipu 1310 / qwen insufficient_quota) no longer takes search down. */
  case ZhipuWebSearch

/** Resolved, ready-to-call standalone search configuration (Tier 2a). */
case class StandaloneSearchConfig(
  provider: String,
  apiKey: String,
  baseUrl: String,
  engine: String
)

object StandaloneSearchConfig:
  val DefaultZhipuBaseUrl = "https://open.bigmodel.cn/api/paas/v4/web_search"
  val DefaultEngine = "search_std"
  val TimeoutMs = 15_000
  val Count = 8

sealed trait SearchRoute extends Product with Serializable
object SearchRoute:
  case class ProviderSearch(providerId: String, kind: ProviderSearchKind) extends SearchRoute
  case class StandaloneApi(providerId: String, kind: StandaloneSearchKind) extends SearchRoute
  case object BuiltinAggregated extends SearchRoute
end SearchRoute

/** WebSearch productization, P0 (docs/Nebflow/20260823_websearch-audit-and-plan.md).
  *
  * Resolution chain for who actually executes a web search:
  *
  *   Tier 1  user-installed search MCP tools (P1 — not wired yet)
  *   Tier 2  current provider's builtin search (zhipu / kimi / qwen) — this file
  *   Tier 3  builtin multi-engine aggregation (WebSearchTool), annotated
  *           non-guaranteed (keyless HTML scraping, no SLA)
  *
  * Tier 2 has two cooperating halves:
  *   - request-construction injection (OpenAiAdapter, driven by
  *     SendMessageParams.providerSearch set per-candidate in interface.scala):
  *     arms the provider's native search for every tool-bearing agent request,
  *     so the model can search natively without our WebSearch tool at all;
  *   - WebSearch tool interception (AgentCore.executeTool → executeProviderSearchFor):
  *     when the model still calls WebSearch, a dedicated provider search
  *     request is issued and its results returned with provenance.
  *
  * #356 (2026-08-23): the interception's Tier 2 decision is chain-WIDE, not
  * chain-head-bound. The model chain is reordered (search-capable providers
  * lead, original relative order preserved, the rest sink to the tail) so a
  * deepseek-headed chain whose fallbacks include kimi routes the search to
  * kimi — only when NO chain member has builtin search do we degrade to
  * Tier 3. A chain whose head already has search capability reorders to the
  * same head (stable partition) — zero behavioral change on the incumbent
  * paths.
  *
  * Anti-hallucination evidence rule: a provider search only counts as
  * successful when the response carries PROOF of a real search — structured
  * searchInfo (zhipu `web_search` field / qwen `search_info` field) with at
  * least one URL, or (kimi) an actual `$web_search` round-trip. A model that
  * merely answers from parametric memory produces no evidence and the caller
  * degrades to the builtin aggregation — a fabricated "search result" must
  * never reach the agent (same philosophy as the Tier 3 garbage filter).
  */
object SearchProviderResolver:

  /** kimi builtin search tool name (Moonshot round-trip semantics: the model
    * emits this tool call; the caller echoes the arguments back verbatim as
    * the tool result; the search then happens server-side on the next round). */
  val KimiWebSearchToolName = "$web_search"

  private val logger = NebflowLogger.forName("nebflow.llm.search")

  /** Capability lookup by provider id and/or baseUrl host.
    *
    * Two signals, either sufficient:
    *   - baseUrl host match (strongest — works for any user-chosen provider
    *     id pointing at the canonical endpoint; used by the request
    *     construction injection, which has the real baseUrl);
    *   - id family-token match: the provider id split into alphanumeric
    *     tokens (qwen-openai → {qwen, openai}). Used by the WebSearch
    *     interception path, which resolves from the agent's model chain head
    *     WITHOUT a baseUrl. Token equality (not prefix/substring) keeps
    *     "qwenty" and "provider-x" clean. */
  def capabilityFor(providerId: String, baseUrl: String): Option[ProviderSearchKind] =
    val id = providerId.trim.toLowerCase
    val url = baseUrl.trim.toLowerCase
    val tokens = id.split("[^a-z0-9]+").filter(_.nonEmpty).toSet
    if url.contains("bigmodel.cn") || id == "zhipu" || tokens.contains("zhipu") then
      Some(ProviderSearchKind.ZhipuWebSearchTool)
    else if url.contains("moonshot") || id == "kimi" || tokens.contains("kimi") then
      Some(ProviderSearchKind.KimiBuiltinWebSearch)
    else if url.contains("dashscope") || id == "qwen" || tokens.contains("qwen") then
      Some(ProviderSearchKind.QwenEnableSearch)
    else None

  /** Tier resolution for a provider (P0: Tier 1 MCP is a future stub — resolve
    * goes straight to Tier 2 or Tier 3). deepseek / provider-x / unknown → Tier 3.
    *
    * P2 (2026-08-25): Tier 2a — a STANDALONE search API (zhipu web_search,
    * per-call billing, independent of model quotas) is resolved separately
    * via resolveStandalone/loadStandaloneSearchConfig and tried BEFORE the
    * model-builtin path. */
  def resolve(providerId: String, baseUrl: String = ""): SearchRoute =
    capabilityFor(providerId, baseUrl) match
      case Some(kind) => SearchRoute.ProviderSearch(providerId, kind)
      case None       => SearchRoute.BuiltinAggregated

  /** Tier 2a route for a standalone search provider name (the nebflow.json
    * `search.provider` value). zhipu → StandaloneApi; anything else → None
    * (unknown providers are ignored, not fatal). */
  def resolveStandalone(provider: String): Option[SearchRoute] =
    provider.trim.toLowerCase match
      case "zhipu" => Some(SearchRoute.StandaloneApi("zhipu", StandaloneSearchKind.ZhipuWebSearch))
      case _       => None

  /** Pure mapping from the configured `search` block to a ready-to-call
    * standalone config (P2-4). None when disabled / empty key / unknown
    * provider — the caller then gracefully skips Tier 2a (existing users with
    * no `search` block are zero-migration: loadStandaloneSearchConfig returns
    * None and the old Tier 2b/3 chain runs unchanged). */
  private[llm] def standaloneConfigFrom(c: SearchConfig): Option[StandaloneSearchConfig] =
    if !c.enabled.getOrElse(true) || c.apiKey.trim.isEmpty then None
    else
      c.provider.trim.toLowerCase match
        case "zhipu" =>
          Some(
            StandaloneSearchConfig(
              provider = "zhipu",
              apiKey = c.apiKey.trim,
              baseUrl = c.baseUrl.getOrElse(StandaloneSearchConfig.DefaultZhipuBaseUrl).replaceAll("/+$", ""),
              engine = c.engine.getOrElse(StandaloneSearchConfig.DefaultEngine)
            )
          )
        case other =>
          logger.warnSync(
            s"search config references unsupported standalone provider '$other' — Tier 2a skipped"
          )
          None

  /** Read the standalone search config from nebflow.json on every call (the
    * config file is the single source of truth; a hot edit is picked up
    * without restart). Any load/parse failure degrades to None — Tier 2a is
    * an enhancement, never a crash path. */
  def loadStandaloneSearchConfig(): Option[StandaloneSearchConfig] =
    try Config.loadServiceConfig().search.flatMap(standaloneConfigFrom)
    catch
      case e: Exception =>
        logger.warnSync(s"search config load failed (${e.getMessage.take(120)}) — Tier 2a skipped")
        None

  /** Full model-ref chain for a WebSearch decision: agent's preferred ++
    * fallbacks, else the global default preset chain (mirrors
    * ProviderRegistry.getCandidates' fallback semantics). */
  private def modelChainRefs(agentModel: Option[AgentModelConfig]): List[String] =
    agentModel.toList.flatMap(m => m.preferred.toList ++ m.fallbacks) match
      case nonEmpty @ _ :: _ => nonEmpty
      case Nil =>
        try
          val (am, _) = nebflow.core.presets.PresetStore().resolve(None, None)
          am.preferred.toList ++ am.fallbacks
        catch case _: Exception => Nil

  private def providerIdOf(ref: String): Option[String] =
    try Some(Config.parseModelRef(ref)._1)
    catch case _: Exception => None

  /** Head provider id of a model chain: agent's preferred, else first
    * fallback, else the global default preset's preferred. Used by the
    * WebSearch interception to decide Tier 2 vs Tier 3. */
  def chainHeadProviderId(agentModel: Option[AgentModelConfig]): Option[String] =
    modelChainRefs(agentModel).headOption.flatMap(providerIdOf)

  /** Reorder the model chain for a Tier 2 provider search (#356): providers
    * with a builtin search capability float to the front (original relative
    * order preserved), the rest sink to the tail. The LlmHandle pipeline
    * (health/gate/fallback) then naturally lands on the FIRST AVAILABLE
    * search-capable provider — a deepseek-headed chain (no builtin search)
    * whose fallbacks include kimi now routes the search to kimi instead of
    * degrading straight to Tier 3. When every search-capable provider is
    * down/unavailable the request falls through to a non-search provider →
    * no search evidence → Tier 3 (existing degrade semantics, acceptance #3).
    *
    * A chain whose head already has search capability reorders to the same
    * head (stable partition) — zero behavioral change for the incumbent
    * zhipu/qwen/kimi paths (acceptance #2). Returns None only when the
    * chain is empty (nothing to route → caller goes Tier 3 directly). */
  def searchOrderedModel(agentModel: Option[AgentModelConfig]): Option[AgentModelConfig] =
    val refs = modelChainRefs(agentModel)
    if refs.isEmpty then None
    else
      val (capable, rest) = refs.partition(ref =>
        providerIdOf(ref).exists(pid => capabilityFor(pid, "").isDefined)
      )
      val ordered = capable ++ rest
      Some(AgentModelConfig(Some(ordered.head), ordered.tail))

  /** kimi echo content: the model's arguments, byte-identical. Falls back to
    * compact re-serialization only when no raw string survived (hand-built
    * ToolCalls in tests / non-adapter sources). */
  def kimiEchoContent(call: ToolCall): String =
    call.rawArguments.getOrElse(Json.fromJsonObject(call.input).noSpaces)

  /** Request-level injection gate (pure — unit tested): should this request,
    * going to THIS candidate, carry provider search injection? Called by
    * interface.scala per-candidate (fallback switches provider mid-request
    * and must not carry the previous provider's injection — e.g.
    * zhipu→deepseek must drop the web_search tool).
    *
    * Gates:
    *   - searchAllowed — housekeeping turns (compact/save/ask) and
    *     maintenance calls opt out;
    *   - tools.isDefined — the Tier 2 sub-request arms search via
    *     Some(Nil); tool-less maintenance calls (experience extraction,
    *     memory hook, onboarding probe) stay clean;
    *   - OpenAI protocol only — Anthropic-protocol candidates (provider-x,
    *     deepseek) have no P0 injection.
    */
  def searchInjectionFor(
      searchAllowed: Boolean,
      tools: Option[List[ToolDefinition]],
      protocol: LlmProtocol,
      providerId: String,
      baseUrl: String
  ): Option[ProviderSearchKind] =
    if !searchAllowed then None
    else if !tools.isDefined then None
    else if protocol != LlmProtocol.OpenAI then None
    else capabilityFor(providerId, baseUrl)

  // ── Tier 2 execution ─────────────────────────────────────────────────

  /** Max LLM rounds for one provider search (kimi's echo round-trip needs an
    * extra round; the budget also caps a model that keeps re-issuing
    * $web_search without ever answering). */
  private val MaxSearchRounds = 3

  private def searchPrompt(query: String): String =
    s"""使用网络搜索（web search）查找以下内容，返回搜索结果列表。要求：
       |1. 每条结果包含标题、URL、简短摘要；
       |2. 只返回真实搜索到的内容，不要用你自己记忆中的知识补充；
       |3. 如果搜索没有返回结果，直接说明没有搜到。
       |
       |搜索内容：$query""".stripMargin

  // ── Tier 2a: standalone search API (P2, 2026-08-25) ──────────────────

  /** Standalone search request body (P2-4 contract): search_query /
    * search_engine / count. Verified against the real zhipu endpoint
    * 2026-08-25 (the shape is accepted — the account returned 429 quota
    * error, not 400 schema error). Exposed private[llm] so the wire contract
    * is pinned by a unit test. */
  private[llm] def standaloneRequestBody(query: String, engine: String): Json =
    Json.obj(
      "search_query" -> query.asJson,
      "search_engine" -> engine.asJson,
      "search_intent" -> true.asJson,
      "count" -> StandaloneSearchConfig.Count.asJson
    )

  /** HTTP-status error classification for the standalone API (P2-5):
    * 401/403 → auth, 429 → quota/rate (zhipu code 1113 "余额不足或无可用资源包"
    * when the account has no search balance), 5xx → server, timeout → explicit,
    * else generic — the provider's message is never swallowed. */
  private[llm] def classifyStandaloneError(code: Int, body: String, timeout: Boolean): String =
    if timeout then "standalone search API timeout"
    else if code == 401 || code == 403 then s"standalone search API auth failed (HTTP $code)"
    else if code == 429 then s"standalone search API quota/rate limited (HTTP 429): ${body.take(200)}"
    else if code >= 500 then s"standalone search API server error (HTTP $code)"
    else s"standalone search API error (HTTP $code): ${body.take(200)}"

  /** Parse the standalone API's structured `search_result[]` envelope (zhipu
    * web_search: title/content/link/media/icon/refer/publish_date). URL-less
    * entries are dropped — a source-less entry cannot be cited. */
  private[llm] def parseStandaloneResults(body: String): Option[List[(String, String, String)]] =
    io.circe.parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.downField("search_result").as[List[Json]].toOption)
      .map(
        _.flatMap { entry =>
          val h = entry.hcursor
          val url = h.downField("link").as[String].toOption.orElse(h.downField("url").as[String].toOption)
          url.filter(_.startsWith("http")).map { u =>
            val title = h.downField("title").as[String].toOption.getOrElse("")
            val snippet = h.downField("content").as[String].toOption.getOrElse("")
            (title, u, snippet)
          }
        }
      )

  /** Synchronous standalone search call (sttp over SharedBackend — no
    * LlmHandle, zero model tokens, no model health/quota gating). Returns
    * Right(formatted result with `search-api:<provider>` provenance) or
    * Left(diagnostic). */
  def executeStandaloneSearch(query: String, cfg: StandaloneSearchConfig): Either[String, String] =
    try
      val backend = SharedBackend.instance
      val request = basicRequest
        .post(uri"${cfg.baseUrl}")
        .header("Authorization", s"Bearer ${cfg.apiKey}")
        .header("Content-Type", "application/json")
        .body(standaloneRequestBody(query, cfg.engine).noSpaces)
        .readTimeout(StandaloneSearchConfig.TimeoutMs.millis)
        .response(asStringAlways)
      val resp = request.send(backend)
      if !resp.code.isSuccess then Left(classifyStandaloneError(resp.code.code, resp.body, timeout = false))
      else
        parseStandaloneResults(resp.body) match
          case Some(entries) if entries.nonEmpty =>
            Right(formatStandaloneResult(cfg.provider, formatEntries(entries)))
          case _ => Left(s"standalone search API returned no parseable results (${cfg.provider})")
    catch
      case e: Exception =>
        val raw = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        val isTimeout = raw.toLowerCase.contains("timed out") || raw.toLowerCase.contains("timeout")
        Left(classifyStandaloneError(0, raw.take(120), timeout = isTimeout))

  private def formatStandaloneResult(providerId: String, content: String): String =
    s"Search source: search-api:$providerId (standalone search API, per-call SLA)\n\n$content"

  /** Tier 2a call + health recording (P2-6: the search API health channel
    * lives in ProviderHealthMonitor, independent of model-provider health). */
  private def executeStandaloneFor(
      query: String,
      cfg: StandaloneSearchConfig,
      health: Option[ProviderHealthMonitor]
  ): IO[Either[String, String]] =
    IO.blocking(executeStandaloneSearch(query, cfg)).flatTap {
      case Right(_)     => health.traverse_(_.recordSearchSuccess())
      case Left(err)    => health.traverse_(_.recordSearchFailure(err))
    }

  /** WebSearch Tier resolution (P2, 2026-08-25): Tier 2a standalone search
    * API FIRST (configured `search` block — per-call billing, independent of
    * model quotas), then Tier 2b (provider builtin via the model pipeline),
    * then the caller falls back to Tier 3 (builtin aggregation). A 2a failure
    * degrades to 2b with a diagnostic; no `search` config → 2a skipped
    * silently (zero-migration for existing users). */
  def executeProviderSearchFor(
      query: String,
      llm: Option[LlmHandle[IO]],
      sessionId: String,
      agentId: String,
      agentModel: Option[AgentModelConfig],
      standalone: Option[StandaloneSearchConfig] = None,
      health: Option[ProviderHealthMonitor] = None
  ): IO[Option[String]] =
    val cfg = standalone.orElse(loadStandaloneSearchConfig())
    cfg match
      case Some(c) =>
        executeStandaloneFor(query, c, health).flatMap {
          case Right(result) => IO.pure(Some(result))
          case Left(err) =>
            logger
              .warn(s"Tier 2a standalone search failed (${c.provider}): $err — degrading to Tier 2b (provider builtin)")
              *> tier2b(query, llm, sessionId, agentId, agentModel)
        }
      case None => tier2b(query, llm, sessionId, agentId, agentModel)

  /** Tier 2b: the model-builtin search path (unchanged from P0/#356 — chain-
    * wide reorder so a deepseek-headed chain routes to a search-capable
    * fallback). Runs through the FULL LlmHandle pipeline. */
  private def tier2b(
      query: String,
      llm: Option[LlmHandle[IO]],
      sessionId: String,
      agentId: String,
      agentModel: Option[AgentModelConfig]
  ): IO[Option[String]] =
    llm match
      case None => IO.pure(None)
      case Some(handle) =>
        // #356: the search sub-request must not be bound to the MAIN chain
        // head's search capability. Reorder the chain so search-capable
        // providers lead — the full LlmHandle pipeline (health/gate/fallback)
        // then lands on the first AVAILABLE search-capable provider. Only
        // when NO chain member has builtin search (or the chain is empty)
        // do we go Tier 3 directly.
        searchOrderedModel(agentModel) match
          case None => IO.pure(None) // Tier 3 directly (no chain / deepseek-only / provider-x / unknown)
          case Some(ordered) =>
            chainHeadProviderId(Some(ordered)).flatMap(capabilityFor(_, "")) match
              case None => IO.pure(None) // no search-capable member in chain → Tier 3
              case Some(kind) =>
                execute(query, handle, sessionId, agentId, Some(ordered), kind)

  private def execute(
      query: String,
      handle: LlmHandle[IO],
      sessionId: String,
      agentId: String,
      agentModel: Option[AgentModelConfig],
      kind: ProviderSearchKind
  ): IO[Option[String]] =
    def loop(messages: List[Message], round: Int, sawKimiSearch: Boolean): IO[Option[String]] =
      if round > MaxSearchRounds then
        logger
          .warn(s"provider search exceeded round budget ($MaxSearchRounds) — degrading to builtin")
          .as(None)
      else
        handle
          .send(
            LlmRequest(
              messages = messages,
              sessionId = sessionId,
              agentId = agentId,
              tools = Some(Nil),
              agentModel = agentModel
            )
          )
          .flatMap { resp =>
            val kimiCalls = resp.toolCalls.filter(_.name == KimiWebSearchToolName)
            if kimiCalls.nonEmpty then
              // kimi round-trip: echo the model's arguments back verbatim as
              // the tool results; the search happens server-side next round.
              val echoMessages = messages ++
                List(
                  Message(
                    MessageRole.Assistant,
                    Right(resp.toolCalls.map(tc => ContentBlock.ToolUse(tc.id, tc.name, tc.input)))
                  )
                ) ++
                kimiCalls.map(tc =>
                  Message(MessageRole.User, Right(List(ContentBlock.ToolResult(tc.id, kimiEchoContent(tc)))))
                )
              loop(echoMessages, round + 1, sawKimiSearch = true)
            else
              evidence(resp, sawKimiSearch) match
                case Some(content) =>
                  IO.pure(Some(formatProviderResult(resp.meta.providerId, content)))
                case None =>
                  logger
                    .info(
                      s"provider search produced no search evidence (provider=${resp.meta.providerId}, " +
                        s"searchInfo=${resp.searchInfo.isDefined}, kimiRoundTrip=$sawKimiSearch) — " +
                        "degrading to builtin aggregation"
                    )
                    .as(None)
          }

    loop(List(Message(MessageRole.User, Left(searchPrompt(query)))), 1, sawKimiSearch = false)
      .handleErrorWith { e =>
        logger
          .warn(s"provider search failed (${e.getMessage.take(160)}) — degrading to builtin aggregation")
          .as(None)
      }
  end execute

  /** Success evidence for a provider search response. */
  private def evidence(resp: LlmResponse, sawKimiSearch: Boolean): Option[String] =
    resp.searchInfo.flatMap(searchEntries) match
      case Some(entries) if entries.nonEmpty => Some(formatEntries(entries))
      case _ =>
        if sawKimiSearch && resp.reply.nonEmpty then Some(resp.reply.take(15_000))
        else None

  /** Normalize zhipu's `web_search` array / qwen's `search_info` object into
    * (title, url, snippet) triples. Entries without a URL are dropped — a
    * source-less entry cannot be cited. Field-name note: zhipu's entries use
    * `link` for the URL (verified against the real endpoint 2026-08-23);
    * qwen uses `url` — accept both. */
  private[llm] def searchEntries(searchInfo: Json): Option[List[(String, String, String)]] =
    val arr: Option[List[Json]] =
      if searchInfo.isArray then searchInfo.asArray.map(_.toList)
      else if searchInfo.isObject then
        searchInfo.hcursor
          .downField("search_results")
          .as[List[Json]]
          .toOption
          .orElse(searchInfo.hcursor.downField("results").as[List[Json]].toOption)
      else None
    arr.map(_.flatMap { entry =>
      val h = entry.hcursor
      val urlOpt = h.downField("url").as[String].toOption
        .orElse(h.downField("link").as[String].toOption)
      urlOpt.filter(u => u.startsWith("http")).map { url =>
        val title = h.downField("title").as[String].toOption.getOrElse("")
        val snippet =
          h.downField("content").as[String].toOption
            .orElse(h.downField("snippet").as[String].toOption)
            .orElse(h.downField("summary").as[String].toOption)
            .getOrElse("")
        (title, url, snippet)
      }
    })

  private def formatEntries(entries: List[(String, String, String)]): String =
    entries
      .map { case (title, url, snippet) =>
        val t = if title.nonEmpty then s"**$title**\n" else ""
        val s = if snippet.nonEmpty then s"\n$snippet" else ""
        s"$t$url$s"
      }
      .mkString("\n\n")

  private def formatProviderResult(providerId: String, content: String): String =
    s"Search source: provider:$providerId (provider builtin web search, SLA-backed)\n\n$content"

end SearchProviderResolver
