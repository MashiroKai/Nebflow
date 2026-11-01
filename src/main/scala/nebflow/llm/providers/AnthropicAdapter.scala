package nebflow.llm.providers

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.llm.{AdapterResponse, ProviderAdapter, ResolvedThinking, SendMessageParams}
import nebflow.core.compact.TokenEstimator
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*

import scala.concurrent.duration.*

class AnthropicAdapter(
  baseUrl: String,
  apiKey: String,
  backend: StreamBackend[IO, Fs2Streams[IO]],
  // When true, unsigned thinking blocks from assistant history are replayed
  // (without a signature field) instead of dropped. DeepSeek's Anthropic-
  // compatible endpoint requires thinking blocks to be passed back in thinking
  // mode; real Anthropic rejects them without a signature. Set per provider
  // in ProviderRegistry.createAdapter.
  requireThinkingPassback: Boolean = false,
  /**
   * provider-compat 批（2026-11-01）：本适配器服务的 provider 名。两个用途，
   * 都不是行为开关：
   *   1. **路由自述**——每请求把 `provider=<id> model=<id>` 写进 dynamic
   *      system 段（[[WireShape.routeBanner]]），批自己就能读到自己在哪条腿上；
   *   2. **诊断串**——形状类失败必须能说出"谁的腿"（作者实证：缺这两字段时
   *      冒烟批只能答 provider=unknown model=unknown）。
   * 缺省 "unknown" 保证既有构造点（测试 / 辅助面）源码兼容。
   */
  providerId: String = "unknown",
  /**
   * provider 级额外 HTTP 头（`ProviderConfig.headers`，protocol-compat 批）。
   * 装配唯一一点 = [[HttpHeaders]]：引擎缺省必带 User-Agent，本参数覆盖/追加。
   * 事故背景见 [[HttpHeaders]] 文件头（cmdcode 的 403/1010 = 无 UA 被 Cloudflare 挡）。
   */
  providerHeaders: Option[Map[String, String]] = None
) extends ProviderAdapter[IO]:
  private val base = baseUrl.replaceAll("/+$", "")

  /** Full endpoint URL: use base as-is if it already points to /v1/messages, otherwise append. */
  private val endpoint =
    if base.endsWith("/v1/messages") then base else s"$base/v1/messages"

  // Holds (inputTokens, cacheReadTokens, cacheCreationTokens) from message_start
  // private[providers] so specs can drive processAnthropicEvent.
  private[providers] case class Tokens(input: Int, cacheRead: Option[Int], cacheWrite: Option[Int])

  // private[providers] for spec access (pure JSON mapping, no backend needed)
  private[providers] def toAnthropicMessages(messages: List[Message]): List[Json] =
    mergeConsecutive(pairToolResults(messages.filterNot(_.role == MessageRole.System))).map { msg =>
      val role = msg.role match
        case MessageRole.User => "user"
        case MessageRole.Assistant => "assistant"
        case _ => "user"

      msg.content match
        case Left(text) =>
          Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(text))
        case Right(blocks) =>
          val content = blocks.map {
            case ContentBlock.Text(text) =>
              Json.obj("type" -> "text".asJson, "text" -> text.asJson)
            case ContentBlock.Image(data, mediaType) =>
              Json.obj(
                "type" -> "image".asJson,
                "source" -> Json.obj(
                  "type" -> "base64".asJson,
                  "media_type" -> mediaType.asJson,
                  "data" -> data.asJson
                )
              )
            case ContentBlock.ToolUse(id, name, input) =>
              Json.obj(
                "type" -> "tool_use".asJson,
                "id" -> id.asJson,
                "name" -> name.asJson,
                "input" -> Json.fromJsonObject(input)
              )
            case ContentBlock.ToolResult(toolUseId, content, isError) =>
              val base = Json.obj(
                "type" -> "tool_result".asJson,
                "tool_use_id" -> toolUseId.asJson,
                "content" -> content.asJson
              )
              if isError.contains(true) then base.deepMerge(Json.obj("is_error" -> true.asJson)) else base
            case ContentBlock.Thinking(thinking, signature) =>
              // Anthropic requires signature when thinking mode is enabled.
              // Providers that don't send signature (e.g. DeepSeek) will have None here.
              // Including a thinking block without signature causes API rejection.
              signature.filter(_.nonEmpty) match
                case Some(sig) =>
                  Json.obj(
                    "type" -> "thinking".asJson,
                    "thinking" -> thinking.asJson,
                    "signature" -> sig.asJson
                  )
                case None =>
                  // Unsigned thinking: replay it only for providers that demand
                  // passback (DeepSeek). Dropping it was the root cause of the
                  // recurring deepseek DOWN cycles on 2026-08-15 — its thinking
                  // mode rejects history that omits thinking blocks.
                  if requireThinkingPassback then
                    Json.obj(
                      "type" -> "thinking".asJson,
                      "thinking" -> thinking.asJson
                    )
                  else Json.Null
          }
          val filtered = content.filterNot(_ == Json.Null)
          if filtered.isEmpty then Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(" "))
          else Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(filtered))
      end match
    }

  /**
   * Protocol-level pairing defense for Anthropic tool calls.
   *
   * Root cause (2026-08-23 deepseek 422 loop): a user(tool_result) message can
   * survive context cleanup while its preceding assistant(tool_use) message is
   * truncated away — the orphaned tool_result then heads the message list and
   * the provider rejects it (`Each tool_result block must have a corresponding
   * tool_use block in the previous message`). Observed live: Manager session
   * started with an orphan `[Output removed to free context space]` result
   * referencing a yesterday tool_use id → deepseek DOWN/recovered loop.
   *
   * Fix: scan the message list, collect every tool_use id seen in assistant
   * messages (in order), and drop tool_result blocks whose id was never seen.
   * A user message whose tool_use was consumed by cleanup (all remaining
   * blocks are the now-dangling tool_result's siblings) keeps its text —
   * dropping ONLY the orphaned result block, never whole messages.
   */
  private def pairToolResults(messages: List[Message]): List[Message] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    messages.map {
      case msg @ Message(MessageRole.Assistant, Right(blocks), _, _) =>
        blocks.foreach {
          case ContentBlock.ToolUse(id, _, _) => seen += id
          case _ => ()
        }
        msg
      case msg @ Message(MessageRole.User, Right(blocks), _, _) =>
        val paired = blocks.filter {
          case ContentBlock.ToolResult(toolUseId, _, _) => seen.contains(toolUseId)
          case _ => true
        }
        if paired.size == blocks.size then msg
        else
          val newBlocks =
            if paired.isEmpty then
              List(ContentBlock.Text("[dropped orphaned tool_result: referenced tool_use is not in history]"))
            else paired
          msg.copy(content = Right(newBlocks))
      case other => other
    }

  end pairToolResults

  /** Merge consecutive messages of the same role to prevent tool_use/tool_result pairing issues. */
  private def mergeConsecutive(messages: List[Message]): List[Message] =
    if messages.isEmpty then Nil
    else
      messages.tail.foldLeft(List(messages.head)) { (acc, msg) =>
        val last = acc.last
        if last.role == msg.role then
          val merged = mergeMessages(last, msg)
          acc.init :+ merged
        else acc :+ msg
      }

  private def mergeMessages(a: Message, b: Message): Message =
    val mergedContent = (a.content, b.content) match
      case (Left(aText), Left(bText)) =>
        Left(aText + "\n" + bText)
      case (Right(aBlocks), Right(bBlocks)) =>
        Right(aBlocks ++ bBlocks)
      case (Left(text), Right(blocks)) =>
        Right(ContentBlock.Text(text) +: blocks)
      case (Right(blocks), Left(text)) =>
        Right(blocks :+ ContentBlock.Text(text))
    Message(a.role, mergedContent, math.max(a.timestamp, b.timestamp))

  private def toAnthropicTools(tools: List[ToolDefinition]): Json =
    val toolJsons = tools.map { t =>
      Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "input_schema" -> Json.fromJsonObject(t.inputSchema)
      )
    }
    // Add cache_control to the last tool definition to extend the cache prefix through tools
    if toolJsons.isEmpty then Json.fromValues(Nil)
    else
      val (init, last) = (toolJsons.init, toolJsons.last)
      val lastWithCache = last.deepMerge(Json.obj("cache_control" -> Json.obj("type" -> "ephemeral".asJson)))
      Json.fromValues(init :+ lastWithCache)

  /** Build the system field as content blocks with cache_control on the stable prefix. */
  private def buildSystemBlocks(params: SendMessageParams): Json =
    val stableOpt = params.systemStable.filter(_.nonEmpty)
    // 路由自述（protocol-compat 批）：`provider=<id> model=<id>` 拼在 dynamic
    // 段尾部——稳定前缀（cache breakpoint）逐字节不变，换腿只改尾巴。
    val dynamicOpt = Some(
      List(params.systemDynamic.filter(_.nonEmpty), Some(WireShape.routeBanner(providerId, params.model)))
        .flatten
        .mkString("\n\n")
    ).filter(_.nonEmpty)
    // Fallback: if systemParts not provided, extract from messages (backward compat)
    val fallbackSystem = params.messages.find(_.role == MessageRole.System).map(_.textContent).filter(_.nonEmpty)

    val stable = stableOpt.orElse(fallbackSystem)
    if stable.isEmpty && dynamicOpt.isEmpty then Json.Null
    else
      val blocks = scala.collection.mutable.ListBuffer.empty[Json]
      stable.foreach { text =>
        // Stable part gets cache_control — this is the cache breakpoint shared across sessions
        blocks += Json.obj(
          "type" -> "text".asJson,
          "text" -> text.asJson,
          "cache_control" -> Json.obj("type" -> "ephemeral".asJson)
        )
      }
      dynamicOpt.foreach { text =>
        // Dynamic part (env info, reminders) — no cache_control, changes every turn
        blocks += Json.obj(
          "type" -> "text".asJson,
          "text" -> text.asJson
        )
      }
      Json.fromValues(blocks.toList)
    end if
  end buildSystemBlocks

  /**
   * 跨 provider thinking 重放适配（审计 20260903 子项④，flap 风暴底层缺陷）。
   *
   * 会话历史由「不产出 thinking 块的 provider」生成时（所有 OpenAI 协议
   * adapter 的 history 序列化都丢弃 thinking；GLM/zhipu 会话即此形态），
   * fallback 到 requireThinkingPassback 的 Anthropic 兼容端点（deepseek）
   * 会 400 "The content[].thinking in the thinking mode must be passed back
   * to the API"——thinking 模式下 assistant 历史缺 thinking 块。实测 25 次
   * DOWN 全部源于此（09-03 flap 风暴，p50 0.9s 探测秒回 UP 后再 400）。
   *
   * 取舍（转换优先、不可转换则剥离）：
   *  - 历史「有」thinking 块 → 原样回传（2026-08-15 既有契约，本签名 spec
   *    锁定；含签名块的回传行为不变）。
   *  - 历史「缺」thinking 块 → 转换不可能（无法伪造模型没产生的推理内容）
   *    → 剥离 = 本请求不进 thinking 模式（去掉 thinking 参数）。deepseek 对
   *    纯文本历史在非 thinking 模式下正常接受；后续轮次由 deepseek 自己产生
   *    thinking 块后自然恢复 thinking 模式。
   */
  private[providers] def effectiveThinking(params: SendMessageParams): Option[Json] =
    params.thinking match
      case Some(t) if requireThinkingPassback && hasAssistantWithoutThinking(params.messages) =>
        // protocol-compat 批（2026-11-01）：这条诊断原先是 `logger.warn(...)` 的
        // IO 值——在纯函数里构造完就被丢弃，**从未落盘**（OpenAiAdapter:669 记
        // 过同族教训："builds-but-never-runs the inner IO"）。deepseek Format
        // 事故复盘时正是因为这行静默，才无法确认剥离腿有没有走过。改 warnSync
        // 同步落盘，并带上形状指纹（哪条历史消息缺思考块）。
        nebflow.shared.NebflowLogger
          .forName("nebflow.llm.anthropic")
          .warnSync(
            s"thinking mode dropped for this request: ${WireShape.routeLine(providerId, params.model)} " +
              "requires thinking passback but session history contains assistant messages without " +
              "thinking blocks (cross-provider replay) — replaying would 400 (audit 20260903 #4); " +
              s"request=${WireShape.requestShape(params.messages)}"
          )
        None
      case other => other

  private def hasAssistantWithoutThinking(messages: List[Message]): Boolean =
    messages.exists {
      case Message(MessageRole.Assistant, Right(blocks), _, _) =>
        !blocks.exists(_.isInstanceOf[ContentBlock.Thinking])
      case Message(MessageRole.Assistant, Left(_), _, _) => true
      case _ => false
    }

  // ── wire 翻译表（thinking-config 批 2026-10-28，设计 §4.2）────────────────
  //
  // 唯一翻译点：语义载体（params.thinking：enabled → budget JSON，off → Null）
  // × 能力类（显式 ResolvedThinking.kind，缺省模型族启发）→ 本请求要 deepMerge
  // 进 body 的 JSON 片段（可能为空 = 不发任何思考键）。两家发送面共用。
  //
  // | 能力类            | enabled 线上形态                                   | off 线上形态            |
  // |-------------------|---------------------------------------------------|------------------------|
  // | Budget            | thinking:{type:enabled, budget_tokens:N}          | 省略键（修掉 null）     |
  // | EffortAnthropic   | thinking:{type:enabled}（不发 budget_tokens）      | thinking:{type:disabled}|
  // | Toggle（强制开）  | thinking:{type:enabled}(+clear_thinking 配置时)    | 兜底 enabled（禁 off）  |
  // | EffortTopLevel    | 省略 thinking 键（K3 官方明示移除）                 | 省略                   |
  //
  // effort 档位（output_config.effort / reasoning_effort）是第二段点亮腿——
  // [[ThinkingEffortGate]] 逐族核定门控。

  /** 本请求的能力类：显式配置永远赢，缺省模型族启发（设计 §4.2 铁律）。 */
  private[providers] def kindOf(params: SendMessageParams): ThinkingKind =
    params.thinkingResolved
      .map(_.kind)
      .getOrElse(ThinkingFamily.forModel(params.model))

  /**
   * 是否进 thinking 模式：语义载体开着 且 没有 layer 显式关。全局 master off
   * （语义载体 = Null）不可被 layer 复活——保守方向（强制思考模型的 off 错误
   * 路径风险只降不升）。
   */
  private[providers] def wireThinkingOn(params: SendMessageParams): Boolean =
    params.thinking.exists(!_.isNull) && !params.thinkingResolved.exists(t => !t.enabled)

  /**
   * 本请求的思考 wire 片段（deepMerge 进 body；空对象 = 不发任何思考键）。
   *
   * **protocol-compat 批（2026-11-01）——deepseek Format 事故的根因修复**：
   * passback 剥离（`effectiveThinking = None`）旧实现发「空片段 = 省略 thinking
   * 键」。对**默认思考**的模型族（DeepSeek）这是致命误判：缺键 ≠ 关思考，
   * 缺键 = 进默认思考模式 ⇒ 上游要求回传历史思考块 ⇒ 400
   * "The `content[].thinking` in the thinking mode must be passed back to the
   * API" ⇒ 引擎 400 归 Format/Permanent ⇒ 候选判死 ⇒ 整批全灭。
   *
   * 实测（真端点，2026-11-01，deepseek anthropic 面）：
   *   省略 thinking 键 + 无思考块历史 → **400**（正是事故报文）
   *   thinking:{type:"disabled"} + 同一历史 → **200**
   *   thinking:{type:"enabled"} + 回传思考块 → 200
   * ⇒ 剥离腿必须发**该族可表达的关态**，不能靠省略键赌对方默认值。
   * [[stripWire]] 就是这条判据：能表达关态的族发关态，不能的族才省略。
   */
  private[providers] def wireThinkingFragment(params: SendMessageParams): Json =
    effectiveThinking(params) match
      case None => stripWire(kindOf(params))
      case Some(_) if !wireThinkingOn(params) => offWire(kindOf(params))
      case Some(t) => onWire(params, kindOf(params), t)

  /**
   * 剥离腿（passback 不可转换时的降级形态）：尽量**显式表达"不进思考模式"**。
   *
   *   - EffortAnthropic（DeepSeek 族）：`thinking:{type:"disabled"}`。省略键会
   *     被对方当成默认思考（实测 400），必须显式关。
   *   - Budget（官方 anthropic 面）：省略键——官方默认非思考，省略即关。
   *   - Toggle（glm / kimi）：省略键。两族对 disabled 形态未核实（kimi 对非
   *     enabled 形态 400 有记录），保持旧行为不引入新回归。
   *   - EffortTopLevel（K3）：官方明示不得发 thinking 键 ⇒ 省略。
   *
   * 与 [[offWire]] 的差别：offWire 是"用户/配置显式关了思考"，stripWire 是
   * "历史不可转换，本请求降级离开思考模式"。两者在 EffortAnthropic 上收敛到
   * 同一形态（这正是对的：对默认思考的端点，"关"只有一种可信表达）。
   */
  private[providers] def stripWire(kind: ThinkingKind): Json = kind match
    case ThinkingKind.EffortAnthropic => offWire(kind)
    case _                             => Json.obj()

  private def offWire(kind: ThinkingKind): Json = kind match
    case ThinkingKind.Budget => Json.obj() // off → 省略键（旧形态 "thinking": null 由此消灭）
    case ThinkingKind.EffortAnthropic => Json.obj("thinking" -> Json.obj("type" -> "disabled".asJson))
    case ThinkingKind.Toggle =>
      Json.obj("thinking" -> Json.obj("type" -> "enabled".asJson)) // 强制思考：off 兜底 enabled
    case ThinkingKind.EffortTopLevel => Json.obj() // K3 恒推理，无「关」可表达

  private def onWire(params: SendMessageParams, kind: ThinkingKind, semantic: Json): Json =
    kind match
      case ThinkingKind.Budget => Json.obj("thinking" -> semantic) // 既有 budget 形态逐字保留
      case ThinkingKind.EffortAnthropic =>
        // 官方 anthropic 面：thinking 支持（budget_tokens 被忽略）→ 只发开关位。
        val base = Json.obj("thinking" -> Json.obj("type" -> "enabled".asJson))
        ThinkingEffortGate
          .effortFragment(kind, params.model, params.thinkingResolved.flatMap(_.effort)) match
          case Some(extra) => base.deepMerge(extra)
          case None => base
      case ThinkingKind.Toggle =>
        // 开关型：只发 enabled（budget_tokens 不再上线——kimi-for-coding 家族
        // 对非 enabled 形态 400，glm 的 budget 处置未核实，少发永远更安全）。
        val thinkingObj = scala.collection.mutable.ListBuffer.empty[(String, Json)]
        thinkingObj += ("type" -> "enabled".asJson)
        // GLM 保留式思考位：仅显式配置时上线（clear_thinking:false = 开启保留）。
        params.thinkingResolved.flatMap(_.clearThinking).foreach { c =>
          thinkingObj += ("clear_thinking" -> c.asJson)
        }
        val base = Json.obj("thinking" -> Json.obj(thinkingObj.toList*))
        // glm 族 effort 腿（门开时）：顶层 reasoning_effort（body 级，非 thinking 键内）。
        ThinkingEffortGate
          .effortFragment(kind, params.model, params.thinkingResolved.flatMap(_.effort)) match
          case Some(extra) => base.deepMerge(extra)
          case None => base
      case ThinkingKind.EffortTopLevel =>
        // K3：不发 thinking 配置（官方明示移除）；effort 核定后走顶层
        // reasoning_effort（第二段点亮腿）。
        ThinkingEffortGate
          .effortFragment(kind, params.model, params.thinkingResolved.flatMap(_.effort)) match
          case Some(extra) => extra
          case None => Json.obj()

  end onWire

  /**
   * `max_tokens` for this face (maxcfg batch 2026-09-16; clamp batch
   * 2026-10-21, author ruling 「总输出上限改为128000」).
   *
   * The Anthropic API REQUIRES `max_tokens`, and additionally requires
   * `budget_tokens < max_tokens`. The output cap is not user configurable
   * (the `models[].maxTokens` config key was removed), so the value is
   * derived here, in three legs:
   *
   *  1. **Override leg** — `maxTokensOverride` (engine-internal, auxiliary
   *     faces only: probe / small-judge / allocator) is sent verbatim. Those
   *     faces pin `Defaults.ProbeMaxTokens` (16384, the pre-raise proven
   *     value) and must stay byte-identical to pre-batch requests, so the
   *     override deliberately bypasses every clamp below.
   *  2. **Raise leg (unchanged in form)** — base = `Defaults.MaxTokens`
   *     (128000), raised to `budget + Defaults.MaxTokens` when thinking is
   *     on, keeping the API invariant `budget_tokens < max_tokens` with a
   *     FULL output allowance above the budget.
   *  3. **Clamp legs (new)** — the constant is never sent blindly to an
   *     endpoint that would 400 it:
   *       - family cap: models with a documented smaller real output cap
   *         (glm family → 32768) are bounded below the constant;
   *       - window cap: `contextWindow − (estimated input + 8192 headroom)`,
   *         floored at 16384 — the output allowance must leave room for the
   *         input that shares the same window.
   *     After both, the thinking invariant is re-asserted as a hard floor
   *     (`budget + 1`): a clamp that would make `max_tokens ≤ budget_tokens`
   *     turns a truncation risk into a guaranteed 400, so the invariant wins.
   *
   * The clamp is best-effort by design: family caps are conservative floors
   * (under-clamping only shortens output, never 400s), and the window leg is
   * an estimate (`TokenEstimator`, compaction-grade). Engine-side values take
   * effect on the next restart window.
   */
  private[providers] def maxTokensFor(params: SendMessageParams): Int =
    params.maxTokensOverride match
      case Some(fixed) => fixed
      case None =>
        val budget = params.thinking.flatMap(_.hcursor.get[Int]("budget_tokens").toOption).getOrElse(0)
        val base = math.max(Defaults.MaxTokens, budget + Defaults.MaxTokens)
        val clamped = familyOutputCap(params.model) match
          case Some(family) => math.min(base, family)
          case None => base
        val windowClamped = params.contextWindow match
          case Some(cw) => math.min(clamped, windowOutputCap(cw, params))
          case None => clamped
        // Hard API invariant: budget_tokens < max_tokens (see doc, leg 3).
        if budget > 0 then math.max(windowClamped, budget + 1) else windowClamped

  /**
   * Known per-family output caps — CONSERVATIVE floors, not vendor truth:
   * every entry must be ≤ the smallest documented output cap across the
   * family's current SKUs, because the failure being prevented (HTTP 400 on
   * an oversized `max_tokens`) is an outage, while the cost of under-clamping
   * (output stops early, `MaxTokensStubText` closing text) is routine. Add an
   * entry only with verified vendor documentation; a missing entry degrades
   * to the window leg + smoke tests, a wrong entry silently caps outputs.
   */
  private val FamilyOutputCaps: List[(String, Int)] = List(
    // glm family: zhipu documents output caps well below 128000 across SKUs
    // (GLM-4.x flash tiers ≤ 8K, 4.5/4.6 ≥ 96K — 32768 is safe on all of them
    // and still clears MaxThinkingBudget-class budgets via the floor leg).
    "glm" -> 32768
  )

  private def familyOutputCap(model: String): Option[Int] =
    val m = model.toLowerCase
    FamilyOutputCaps.collectFirst { case (needle, cap) if m.contains(needle) => cap }

  /**
   * Window leg: the input shares the context window with the output, so the
   * output allowance is `window − estimated input − headroom`, never below
   * [[WindowFloorTokens]]. The floor equals the pre-raise serving cap — on a
   * window too small for any meaningful output the request degrades to
   * exactly the pre-batch wire shape instead of inventing a new failure mode.
   * (Same number as `Defaults.ProbeMaxTokens` on purpose: both are "the last
   * proven-legal small cap". If the two roles ever need to diverge, revisit
   * this line consciously — do not silently split the constant.)
   */
  private def windowOutputCap(contextWindow: Int, params: SendMessageParams): Int =
    val reserve = estimateInputTokens(params) + InputHeadroomTokens
    math.max(contextWindow - reserve, WindowFloorTokens)

  /**
   * Compaction-grade input estimate (chars/4, CJK-weighted — `TokenEstimator`)
   * over messages + system parts + tool schemas. Tool schemas are near-pure
   * Latin JSON: raw chars/4 is adequate inside the headroom margin.
   */
  private def estimateInputTokens(params: SendMessageParams): Int =
    val systemText = params.systemStable.getOrElse("") + params.systemDynamic.getOrElse("")
    val toolsChars = params.tools.foldLeft(0)((acc, ts) => acc + ts.map(t => t.inputSchema.asJson.noSpaces.length).sum)
    TokenEstimator.estimate(params.messages) +
      (TokenEstimator.estimateWeightedChars(systemText) / 4) +
      (toolsChars / 4)

  /** Output allowance reserved for the input-side estimate error margin. */
  private val InputHeadroomTokens = 8192

  /** Window-leg floor — the pre-raise serving cap (see [[windowOutputCap]]). */
  private val WindowFloorTokens = Defaults.ProbeMaxTokens

  /** 请求体裁剪：engine 侧唯一的 body 装配点（两发送面共用同一形状）。 */
  private def bodyOf(params: SendMessageParams, streaming: Boolean): Json =
    val systemBlocks = buildSystemBlocks(params)
    val base = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toAnthropicMessages(params.messages)),
      "max_tokens" -> maxTokensFor(params).asJson
    )
    val withStream = if streaming then base.deepMerge(Json.obj("stream" -> true.asJson)) else base
    val withSystem =
      if systemBlocks != Json.Null then withStream.deepMerge(Json.obj("system" -> systemBlocks))
      else withStream
    val withTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => withSystem.deepMerge(Json.obj("tools" -> toAnthropicTools(tools)))
      case None => withSystem
    // thinking-config 批（2026-10-28）+ protocol-compat 批（2026-11-01）：
    // wire 翻译单点——按能力类发参数/省略键/显式关态（[[wireThinkingFragment]]）。
    val withThinking = withTools.deepMerge(wireThinkingFragment(params))
    (params.sessionId, params.agentId) match
      case (Some(sid), Some(aid)) =>
        withThinking.deepMerge(
          Json.obj(
            "metadata" -> Json.obj("session_id" -> sid.asJson, "agent_id" -> aid.asJson)
          )
        )
      case (Some(sid), _) => withThinking.deepMerge(Json.obj("metadata" -> Json.obj("session_id" -> sid.asJson)))
      case (_, Some(aid)) => withThinking.deepMerge(Json.obj("metadata" -> Json.obj("agent_id" -> aid.asJson)))
      case _              => withThinking

  /**
   * 降级重试用的参数：**显式离开思考模式**。
   *
   * 只在"上游 400 且明确要求回传思考块"时使用（[[WireShape
   * .isThinkingPassbackRejection]]）。`thinkingResolved.enabled=false` 让
   * [[wireThinkingOn]] 为假 ⇒ 走该族的关态（EffortAnthropic → disabled），
   * 这是我们实测唯一能让 DeepSeek 类端点放弃回传要求的形态。
   */
  private def thinkingOffParams(params: SendMessageParams): SendMessageParams =
    params.copy(
      thinkingResolved = Some(
        params.thinkingResolved
          .getOrElse(ResolvedThinking(ThinkingFamily.forModel(params.model)))
          .copy(enabled = false)
      )
    )

  /** 形状类失败：带 provider/model + 请求形状 + 响应形状的 HttpError。 */
  private def wireError(status: Int, body: String, params: SendMessageParams): sttp.client4.HttpError[String] =
    new sttp.client4.HttpError(
      s"${WireShape.diagnose(providerId, params.model, params.messages, Some(status), body)} " +
        s"| upstream=${WireShape.upstreamExcerpt(body)}${edgeHint(status, body)} " +
        s"| raw=${Option(body).getOrElse("").take(600)}",
      sttp.model.StatusCode(status)
    )

  /**
   * 本请求的头装配（唯一一点 = [[HttpHeaders]]）：引擎缺省（含必带 UA）+
   * provider 级覆盖 + anthropic 面协议头。auth 头也经这里，保证"改头只改一处"。
   */
  private def applyHeaders[B](req: sttp.client4.Request[B]): sttp.client4.Request[B] =
    HttpHeaders.applyTo(
      req.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01"),
      providerHeaders
    )

  /**
   * 边缘拦截（WAF）专用：鉴权失败长得一样，但处置完全不同——UA 问题是**我们
   * 的请求头错了**，不该判 provider 死。命中时把 statusCode 挪成 400 并把话说
   * 在诊断串里？不——那会掩盖真实状态码。这里只负责在诊断串尾部追加人话
   * （[[HttpHeaders.edgeBlockHint]]），状态码原样保留供 classifyError 归类。
   */
  private def edgeHint(status: Int, body: String): String =
    HttpHeaders.edgeBlockHint(status, body)

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    def attempt(p: SendMessageParams): IO[AdapterResponse] =
      val request = applyHeaders(
        basicRequest
          .post(uri"$endpoint")
          .header("content-type", "application/json")
          .body(bodyOf(p, streaming = false).noSpaces)
      )

      backend.send(request).flatMap { response =>
        if response.code.isClientError then
          val raw = response.body.fold(_.toString, identity)
          IO.raiseError(wireError(response.code.code, raw, p))
        else
          response.body match
            case Left(error) =>
              // 审计 20260903 子项②：非 2xx 升格为结构化 HttpError（带状态码）——
              // classifyError 的结构化分支据此区分 400 Format（不驱逐）与 Auth/404
              // 等确证死亡（驱逐）。旧 RuntimeException 文案无状态码，400 只能落
              // Unknown/Transient → 重试耗尽后照样 markDown（flap 主因）。
              IO.raiseError(sttp.client4.HttpError(error, response.code))
            case Right(bodyStr) =>
              IO.defer {
                parse(bodyStr) match
                  case Left(err) =>
                    IO.raiseError(
                      new RuntimeException(
                        s"Failed to parse response: ${err.message} | " +
                          WireShape.diagnose(providerId, p.model, p.messages, Some(response.code.code), bodyStr)
                      )
                    )
                  case Right(json) => IO.pure(parseNonStreamingResponse(json, p))
              }
      }

    // 单 provider 失败不许打死整批（作者令 17:45 第 3 条）：形状可自愈的一类
    // 失败先就地降级重试一次，再谈向上层抛（届时才轮到 fallback 换腿）。
    attempt(params).handleErrorWith {
      case e: sttp.client4.HttpError[?]
          if WireShape.isThinkingPassbackRejection(e.statusCode.code, Option(e.body).map(_.toString).getOrElse("")) =>
        nebflow.shared.NebflowLogger
          .forName("nebflow.llm.anthropic")
          .warn(
            s"thinking-passback rejection — degraded retry with thinking disabled: " +
              WireShape.routeLine(providerId, params.model)
          ) *> attempt(thinkingOffParams(params))
      case other => IO.raiseError(other)
    }

  end sendMessage

  /**
   * 非流式响应解析（**宽容口径**，protocol-compat 批 2026-11-01）。
   *
   * 旧实现按 `type == "text"` 精确筛块：provider 换一个块名（`output_text` /
   * `redacted_thinking` / 各家自造的 reasoning 变体）正文就整段消失，下游只
   * 看到空回复 → EmptyStream。现在正文收集对未知块走降级：带 `text` 的块一律
   * 算正文，判不出类型的块跳过（不抛错、不影响其它块）。
   *
   * 取舍：tool_use / image / thinking 仍走各自的结构化分支（思考块不算正文——
   * 它的语义是推理，混进 reply 会污染用户可见文本；由 ThinkingDelta 通道与
   * 面板折叠面消费）。
   */
  private[providers] def parseNonStreamingResponse(json: Json, params: SendMessageParams): AdapterResponse =
    val content = json.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    val toolUseBlocks = content.filter(_.hcursor.downField("type").as[String].toOption.contains("tool_use"))
    val textParts = content.flatMap { b =>
      val t = b.hcursor.downField("type").as[String].toOption
      if t.contains("thinking") || t.contains("redacted_thinking") || t.contains("tool_use") ||
        t.contains("image") || t.contains("tool_result")
      then None
      else
        b.hcursor.downField("text").as[String].toOption
          .orElse(b.hcursor.downField("content").focus.filter(_.isString).flatMap(_.asString))
    }

    val reply = textParts.mkString("")
    val toolCalls = toolUseBlocks.map { b =>
      val id = b.hcursor.downField("id").as[String].getOrElse("")
      val name = b.hcursor.downField("name").as[String].getOrElse("")
      // Non-streaming responses carry `input` as a JSON object; a non-object
      // input goes through the shared marker path instead of silently {}.
      val input = b.hcursor.downField("input").as[JsonObject] match
        case Right(obj) => obj
        case Left(_) =>
          ToolInputJson.malformedInput(
            b.hcursor.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
          )
      ToolCall(id, name, input)
    }

    val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
      val inputTokens = u.hcursor.downField("input_tokens").as[Int].getOrElse(0)
      val cacheRead = u.hcursor.downField("cache_read_input_tokens").as[Option[Int]].toOption.flatten
      val cacheWrite = u.hcursor.downField("cache_creation_input_tokens").as[Option[Int]].toOption.flatten
      val totalInput = inputTokens + cacheRead.getOrElse(0) + cacheWrite.getOrElse(0)
      TokenUsage(
        inputTokens = totalInput,
        outputTokens = u.hcursor.downField("output_tokens").as[Int].getOrElse(0),
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite
      )
    }

    // 可诊断（作者令 17:49）：正文与工具都空、但对方确实回了块 ⇒ 说清类型序列，
    // 别让下游/面板只看到一句空回复。
    if reply.isEmpty && toolCalls.isEmpty && !hasReadableThinking(content) then
      val kinds = content.map(b => b.hcursor.downField("type").as[String].toOption.getOrElse("?")).mkString(">")
      if kinds.nonEmpty then
        nebflow.shared.NebflowLogger
          .forName("nebflow.llm.anthropic")
          .warnSync(
            s"response produced no text/tool content: ${WireShape.routeLine(providerId, params.model)} " +
              s"contentTypes=$kinds request=${WireShape.requestShape(params.messages)}"
          )

    AdapterResponse(reply, toolCalls, usage)

  end parseNonStreamingResponse

  /** 响应里是否有任何可读的思考内容（含未知 reasoning 变体）。 */
  private[providers] def hasReadableThinking(content: List[Json]): Boolean =
    content.exists { b =>
      val t = b.hcursor.downField("type").as[String].toOption.getOrElse("")
      (t.contains("thinking") || t.contains("reasoning")) &&
      b.hcursor.downField("thinking").as[String].toOption
        .orElse(b.hcursor.downField("reasoning_content").as[String].toOption)
        .orElse(b.hcursor.downField("text").as[String].toOption)
        .exists(_.trim.nonEmpty)
    }

  def sendMessageStream(params: SendMessageParams): Stream[IO, StreamChunk] =
    def attempt(p: SendMessageParams): Stream[IO, StreamChunk] =
      Stream.eval(IO.ref(Map.empty[Int, (String, String, StringBuilder)])).flatMap { toolCallState =>
        Stream.eval(IO.ref(Tokens(0, None, None))).flatMap { tokenRef =>
          // 头装配在 .response/.readTimeout 之前完成（后两者返回 StreamRequest，
          // 不再是 Request[B]，header() 会丢响应体类型）。
          val request = applyHeaders(
            basicRequest
              .post(uri"$endpoint")
              .header("content-type", "application/json")
              .body(bodyOf(p, streaming = true).noSpaces)
          ).response(asStreamUnsafe(Fs2Streams[IO])).readTimeout(Defaults.LlmReadTimeoutSec.seconds)

          // Hard-recovery P1: per-attempt backend when provided (per-request
          // HttpClient whose shutdownNow aborts exactly this request).
          Stream.eval(p.attemptBackend.getOrElse(backend).send(request)).flatMap { response =>
            if response.code.isClientError then
              // 流式请求的 body 是字节流，错误页只在 Left 侧（sttp 对非 2xx 不建流）
              val raw = response.body.fold(_.toString, _ => "(stream body not read: non-2xx)")
              Stream.eval(IO.raiseError(wireError(response.code.code, raw, p)))
            else
              response.body match
                case Left(error) =>
                  // 同 sendMessage：结构化 HttpError 携带状态码（子项②）。
                  Stream.eval(IO.raiseError(sttp.client4.HttpError(error, response.code)))
                case Right(byteStream) =>
                  parseSseIncrementally(byteStream, toolCallState, tokenRef, p)
          }
        }
      }

    // 降级重试（作者令 17:45 第 3 条）：400 + 上游明说"思考必须回传" ⇒ 就地
    // 用 thinking:disabled 重试一次，别把整批判死。
    //
    // 接缝安全（seam guard）论证：该 400 在**响应头**就到了，`response.code`
    // 是 4xx ⇒ 在流的第一个元素就抛，任何一个 StreamChunk 都没发出去过。所以
    // 重试不可能产生"两家输出拼接"的重复内容（那正是 interface 层锁
    // lockedRef 防的场景）。测试 AnthropicPassbackRepairSpec 钉住"重试前零块"。
    attempt(params).handleErrorWith {
      case e: sttp.client4.HttpError[?]
          if WireShape.isThinkingPassbackRejection(e.statusCode.code, Option(e.body).map(_.toString).getOrElse("")) =>
        Stream.eval(
          nebflow.shared.NebflowLogger
            .forName("nebflow.llm.anthropic")
            .warn(
              s"thinking-passback rejection — degraded retry with thinking disabled: " +
                WireShape.routeLine(providerId, params.model)
            )
        ).drain ++ attempt(thinkingOffParams(params))
      case other => Stream.raiseError(other)
    }

  end sendMessageStream

  private def parseSseIncrementally(
    byteStream: Stream[IO, Byte],
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
    tokenRef: Ref[IO, Tokens],
    params: SendMessageParams
  ): Stream[IO, StreamChunk] =
    Stream.eval(IO.ref(Option.empty[String])).flatMap { eventTypeRef =>
      byteStream
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .filter(_.nonEmpty)
        .evalMap { line =>
          if line.startsWith("event:") then eventTypeRef.set(Some(line.drop(6).trim)).as(List.empty[StreamChunk])
          else if line.startsWith("data:") then
            val data = line.drop(5).trim
            eventTypeRef.getAndSet(None).flatMap {
              case Some(et) => processAnthropicEvent(et, data, toolCallState, tokenRef, params)
              // #256: this data frame carried no preceding `event:` line, so it is
              // dispatched with an empty event type and dropped — with a WARN — by
              // processAnthropicEvent's empty-event case.
              case None => processAnthropicEvent("", data, toolCallState, tokenRef, params)
            }
          else IO.pure(Nil)
        }
        .flatMap(cs => if cs.nonEmpty then Stream.emits(cs) else Stream.empty)
    }

  private[providers] def processAnthropicEvent(
    eventType: String,
    data: String,
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
    tokenRef: Ref[IO, Tokens],
    params: SendMessageParams
  ): IO[List[StreamChunk]] =
    if data == "[DONE]" then IO.pure(Nil)
    else
      parse(data) match
        case Left(err) =>
          // Observability (issue #18 follow-up): dropped SSE data used to be
          // invisible; log so malformed provider frames are diagnosable.
          nebflow.shared.NebflowLogger
            .forName("nebflow.llm.anthropic")
            .warn(s"dropped unparseable SSE data (${err.message}): ${data.take(120)}")
            .as(Nil)
        case Right(json) =>
          eventType match
            case "message_start" =>
              // Capture usage from message_start (message_delta only has output_tokens)
              val usageObj = json.hcursor.downField("message").downField("usage")
              val inputTokens = usageObj.downField("input_tokens").as[Int].getOrElse(0)
              val cacheRead = usageObj.downField("cache_read_input_tokens").as[Option[Int]].toOption.flatten
              val cacheWrite = usageObj.downField("cache_creation_input_tokens").as[Option[Int]].toOption.flatten
              nebflow.shared.NebflowLogger
                .forName("nebflow.llm.anthropic")
                .infoSync(
                  s"message_start: model=${params.model} usage_json=${usageObj.as[Json].getOrElse(Json.Null).noSpaces} inputTokens=$inputTokens cacheRead=$cacheRead cacheWrite=$cacheWrite"
                )
              tokenRef.set(Tokens(inputTokens, cacheRead, cacheWrite)).as(Nil)
            case "content_block_delta" =>
              json.hcursor.downField("delta").downField("type").as[String].toOption match
                case Some("text_delta") =>
                  val text = json.hcursor.downField("delta").downField("text").as[String].getOrElse("")
                  IO.pure(if text.nonEmpty then List(StreamChunk.TextDelta(text)) else Nil)
                case Some("thinking_delta") =>
                  val thinking = json.hcursor.downField("delta").downField("thinking").as[String].getOrElse("")
                  IO.pure(if thinking.nonEmpty then List(StreamChunk.ThinkingDelta(thinking)) else Nil)
                case Some("input_json_delta") =>
                  val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                  val partial = json.hcursor.downField("delta").downField("partial_json").as[String].getOrElse("")
                  toolCallState.modify { m =>
                    m.get(idx) match
                      case Some((id, name, sb)) =>
                        val updated = m.updated(idx, (id, name, sb.append(partial)))
                        val chunks = if partial.nonEmpty then List(StreamChunk.ToolArgDelta(name, partial)) else Nil
                        (updated, chunks)
                      case None => (m, Nil)
                  }
                case Some("signature_delta") =>
                  val sig = json.hcursor.downField("delta").downField("signature").as[String].getOrElse("")
                  IO.pure(if sig.nonEmpty then List(StreamChunk.ThinkingSignature(sig)) else Nil)
                case other =>
                  // 未知 delta 类型**不许丢正文**（protocol-compat 批 2026-11-01）。
                  // provider 家目录是开放集（`reasoning_delta` / `output_text_delta`
                  // / `summary_delta` / 各家自造派生名），引擎映射表是封闭的。
                  // 旧实现一律 `IO.pure(Nil)`：只要对方改了 delta 名，正文整段
                  // 静默消失 → 下游只见 "Stream completed with no content"。
                  // 现在按内容字段降级：带 text → TextDelta，带
                  // thinking/reasoning* → ThinkingDelta，都不带才真丢（记 WARN）。
                  val delta = json.hcursor.downField("delta")
                  val degraded = WireShape.classifyBlock(delta.focus.getOrElse(Json.obj())) match
                    case WireShape.Block.Text(t)      => List(StreamChunk.TextDelta(t))
                    case WireShape.Block.Thinking(t, _) => List(StreamChunk.ThinkingDelta(t))
                    case WireShape.Block.Opaque(_) =>
                      nebflow.shared.NebflowLogger
                        .forName("nebflow.llm.anthropic")
                        .warnSync(
                          s"skipped unmodelled delta type '${other.getOrElse("(absent)")}' with no text/reasoning " +
                            s"payload: ${WireShape.routeLine(providerId, params.model)}"
                        )
                      Nil
                  IO.pure(degraded)
            case "content_block_start" =>
              json.hcursor.downField("content_block").downField("type").as[String].toOption match
                case Some("tool_use") =>
                  val id = json.hcursor.downField("content_block").downField("id").as[String].getOrElse("")
                  val name = json.hcursor.downField("content_block").downField("name").as[String].getOrElse("")
                  val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                  toolCallState
                    .update(_ + (idx -> (id, name, new StringBuilder)))
                    .as(List(StreamChunk.ToolCallStart(name)))
                case Some("thinking") | Some("redacted_thinking") =>
                  // Some providers (DeepSeek) send empty signature in content_block_start
                  // and the real signature later via signature_delta. Filter out empty to avoid
                  // collectFirst in aggregateChunks picking the useless empty string.
                  // redacted_thinking：签名/密文在 `data` 字段，思考正文不可读——只
                  // 取签名位，不伪造正文（protocol-compat 批：认得它，别丢它）。
                  val block = json.hcursor.downField("content_block")
                  val sig = block.downField("signature").as[String].toOption.filter(_.nonEmpty)
                  val text = block.downField("thinking").as[String].toOption.filter(_.trim.nonEmpty)
                  IO.pure(
                    sig.map(s => List(StreamChunk.ThinkingSignature(s))).getOrElse(Nil) ++
                      text.map(t => List(StreamChunk.ThinkingDelta(t))).getOrElse(Nil)
                  )
                case other =>
                  // 未知块类型：带正文就以 TextDelta 送出（保内容），
                  // 其余跳过——**不抛错、不影响后续帧**。
                  WireShape.classifyBlock(json.hcursor.downField("content_block").focus.getOrElse(Json.obj())) match
                    case WireShape.Block.Text(t)        => IO.pure(List(StreamChunk.TextDelta(t)))
                    case WireShape.Block.Thinking(t, s) =>
                      IO.pure(StreamChunk.ThinkingDelta(t) :: s.toList.map(StreamChunk.ThinkingSignature.apply))
                    case WireShape.Block.Opaque(k) =>
                      nebflow.shared.NebflowLogger
                        .forName("nebflow.llm.anthropic")
                        .warnSync(
                          s"skipped unmodelled content block type '$k' (no text/reasoning payload): " +
                            WireShape.routeLine(providerId, params.model)
                        )
                      IO.pure(Nil)
            case "content_block_stop" =>
              val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
              toolCallState.modify { m =>
                m.get(idx) match
                  case Some((id, name, sb)) =>
                    // Issue #18: an unparseable argument stream (e.g. GLM's
                    // unquoted ISO-8601 triggerAt) used to be coerced to {}
                    // here, silently. ToolInputJson repairs what it can and
                    // marks the rest so executeTool reports it to the LLM.
                    (
                      m - idx,
                      List(
                        StreamChunk.ToolCallChunk(ToolCall(id, name, ToolInputJson.parseToolInput(name, sb.toString)))
                      )
                    )
                  case None => (m, Nil)
              }
            case "message_delta" =>
              val stopReason = json.hcursor.downField("delta").downField("stop_reason").as[String].toOption
              val deltaUsage = json.hcursor.downField("usage")
              val outputTokens = deltaUsage.downField("output_tokens").as[Int].getOrElse(0)
              // Some Anthropic-compatible providers (Zhipu, DeepSeek) report input_tokens in
              // message_delta instead of (or in addition to) message_start.
              // Prefer message_delta's input_tokens when available and non-zero.
              val deltaInput = deltaUsage.downField("input_tokens").as[Int].toOption
              val deltaCacheRead = deltaUsage.downField("cache_read_input_tokens").as[Option[Int]].toOption.flatten
              val deltaCacheWrite = deltaUsage.downField("cache_creation_input_tokens").as[Option[Int]].toOption.flatten
              tokenRef.get.map { t =>
                val inputTokens = deltaInput.filter(_ > 0).getOrElse(t.input)
                val cacheRead = deltaCacheRead.orElse(t.cacheRead)
                val cacheWrite = deltaCacheWrite.orElse(t.cacheWrite)
                val totalInput = inputTokens + cacheRead.getOrElse(0) + cacheWrite.getOrElse(0)
                nebflow.shared.NebflowLogger
                  .forName("nebflow.llm.anthropic")
                  .infoSync(
                    s"message_delta: model=${params.model} deltaInput=$deltaInput stored_input=${t.input} final_input=$inputTokens cacheRead=$cacheRead cacheWrite=$cacheWrite totalInput=$totalInput outputTokens=$outputTokens stopReason=$stopReason"
                  )
                val usage = Some(
                  TokenUsage(
                    inputTokens = totalInput,
                    outputTokens = outputTokens,
                    cacheReadTokens = cacheRead,
                    cacheWriteTokens = cacheWrite
                  )
                )
                val meta = LlmMeta(
                  sessionId = params.sessionId.getOrElse(""),
                  agentId = params.agentId.getOrElse(""),
                  providerId = "anthropic",
                  model = params.model,
                  durationMs = 0
                )
                List(StreamChunk.Done(stopReason, usage, Some(meta), None))
              }
            case "" =>
              // #256 (author ruling 2026-09-13): silent-drop path. A frame whose
              // `event:` line is missing (or empty) reaches here as an empty event
              // type and used to be discarded with zero trace — the frame's content
              // is lost and nothing is logged. WARN once with locatable metadata
              // only; the raw wire payload is never logged (R1b is deliberately out
              // of scope). Return semantics are unchanged: still Nil, and every
              // other event type still falls through to `case _ => Nil` below.
              val frameType =
                json.hcursor.downField("type").as[String].toOption.map(_.take(40)).getOrElse("(absent)")
              nebflow.shared.NebflowLogger
                .forName("nebflow.llm.anthropic")
                .warn(
                  "dropped SSE frame with empty event type (data line without a preceding 'event:' line): " +
                    s"frame_type=$frameType payloadChars=${data.length} model=${params.model} " +
                    s"sessionId=${params.sessionId.getOrElse("(none)")} agentId=${params.agentId.getOrElse("(none)")}"
                )
                .as(Nil)
            case _ => IO.pure(Nil)
end AnthropicAdapter

/**
 * Model-list endpoints of the Anthropic face — declared here, not derived by
 * the gateway from a raw `baseUrl` (see `ModelListFaces` for the rule and
 * `RestApiRoutes.probeModelList` for how candidates are probed).
 *
 *   1. `{base}/v1/models` — this face appends the version segment itself for
 *      chat (`{base}/v1/messages`, see `endpoint` above), so the list lives
 *      under the same segment. Measured 200 with the real catalogues on kimi
 *      and zhipu.
 *   2. `{origin}/v1/models` — the vendor's version root, declared for hosts
 *      whose chat prefix carries a non-version segment and serves no list
 *      under it: measured 200 on deepseek, where `/anthropic/v1/models` is 404
 *      while `https://api.deepseek.com/v1/models` answers the catalogue.
 *
 * A `baseUrl` with no path segment (e.g. `https://api.example.com`) declares
 * one candidate only — there the two forms coincide.
 */
object AnthropicAdapter:

  def modelListUrls(baseUrl: String): List[String] =
    val base = baseUrl.replaceAll("/+$", "")
    val own = s"$base/v1/models"
    val origin = originOf(base)
    if origin.isEmpty then List(own) else List(own, s"$origin/v1/models")

  /**
   * `scheme://host[:port]` of an absolute URL, or "" when it carries no path
   * segment (the second candidate would then duplicate the first).
   */
  private def originOf(baseUrl: String): String =
    val schemeEnd = baseUrl.indexOf("://")
    if schemeEnd < 0 then ""
    else
      val afterScheme = baseUrl.substring(schemeEnd + 3)
      val slash = afterScheme.indexOf('/')
      if slash < 0 then "" else baseUrl.substring(0, schemeEnd + 3 + slash)
end AnthropicAdapter
