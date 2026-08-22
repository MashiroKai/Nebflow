package nebflow.llm

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*

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

sealed trait SearchRoute extends Product with Serializable
object SearchRoute:
  case class ProviderSearch(providerId: String, kind: ProviderSearchKind) extends SearchRoute
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

  /** Capability lookup by provider id and/or baseUrl host. baseUrl is the
    * stronger signal (works for any user-chosen provider id pointing at the
    * canonical endpoint); the id match covers setups that keep the family
    * name behind a custom base. Either signal alone is sufficient. */
  def capabilityFor(providerId: String, baseUrl: String): Option[ProviderSearchKind] =
    val id = providerId.trim.toLowerCase
    val url = baseUrl.trim.toLowerCase
    if id == "zhipu" || url.contains("bigmodel.cn") then Some(ProviderSearchKind.ZhipuWebSearchTool)
    else if id == "kimi" || url.contains("moonshot") then Some(ProviderSearchKind.KimiBuiltinWebSearch)
    else if id == "qwen" || url.contains("dashscope") then Some(ProviderSearchKind.QwenEnableSearch)
    else None

  /** Tier resolution for a provider (P0: Tier 1 MCP is a future stub — resolve
    * goes straight to Tier 2 or Tier 3). deepseek / 107 / unknown → Tier 3. */
  def resolve(providerId: String, baseUrl: String = ""): SearchRoute =
    capabilityFor(providerId, baseUrl) match
      case Some(kind) => SearchRoute.ProviderSearch(providerId, kind)
      case None       => SearchRoute.BuiltinAggregated

  /** Head provider id of a model chain: agent's preferred, else first
    * fallback, else the global default preset's preferred. Used by the
    * WebSearch interception to decide Tier 2 vs Tier 3. */
  def chainHeadProviderId(agentModel: Option[AgentModelConfig]): Option[String] =
    val agentChain = agentModel.toList.flatMap(m => m.preferred.toList ++ m.fallbacks)
    val chain = agentChain match
      case nonEmpty @ _ :: _ => nonEmpty
      case Nil =>
        // Agent has no model config → global default preset chain (mirrors
        // ProviderRegistry.getCandidates' fallback semantics).
        try
          val (am, _) = nebflow.core.presets.PresetStore().resolve(None, None)
          am.preferred.toList ++ am.fallbacks
        catch case _: Exception => Nil
    chain.headOption.flatMap { ref =>
      try
        val (providerId, _) = Config.parseModelRef(ref)
        Some(providerId)
      catch case _: Exception => None
    }

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
    *   - OpenAI protocol only — Anthropic-protocol candidates (107,
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

  /** Tier 2 WebSearch execution. Returns Some(formatted result) on success
    * (with provider provenance), None when Tier 2 is unavailable or produced
    * no search evidence — the caller then falls back to the builtin
    * aggregation (Tier 3).
    *
    * The sub-request goes through the FULL LlmHandle pipeline (candidates,
    * health, gate, fallback), so it lands on the session's real provider.
    * `tools = Some(Nil)` arms the search injection (interface injects only
    * for tool-bearing requests) without exposing any agent tools. */
  def executeProviderSearchFor(
      query: String,
      llm: Option[LlmHandle[IO]],
      sessionId: String,
      agentId: String,
      agentModel: Option[AgentModelConfig]
  ): IO[Option[String]] =
    llm match
      case None => IO.pure(None)
      case Some(handle) =>
        chainHeadProviderId(agentModel).flatMap(capabilityFor(_, "")) match
          case None => IO.pure(None) // Tier 3 directly (deepseek/107/unknown)
          case Some(kind) =>
            execute(query, handle, sessionId, agentId, agentModel, kind)

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
    * source-less entry cannot be cited. */
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
      h.downField("url").as[String].toOption.filter(u => u.startsWith("http")).map { url =>
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
