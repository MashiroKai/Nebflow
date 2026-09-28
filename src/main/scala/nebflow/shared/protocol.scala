package nebflow.shared

import io.circe.*
import io.circe.syntax.*

// ===== Content Blocks =====

sealed trait ContentBlock:
  def `type`: String

object ContentBlock:

  case class Text(text: String) extends ContentBlock:
    val `type` = "text"

  case class Image(data: String, mediaType: String) extends ContentBlock:
    val `type` = "image"

  case class ToolUse(id: String, name: String, input: JsonObject) extends ContentBlock:
    val `type` = "tool_use"

  case class ToolResult(toolUseId: String, content: String, isError: Option[Boolean] = None) extends ContentBlock:
    val `type` = "tool_result"

  case class Thinking(thinking: String, signature: Option[String] = None) extends ContentBlock:
    val `type` = "thinking"
end ContentBlock

// ===== Message =====

enum MessageRole:
  case System, User, Assistant

  def name: String = this match
    case System => "system"
    case User => "user"
    case Assistant => "assistant"

object MessageRole:

  def fromString(s: String): Option[MessageRole] = s match
    case "system" => Some(System)
    case "user" => Some(User)
    case "assistant" => Some(Assistant)
    case _ => None

case class Message(
  role: MessageRole,
  content: Either[String, List[ContentBlock]],
  timestamp: Long = System.currentTimeMillis(),
  /**
   * Injection source marker (任务 P): when a message was injected by a tool
   * rather than typed by the user, this records where it came from —
   * "mail" / "delegate" / "subtask" / "flow" / "skill" / "tool".
   * None = normal user-typed message (backward compatible: old persisted
   * messages decode with source=None).
   */
  source: Option[String] = None
):

  def textContent: String = content match
    case Left(text) => text
    case Right(blocks) =>
      blocks.collect { case ContentBlock.Text(t) => t }.mkString("\n")
end Message

// ===== Tool Definition =====

case class ToolDefinition(
  name: String,
  description: String,
  inputSchema: JsonObject
)

// ===== Tool Call =====

/**
 * Raw wire-format arguments string as received from the provider, kept
 * byte-faithful alongside the parsed JsonObject. Needed by provider-native
 * round-trip semantics (kimi $web_search: the caller must echo the model's
 * arguments back verbatim as the tool result — re-serializing the parsed
 * object would break byte-identity, and rescued/malformed inputs would
 * degrade to "{}").
 */
case class ToolCall(id: String, name: String, input: JsonObject, rawArguments: Option[String] = None)

// ===== LLM =====

case class FallbackStep(
  providerId: String,
  model: String,
  reason: Option[String],
  durationMs: Long
)

case class LlmRequest(
  messages: List[Message],
  sessionId: String,
  agentId: String,
  tools: Option[List[ToolDefinition]] = None,
  thinking: Option[io.circe.Json] = None,
  /** Stable system prompt (e.g. system.md) — can be cached by the provider. */
  systemStable: Option[String] = None,
  /** Dynamic system content (env info, reminders) — changes frequently, placed after cache breakpoint. */
  systemDynamic: Option[String] = None,
  /**
   * Per-agent model configuration (preferred + fallbacks). When set, the
   * candidate chain is built from this instead of the global model chain.
   */
  agentModel: Option[AgentModelConfig] = None,
  /**
   * WebSearch P0: provider-native search injection is allowed for this
   * request. Housekeeping turns (compaction / save-turn) and
   * maintenance LLM calls set this to false so a server-side search tool
   * never leaks into summarization or memory-extraction requests.
   */
  searchAllowed: Boolean = true
)

case class TokenUsage(
  inputTokens: Int,
  outputTokens: Int,
  cacheReadTokens: Option[Int] = None,
  cacheWriteTokens: Option[Int] = None
)

case class LlmMeta(
  sessionId: String,
  agentId: String,
  providerId: String,
  model: String,
  durationMs: Long,
  fallbackChain: Option[List[FallbackStep]] = None,
  contextWindow: Option[Int] = None
)

case class LlmResponse(
  reply: String,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage],
  meta: LlmMeta,
  /**
   * WebSearch P0: structured search results from a provider-native search
   * (zhipu `web_search` response field / qwen `search_info`), when present.
   * None on providers/paths without structured search output.
   */
  searchInfo: Option[Json] = None
)

case class LlmOptions(configPath: Option[String] = None)

// ===== Stream Chunk =====

sealed trait StreamChunk

object StreamChunk:
  case class TextDelta(delta: String) extends StreamChunk
  case class ThinkingDelta(delta: String) extends StreamChunk
  case class ThinkingSignature(signature: String) extends StreamChunk

  /** Emitted as soon as the LLM starts streaming a tool_use block (name known, input still streaming). */
  case class ToolCallStart(name: String) extends StreamChunk

  /** Emitted for each partial argument fragment while the LLM is still generating tool call input. */
  case class ToolArgDelta(toolName: String, delta: String) extends StreamChunk
  case class ToolCallChunk(toolCall: ToolCall) extends StreamChunk

  case class Done(
    stopReason: Option[String],
    usage: Option[TokenUsage],
    meta: Option[LlmMeta] = None,
    contextWindow: Option[Int] = None
  ) extends StreamChunk
end StreamChunk

// ===== LLM Handle =====

trait LlmHandle[F[_]]:
  def send(req: LlmRequest): F[LlmResponse]

  def sendStream(
    req: LlmRequest,
    onAttempt: Option[FallbackAttempt => cats.effect.IO[Unit]] = None
  ): fs2.Stream[F, StreamChunk]

// ===== Circe Codecs =====

given Encoder[MessageRole] = Encoder.encodeString.contramap(_.name)
given Decoder[MessageRole] = Decoder.decodeString.emap(s => MessageRole.fromString(s).toRight(s"Unknown role: $s"))

given Encoder[ContentBlock] = Encoder.instance {
  case ContentBlock.Text(text) => Json.obj("type" -> "text".asJson, "text" -> text.asJson)
  case ContentBlock.Image(data, mediaType) =>
    Json.obj("type" -> "image".asJson, "data" -> data.asJson, "mediaType" -> mediaType.asJson)
  case ContentBlock.ToolUse(id, name, input) =>
    Json.obj(
      "type" -> "tool_use".asJson,
      "id" -> id.asJson,
      "name" -> name.asJson,
      "input" -> Json.fromJsonObject(input)
    )
  case ContentBlock.ToolResult(toolUseId, content, isError) =>
    val base = Json.obj("type" -> "tool_result".asJson, "toolUseId" -> toolUseId.asJson, "content" -> content.asJson)
    isError.fold(base)(e => base.deepMerge(Json.obj("isError" -> e.asJson)))
  case ContentBlock.Thinking(thinking, signature) =>
    val base = Json.obj("type" -> "thinking".asJson, "thinking" -> thinking.asJson)
    signature.fold(base)(s => base.deepMerge(Json.obj("signature" -> s.asJson)))
}

given Decoder[ContentBlock] = Decoder.instance { cursor =>
  cursor.downField("type").as[String].flatMap {
    case "text" => cursor.downField("text").as[String].map(ContentBlock.Text(_))
    case "image" =>
      for data <- cursor.downField("data").as[String]; mt <- cursor.downField("mediaType").as[String]
      yield ContentBlock.Image(data, mt)
    case "tool_use" =>
      for
        id <- cursor.downField("id").as[String]; name <- cursor.downField("name").as[String];
        input <- cursor.downField("input").as[JsonObject]
      yield ContentBlock.ToolUse(id, name, input)
    case "tool_result" =>
      for
        id <- cursor.downField("toolUseId").as[String]; content <- cursor.downField("content").as[String];
        isError <- cursor.downField("isError").as[Option[Boolean]]
      yield ContentBlock.ToolResult(id, content, isError)
    case "thinking" =>
      for
        thinking <- cursor.downField("thinking").as[String]
        signature <- cursor.downField("signature").as[Option[String]]
      yield ContentBlock.Thinking(thinking, signature)
    case other => Left(DecodingFailure(s"Unknown content block type: $other", cursor.history))
  }
}

given Encoder[Message] = Encoder.instance { msg =>
  val base = msg.content match
    case Left(text) => Json.obj("role" -> msg.role.name.asJson, "content" -> text.asJson)
    case Right(blocks) => Json.obj("role" -> msg.role.name.asJson, "content" -> blocks.asJson, "blocks" -> true.asJson)
  val withTs = if msg.timestamp > 0 then base.deepMerge(Json.obj("timestamp" -> msg.timestamp.asJson)) else base
  msg.source.fold(withTs)(s => withTs.deepMerge(Json.obj("source" -> s.asJson)))
}

given Decoder[Message] = Decoder.instance { cursor =>
  for
    role <- cursor
      .downField("role")
      .as[String]
      .flatMap(s => MessageRole.fromString(s).toRight(DecodingFailure(s"Unknown role: $s", cursor.history)))
    isBlocks <- cursor.downField("blocks").as[Option[Boolean]]
    content <- isBlocks match
      case Some(true) => cursor.downField("content").as[List[ContentBlock]].map(Right(_))
      case _ => cursor.downField("content").as[String].map(Left(_))
    ts <- cursor.downField("timestamp").as[Option[Long]]
    source <- cursor.downField("source").as[Option[String]]
  yield Message(role, content, ts.getOrElse(0L), source)
}

// ===== UI Messages (for frontend rendering) =====

sealed trait UiMessage:
  def typeName: String

object UiMessage:

  case class User(
    text: String,
    attachments: List[Json] = Nil,
    injected: Boolean = false,
    timestamp: Long = 0L,
    source: Option[String] = None,
    eventType: Option[String] = None,
    sender: Option[String] = None,
    senderTeam: Option[String] = None,
    delivery: Option[String] = None,
    /**
     * 收件通道判别（mailbadge 批 2026-09-13，选项 C）：与
     * [[nebflow.agent.InjectionAttribution.intake]] 同一批名字（帧 ↔ 落盘同源）。
     * 前端历史恢复路径靠它重建注入气泡标签（缺席 ⇒ 回落 `source` 表，
     * 旧历史行渲染逐字节不变）。
     */
    intake: Option[String] = None,
    /**
     * **已渲染的四段式 header**（「气泡四段式统一」批 2026-09-15，作者 12:33 令）：
     * 引擎侧唯一格式化函数 `nebflow.core.project.NotificationHeader` 在唯一发射点
     * （`AgentActor#emitInjectedUserEvent`）产出的整串 `KIND · PROJECT · SUBJECT ·
     * STATE` 随注入帧与 .ui.json **同源落盘** ⇒ live 渲染与历史恢复逐字节一致。
     * 缺席（旧历史行 / 词表外 source）⇒ 前端回落 `injectedSourceLabel`（逐字节不变）。
     */
    header: Option[String] = None,
    /**
     * **作答行的显式来源标记**（双开缺陷批「案 B」2026-09-21，chain-askuserdup）：
     * 值 = 本条 user 行所**作答的那个 requestId** —— 即「这是一次卡片作答」的**数据**
     * 证据（不再靠「askUser 条目后面第一条 user 行」的邻接启发式）。
     *
     * WHY：卡片作答落盘原本是一条无标记的裸 `User(answerText)`，前端重建历史时必须
     * 用 run 启发式猜（`persistence.js askUserAnswerText`：遇第一个非 user 行即
     * break）。非阻塞提问（pending 期间 agent 仍产出 ai/tool 行 ⇒ run 被截断 ⇒ 取样
     * null）与「作答后继续打字」两种形态下取样会偏 —— 取样 null 又被
     * `renderAskUserHistory` 渲染成「已作答」（无条件补 `.option-answer`）⇒ 重放腿
     * 的去重判据被击穿 ⇒ 同 id 双卡（首卡死）。同一处缺口也是 uiclean 批登记项
     * 「#272 残余边界」的可见后果 ⇒ 本条字段一次关掉三件事：双开、#272 取值、
     * 「历史卡不可按 id 关闭」。
     *
     * 缺席即不落键（`.ui.json` 旧行字节形态与旧读法**逐字不变**）：旧行无标记 ⇒
     * 前端**必须回落「未作答」**（禁把历史一律读成「已作答」，方向见
     * `persistence.js askUserAnswerText`）。单一构造点 = [[UiMessage.askUserAnswer]]。
     */
    answerOf: Option[String] = None
  ) extends UiMessage:
    val typeName = "user"

  end User

  case class Ai(
    text: String,
    durationMs: Option[Long] = None,
    model: Option[String] = None,
    thinking: Option[String] = None,
    timestamp: Long = 0L
  ) extends UiMessage:
    val typeName = "ai"

  case class Tool(
    label: String,
    summary: String,
    content: String = "",
    isError: Boolean = false,
    input: String = ""
  ) extends UiMessage:
    val typeName = "tool"

  case class Agent(
    agentId: String,
    text: String,
    /**
     * R1 数据面（2026-09-14 作者七答「footer 统一 · R1 = 带时间」）：历史行的
     * footer 时间需要**落盘的真实时间戳**（live 行传 `Date.now()`、历史行原先传 `0`
     * ⇒ 刷新后时间消失）。缺省 0 = 不落键（`.ui.json` 旧行字节形态与旧读法逐字不变）。
     */
    timestamp: Long = 0L
  ) extends UiMessage:
    val typeName = "agent"

  /**
   * 落盘的 AskUser 提问行。
   *
   * `requestId`（双开缺陷批「案 B」2026-09-21，chain-askuserdup）：提问卡在 hub 里的
   * 唯一身份，随行落盘 ⇒ 历史恢复出的卡**可 id 寻址**（重放腿按 id 替换、`askUserClosed`
   * 关卡可达、#272 取值由数据决定）。
   *
   * 改前只落 `{type, items}` ⇒ 历史卡永远无 `data-request-id`：前端既无法按 id 去重
   * （只能看「卡上有没有作答行」的 DOM 启发式，被伪造的作答行击穿），引擎也关不掉它
   * （`chat.js closeAskUserCard` 只认 `[data-request-id]`）。
   *
   * 缺席即不落键（旧 `.ui.json` 行字节形态与旧读法逐字不变；旧行 ⇒ 前端回落
   * 「按形态兜底」的去重腿，见 `chat.js sameAskCards`）。
   */
  case class AskUser(items: List[Json], requestId: Option[String] = None) extends UiMessage:
    val typeName = "askUser"

  case class Ask(
    question: String,
    answer: String,
    durationMs: Option[Long] = None,
    model: Option[String] = None,
    /**
     * R1 数据面：同 [[Agent.timestamp]] —— ask 行（问句 + 答案）历史 footer 的
     * 时间取自此字段；缺省 0 = 不落键（旧行读法不变）。
     */
    timestamp: Long = 0L
  ) extends UiMessage:
    val typeName = "ask"

  case class AskPermission(
    toolName: String,
    summary: String,
    input: String,
    sourceAgent: Option[String] = None,
    sourceSession: Option[String] = None
  ) extends UiMessage:
    val typeName = "askPermission"

  case class System(content: String, i18nKey: Option[String] = None, params: Option[Json] = None) extends UiMessage:
    val typeName = "system"

  /**
   * 卡片作答的**落盘行构造单点**（双开缺陷批「案 B」2026-09-21，chain-askuserdup）。
   *
   * 唯一消费者 = `WebSocketRoutes` 的 `askUserAnswer` 帧处理（落盘 + 转发 hub）。
   * 收敛成一处的理由：行的形状（text = 各 answer 槽用 '\n' join + `answerOf` 标记 +
   * timestamp）与「这是本 requestId 的作答」这一语义必须**同源**——散在调用点手写
   * 就会再次漂移出无标记的行（正是本缺陷的成因面）。
   *
   * `requestId` 为空/空白 ⇒ `answerOf = None`（缺席即不落键）：无从标记来源时宁可
   * 不标记，让前端回落邻接启发式，而不是落一个空串把「有标记」的语义也污染掉。
   */
  def askUserAnswer(answerText: String, requestId: String, timestamp: Long): User =
    val rid = requestId.trim
    User(answerText, timestamp = timestamp, answerOf = if rid.isEmpty then None else Some(rid))

  given Encoder[UiMessage] = Encoder.instance {
    case m: User =>
      val base = Json.obj("type" -> "user".asJson, "text" -> m.text.asJson, "attachments" -> m.attachments.asJson)
      val withTs = if m.timestamp > 0 then base.deepMerge(Json.obj("timestamp" -> m.timestamp.asJson)) else base
      val withInj = if m.injected then withTs.deepMerge(Json.obj("injected" -> true.asJson)) else withTs
      val withSrc = m.source.fold(withInj)(s => withInj.deepMerge(Json.obj("source" -> s.asJson)))
      val withEt = m.eventType.fold(withSrc)(et => withSrc.deepMerge(Json.obj("eventType" -> et.asJson)))
      val withSender = m.sender.fold(withEt)(s => withEt.deepMerge(Json.obj("sender" -> s.asJson)))
      val withTeam = m.senderTeam.fold(withSender)(t => withSender.deepMerge(Json.obj("senderTeam" -> t.asJson)))
      val withDelivery = m.delivery.fold(withTeam)(d => withTeam.deepMerge(Json.obj("delivery" -> d.asJson)))
      // mailbadge 批（2026-09-13，选项 C）：可选判别字段——缺席即不落键
      // （旧 .ui.json 行的字节形态与旧读法逐字不变）。
      val withIntake = m.intake.fold(withDelivery)(i => withDelivery.deepMerge(Json.obj("intake" -> i.asJson)))
      // 气泡四段式统一批（2026-09-15）：**已渲染**的四段式 header（引擎单一来源，
      // `NotificationHeader`）随行落盘 ⇒ 历史恢复路径与 live 帧逐字渲染同一串，
      // 前端不再二次拼接。缺席即不落键（旧 .ui.json 行字节形态逐字不变；
      // 旧行由前端 `injectedSourceLabel` 回落渲染）。
      val withHeader = m.header.fold(withIntake)(h => withIntake.deepMerge(Json.obj("header" -> h.asJson)))
      // 双开缺陷批「案 B」（2026-09-21，chain-askuserdup）：作答行的显式来源标记 ——
      // 本行是某个 requestId 卡的作答记录。缺席即不落键（旧 .ui.json 行的字节形态与
      // 旧读法逐字不变；旧行 ⇒ 前端回落「未作答」）。
      m.answerOf.fold(withHeader)(r => withHeader.deepMerge(Json.obj("answerOf" -> r.asJson)))
    case m: Ai =>
      val base = Json.obj("type" -> "ai".asJson, "text" -> m.text.asJson)
      val withDur = m.durationMs.fold(base)(d => base.deepMerge(Json.obj("durationMs" -> d.asJson)))
      val withModel = m.model.fold(withDur)(mod => withDur.deepMerge(Json.obj("model" -> mod.asJson)))
      val withThinking = m.thinking.fold(withModel)(th => withModel.deepMerge(Json.obj("thinking" -> th.asJson)))
      if m.timestamp > 0 then withThinking.deepMerge(Json.obj("timestamp" -> m.timestamp.asJson)) else withThinking
    case m: Tool =>
      Json.obj(
        "type" -> "tool".asJson,
        "label" -> m.label.asJson,
        "summary" -> m.summary.asJson,
        "content" -> m.content.asJson,
        "isError" -> m.isError.asJson,
        "input" -> m.input.asJson
      )
    case m: Agent =>
      val base = Json.obj("type" -> "agent".asJson, "agentId" -> m.agentId.asJson, "text" -> m.text.asJson)
      // R1：timestamp 缺席即不落键（与 User/Ai 同款条件编码 ⇒ 旧 .ui.json 行不变）。
      if m.timestamp > 0 then base.deepMerge(Json.obj("timestamp" -> m.timestamp.asJson)) else base
    case m: AskUser =>
      // 案 B：requestId 随行落盘（历史卡可 id 寻址）。缺席即不落键 ⇒ 旧行形态逐字不变。
      val base = Json.obj("type" -> "askUser".asJson, "items" -> m.items.asJson)
      m.requestId.filter(_.nonEmpty).fold(base)(r => base.deepMerge(Json.obj("requestId" -> r.asJson)))
    case m: Ask =>
      val base = Json.obj("type" -> "ask".asJson, "question" -> m.question.asJson, "answer" -> m.answer.asJson)
      val withDur = m.durationMs.fold(base)(d => base.deepMerge(Json.obj("durationMs" -> d.asJson)))
      val withModel = m.model.fold(withDur)(mod => withDur.deepMerge(Json.obj("model" -> mod.asJson)))
      if m.timestamp > 0 then withModel.deepMerge(Json.obj("timestamp" -> m.timestamp.asJson)) else withModel
    case m: AskPermission =>
      val base = Json.obj(
        "type" -> "askPermission".asJson,
        "toolName" -> m.toolName.asJson,
        "summary" -> m.summary.asJson,
        "input" -> m.input.asJson
      )
      val withSource = m.sourceAgent.fold(base)(sa => base.deepMerge(Json.obj("sourceAgent" -> sa.asJson)))
      m.sourceSession.fold(withSource)(ss => withSource.deepMerge(Json.obj("sourceSession" -> ss.asJson)))
    case m: System =>
      val base = Json.obj("type" -> "system".asJson, "content" -> m.content.asJson)
      val withKey = m.i18nKey.fold(base)(k => base.deepMerge(Json.obj("i18nKey" -> k.asJson)))
      m.params.fold(withKey)(p => withKey.deepMerge(Json.obj("params" -> p)))
  }

  given Decoder[UiMessage] = Decoder.instance { cursor =>
    cursor.downField("type").as[String].flatMap {
      case "user" =>
        for
          text <- cursor.downField("text").as[String]
          atts <- cursor.downField("attachments").as[Option[List[Json]]]
          injected <- cursor.downField("injected").as[Option[Boolean]]
          timestamp <- cursor.downField("timestamp").as[Option[Long]]
          source <- cursor.downField("source").as[Option[String]]
          eventType <- cursor.downField("eventType").as[Option[String]]
          sender <- cursor.downField("sender").as[Option[String]]
          senderTeam <- cursor.downField("senderTeam").as[Option[String]]
          delivery <- cursor.downField("delivery").as[Option[String]]
          intake <- cursor.downField("intake").as[Option[String]]
          header <- cursor.downField("header").as[Option[String]]
          // 案 B：作答行来源标记（旧行缺席 ⇒ None ⇒ 前端回落「未作答」）
          answerOf <- cursor.downField("answerOf").as[Option[String]]
        yield User(
          text,
          atts.getOrElse(Nil),
          injected.getOrElse(false),
          timestamp.getOrElse(0L),
          source,
          eventType,
          sender,
          senderTeam,
          delivery,
          intake,
          header,
          answerOf.filter(_.nonEmpty)
        )
      case "ai" =>
        for
          text <- cursor.downField("text").as[String]
          durationMs <- cursor.downField("durationMs").as[Option[Long]]
          model <- cursor.downField("model").as[Option[String]]
          thinking <- cursor.downField("thinking").as[Option[String]]
          timestamp <- cursor.downField("timestamp").as[Option[Long]]
        yield Ai(text, durationMs, model, thinking, timestamp.getOrElse(0L))
      case "tool" =>
        for
          label <- cursor.downField("label").as[String]
          summary <- cursor.downField("summary").as[String]
          content <- cursor.downField("content").as[Option[String]]
          isError <- cursor.downField("isError").as[Option[Boolean]]
          input <- cursor.downField("input").as[Option[String]]
        yield Tool(
          label,
          summary,
          content.getOrElse(""),
          isError.getOrElse(false),
          input.getOrElse("")
        )
      case "agent" =>
        for
          agentId <- cursor.downField("agentId").as[String]
          text <- cursor.downField("text").as[String]
          timestamp <- cursor.downField("timestamp").as[Option[Long]] // R1：旧行缺席 ⇒ 0
        yield Agent(agentId, text, timestamp.getOrElse(0L))
      case "askUser" =>
        for
          items <- cursor.downField("items").as[List[Json]]
          // 案 B：旧行无 requestId ⇒ None（前端回落形态兜底，不强求 id）
          requestId <- cursor.downField("requestId").as[Option[String]]
        yield AskUser(items, requestId.filter(_.nonEmpty))
      case "ask" =>
        for
          question <- cursor.downField("question").as[String]
          answer <- cursor.downField("answer").as[String]
          durationMs <- cursor.downField("durationMs").as[Option[Long]]
          model <- cursor.downField("model").as[Option[String]]
          timestamp <- cursor.downField("timestamp").as[Option[Long]] // R1：旧行缺席 ⇒ 0
        yield Ask(question, answer, durationMs, model, timestamp.getOrElse(0L))
      case "askPermission" =>
        for
          toolName <- cursor.downField("toolName").as[String]
          summary <- cursor.downField("summary").as[String]
          input <- cursor.downField("input").as[String]
          sourceAgent <- cursor.downField("sourceAgent").as[Option[String]]
          sourceSession <- cursor.downField("sourceSession").as[Option[String]]
        yield AskPermission(toolName, summary, input, sourceAgent, sourceSession)
      case "system" =>
        for
          content <- cursor.downField("content").as[String]
          i18nKey <- cursor.downField("i18nKey").as[Option[String]]
          params <- cursor.downField("params").as[Option[Json]]
        yield System(content, i18nKey, params)
      case other => Left(DecodingFailure(s"Unknown UiMessage type: $other", cursor.history))
    }
  }
end UiMessage
