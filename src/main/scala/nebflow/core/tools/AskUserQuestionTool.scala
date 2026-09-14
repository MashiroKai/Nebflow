package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.ActorRef
import nebflow.agent.{
  AgentCommand, AgentStatus, AskMode, AskUserAnswerBridge, InteractionHubCommand, InteractionRequestId
}
import nebflow.core.{AskItem, AskOption, AskPreview, HeadlessMode, QuestionDependency}
import nebflow.shared.ToolDefinition

import scala.concurrent.duration.*

object AskUserQuestionTool extends Tool:
  /** 工具名（`AgentCore.schemaVariantFor` 与本工具共用此一处字面量）。 */
  val Name = "AskUserQuestion"

  val name = Name

  val description =
    """Ask the user one or more questions. Each question can have predefined options or be open-ended. The tool gives the user clickable options and a structured UI, which is faster and clearer than reading a text question — never ask clarifying questions in plain text.

When to use:
- You cannot proceed without an answer.
- There are multiple valid approaches to choose between.
- You need the user to provide information you cannot infer.

When NOT to use (anti-pattern): if you can make a reasonable decision yourself, do NOT ask — just proceed and let the user correct course if needed. Example: don't ask "Which file should I fix?" when the error message already names the file.

Supervision note (project nodes): when a project node asks, the question card is attributed with a "project · node" source label and a node-ask trace event is written to the flow map event log — every ask is visible to the dispatcher for audit. Asking is supervised, not a bypass; still follow the anti-pattern rule above (decide yourself when you reasonably can, and batch dependent questions into one call).

Guidelines:
- For multiple-choice questions, provide clear label values and optional description for each option.
- When several answers may apply to the same question (e.g. "Which areas should we cover?"), set "multiple": true on that question — the user can check several options and the answer comes back as an array of the selected values. Use it only when the choices are genuinely non-exclusive.
- For open-ended questions, omit options so the user gets a free-text input.
- The UI always provides an "Other..." option so the user can type freely even for multiple-choice.
- Independent questions are shown together and can be answered at once.

Visual selection support (askuser-canvas direction C):
- Set `canvas` on a question (absolute file path) to auto-open a comparison page in the Canvas panel when the question appears.
- Set `preview` on an option to embed an inline thumbnail: `{"type": "swatch", "colors": ["#hex", ...]}` shows 1-5 color stripes; `{"type": "image", "src": "<url>"}` shows an image.

Conditional branching (dependsOn):
- Give the upstream question an `id`, then set `dependsOn: {"ref": "<id>", "equals": "<answer>"}` on the dependent question.
- The dependent question is only shown when the referenced answer matches `equals`.
- Rule of thumb: if you would otherwise ask sequentially ("first A, then depending on the answer, ask B"), express the full question tree with dependsOn in a single call instead.
- Common scenarios: stack choice — ask "Which language?" (id: lang) and "Which framework?" (dependsOn: lang=Python → Django/FastAPI; lang=Rust → Actix/Axum); deployment — ask "Deploy where?" (id: target) and if Vercel → "Custom domain?"; testing — ask "Test type?" and if Unit → "Mock library?".
- Independent questions don't need dependsOn — just include them all in one call.

Behavior:
- This tool blocks until the user responds. Your turn pauses and resumes automatically when the user answers."""

  /** 基础变体（= 上面 `description`）**逐字节保持不变**（规格 §5.1 补充条）——
    * 非 root 会话看到的永远是这一份，分化只许在 root 变体上「加」，不许在基础
    * 变体上「改/删/美化」（否则非 root 会话的请求前缀会漂移，且语义面被扩大）。
    * spec `AskUserDualModeSpec` 用字节比对钉住本不变量。 */
  val descriptionBase: String = description

  /** root 变体的增量段（工具面按角色分化批 B1/L5）：只在
    * [[nebulaRootVariant]] 里拼接，绝不并入基线段。三段内容 = ① 非阻塞的确切
    * 语义（发起即返回 + 答复稍后以消息到达 + 未答按最佳判断继续）、② 默认值、
    * ③ 适用面声明（本形态仅本会话可见）。 */
  private val nebulaRootExtraDescription =
    """
- Non-blocking mode (`mode` = "non-blocking", default "blocking"): the tool returns immediately with an acknowledgement instead of waiting. Your turn does NOT pause; the question card is shown to the user exactly as in blocking mode, and the answer arrives later as a new user message in this session (it wakes a new turn when you are idle, or lands at the next turn boundary). The acknowledgement carries the requestId — match the incoming answer to it. If no answer arrives and you cannot decide, proceed with your best judgment and say so in your wrap-up.
- `mode` is available to this session only (Nebula root); every other session sees the blocking form alone."""

  /** root 变体 description（B1/L5）：基础变体 + 增量段（基础文本一字不改）。 */
  val descriptionNebulaRoot: String = description + nebulaRootExtraDescription

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "questions" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> "Questions to ask the user, each with its own options".asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "object".asJson,
            "properties" -> io.circe.Json.obj(
              "question" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The question to ask".asJson),
              "id" -> io.circe.Json.obj(
                "type" -> "string".asJson,
                "description" -> "Unique identifier for this question. Required when other questions depend on this one.".asJson
              ),
              "dependsOn" -> io.circe.Json.obj(
                "type" -> "object".asJson,
                "description" -> "Only show this question when the referenced question's answer matches. Use for conditional branching.".asJson,
                "properties" -> io.circe.Json.obj(
                  "ref" -> io.circe.Json
                    .obj("type" -> "string".asJson, "description" -> "The id of the question this depends on".asJson),
                  "equals" -> io.circe.Json.obj(
                    "type" -> "string".asJson,
                    "description" -> "The answer value that must match for this question to appear".asJson
                  )
                ),
                "required" -> io.circe.Json.arr("ref".asJson, "equals".asJson)
              ),
              "multiple" -> io.circe.Json.obj(
                "type" -> "boolean".asJson,
                "description" ->
                  "Allow selecting several options (checkboxes). Default false = single choice. The answer for this question is returned as an array of the selected option values.".asJson
              ),
              "canvas" -> io.circe.Json.obj(
                "type" -> "string".asJson,
                "description" -> "Optional absolute path to a comparison page that is auto-opened in the Canvas panel when this question appears.".asJson
              ),
              "options" -> io.circe.Json.obj(
                "type" -> "array".asJson,
                "description" -> "Predefined choices for this question".asJson,
                "items" -> io.circe.Json.obj(
                  "type" -> "object".asJson,
                  "properties" -> io.circe.Json.obj(
                    "label" -> io.circe.Json
                      .obj("type" -> "string".asJson, "description" -> "Short option label".asJson),
                    "description" -> io.circe.Json
                      .obj("type" -> "string".asJson, "description" -> "Optional explanation".asJson),
                    "preview" -> io.circe.Json.obj(
                      "type" -> "object".asJson,
                      "description" -> "Optional inline preview for this option: {type:'swatch', colors:[...]} shows 1-5 color stripes; {type:'image', src:'<url>'} shows an image thumbnail. Omit for no preview.".asJson,
                      "properties" -> io.circe.Json.obj(
                        "type" -> io.circe.Json.obj(
                          "type" -> "string".asJson,
                          "description" -> "'swatch' or 'image'".asJson
                        ),
                        "colors" -> io.circe.Json.obj(
                          "type" -> "array".asJson,
                          "description" -> "For swatch: 1-5 CSS color strings, displayed as equal-width stripes".asJson,
                          "items" -> io.circe.Json.obj("type" -> "string".asJson)
                        ),
                        "src" -> io.circe.Json.obj(
                          "type" -> "string".asJson,
                          "description" -> "For image: the thumbnail URL".asJson
                        )
                      )
                    )
                  ),
                  "required" -> io.circe.Json.arr("label".asJson)
                )
              )
            ),
            "required" -> io.circe.Json.arr("question".asJson)
          )
        )
      ),
      "required" -> io.circe.Json.arr("questions".asJson)
    )
  )

  // ============================================================
  // 两份 schema 变体（B1/L4）：基础变体 = 上面 `inputSchema`（**逐字节不变**，
  // 非 root 会话看到的那份，**属性缺席**——不是 enum 收窄、也不是「有属性但值
  // 非法」，T9=(a)）；root 变体 = 基础 + `mode` 属性（enum + default）。
  // 不接落点（= 无 buildToolList 分组）时本对象对外行为零变化。
  // ============================================================

  /** `mode` 属性的 schema（线上字面量取自 [[AskMode]] —— 与解析端共用一处）。 */
  val modePropertySchema: io.circe.Json = io.circe.Json.obj(
    "type" -> "string".asJson,
    "enum" -> io.circe.Json.arr(AskMode.BlockingWire.asJson, AskMode.NonBlockingWire.asJson),
    "default" -> AskMode.BlockingWire.asJson,
    "description" -> ("How this question is asked. \"blocking\" (default) parks your turn until the user answers — " +
      "the answer comes back as this tool's result. \"non-blocking\" returns immediately and the answer arrives later " +
      "as a user message in this session; the card is identical. Non-blocking is available to the Nebula root session only.").asJson
  )

  /** root 变体 schema：基础 schema + `properties.mode`（从传入的基础定义派生 ⇒
    * 与基础变体的字节一致性由构造方式保证）。 */
  def schemaNebulaRoot(base: JsonObject): JsonObject =
    val props = base("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
    base.add("properties", io.circe.Json.fromJsonObject(props.add("mode", modePropertySchema)))

  /** 不变式：基础 schema 里**没有** `mode`（防「顺手把并集 schema 写回来」）。 */
  def baseHasModeProperty: Boolean =
    inputSchema("properties").flatMap(_.asObject).exists(_.contains("mode"))

  /** root 变体定义（B1）：从**已注册的定义**（`ToolRegistry.ALL_TOOLS` 那份，
    * 已经过 `RemoteExecutor.augmentSchema`）派生 ⇒ 变体只多一个属性 + 一段描述，
    * 基础面逐字节不受影响；工具名不变（成员资格与权限边界零变化）。 */
  def nebulaRootVariant(base: ToolDefinition): ToolDefinition =
    base.copy(
      description = descriptionNebulaRoot,
      inputSchema = schemaNebulaRoot(base.inputSchema)
    )

  def summarize(input: JsonObject): String =
    val questions = input("questions").flatMap(_.asArray).getOrElse(Nil)
    if questions.isEmpty then "AskUser()"
    else if questions.length == 1 then
      val q = questions.head.hcursor.downField("question").as[String].getOrElse("")
      val short = if q.length > 40 then q.take(37) + "..." else q
      s"AskUser($short)"
    else s"AskUser(${questions.length} questions)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 100 then result.take(97) + "..." else result

  /** Parse the raw `questions` JSON array into AskItems. Pure — spec-covered.
    * Malformed entries (empty question / empty option label) are skipped, matching
    * the historical behavior. `multiple` defaults to false when absent.
    */
  def parseItems(questionsJson: Seq[io.circe.Json]): List[AskItem] =
    questionsJson.flatMap { q =>
      val question = q.hcursor.downField("question").as[String].getOrElse("")
      if question.isBlank then None // skip malformed entries with empty question
      else
        val id = q.hcursor.downField("id").as[String].toOption
        val dependsOn = for
          dep <- q.hcursor.downField("dependsOn").focus
          ref <- dep.hcursor.downField("ref").as[String].toOption
          equals <- dep.hcursor.downField("equals").as[String].toOption
        yield QuestionDependency(ref, equals)
        val multiple = q.hcursor.downField("multiple").as[Boolean].getOrElse(false)
        val canvas = q.hcursor.downField("canvas").as[String].toOption
        val options = q.hcursor.downField("options").as[List[io.circe.Json]].getOrElse(Nil)
        val opts = options.flatMap { o =>
          val label = o.hcursor.downField("label").as[String].getOrElse("")
          if label.isBlank then None // skip options with empty label
          else
            val desc = o.hcursor.downField("description").as[String].toOption
            val preview = for
              pv <- o.hcursor.downField("preview").focus
              t <- pv.hcursor.downField("type").as[String].toOption
            yield AskPreview(
              `type` = t,
              colors = pv.hcursor.downField("colors").as[List[String]].toOption,
              src = pv.hcursor.downField("src").as[String].toOption
            )
            Some(AskOption(label, desc, preview))
        }
        Some(AskItem(question, opts, id = id, dependsOn = dependsOn, multiple = multiple, canvas = canvas))
      end if
    }.toList

  /** Error text returned when headless mode blocks an AskUser call. */
  val HeadlessErrorMessage =
    "Headless mode: no interactive user available — decide autonomously and continue with your best judgment."

  /** Headless guard: NEBFLOW_HEADLESS=1 (benchmark mode) means no interactive
    * user — dispatching AgentCommand.AskUser would park the run on a reply
    * that never arrives. Returning a ToolError instead tells the agent to
    * decide autonomously and continue. Pure (flag passed in) so both branches
    * are spec-covered; the call touchpoint binds HeadlessMode.enabled.
    */
  def askGuard(headless: Boolean = HeadlessMode.enabled): Option[ToolError] =
    if headless then Some(ToolError(HeadlessErrorMessage)) else None

  // ============================================================
  // 运行期兜底闸（B3/L3，规格 §0.4 第二道）：`mode` 解析 + 非 root 显式拒绝。
  // 位置 = `askGuard` **之后**、`askUser` **之前**，**先于任何副作用**（此点之后
  // 才可能有 hub 槽位 / 卡片 / 状态标记）。三层分层里 schema 分化是**第一性**，
  // 本闸兜住 schema 兜不到的四类边角：① 模型硬造面外参数（引擎无 schema 校验器
  // ⇒ 面外参数本来会被静默忽略）② 进程外调用方（ScriptTool / spec harness /
  // REST 直调）③ 分化实现 bug（漏分支）④ 未来新增会话形态漏传身份。
  // **不得**因 schema 分化而删除本闸——删即把硬造参数变成静默降级。
  // ============================================================

  /** 非法 `mode` 值的错误码（第三种伪处理：静默按阻塞跑）。 */
  val BadModeCode = "ASKUSER_BAD_MODE"

  /** 非 root 携带非阻塞的错误码（规格 §3.3 定稿文案的机器可读锚）。 */
  val NonBlockingNotRootCode = "ASKUSER_NONBLOCK_NOT_ROOT"

  /** 非阻塞**无窗口可投**的错误码（B6 可达性预检；规格 §3.4#4）。 */
  val NoRootWindowCode = "ASKUSER_NONBLOCK_NO_ROOT_WINDOW"

  /** `mode` 解析（B3）：**缺席 ⇒ 默认阻塞**（既有调用点零行为漂移）；**出现但
    * 非法 ⇒ 显式 ToolError**（类型不对 / 值域外 / 空串一律显式，绝不静默回落
    * 到阻塞——那正是明禁的「静默忽略参数」伪处理）。 */
  def parseMode(input: JsonObject): Either[ToolError, AskMode] =
    input("mode") match
      case None => Right(AskMode.Blocking)
      case Some(j) =>
        j.asString.flatMap(AskMode.parse) match
          case Some(m) => Right(m)
          case None =>
            Left(ToolError(
              s"AskUserQuestion: invalid `mode` value ${j.noSpaces} — legal values are " +
                s""""${AskMode.BlockingWire}" | "${AskMode.NonBlockingWire}" (omit `mode` for the default "Blocking"); """ +
                s"nothing was asked ($BadModeCode)."
            ))

  /** 非 root 携带非阻塞的拒答（规格 §3.3 定稿文案：错在哪 / 期望是什么 / 合法
    * 选项 / 错误码；并显式禁止重试）。 */
  def nonBlockingNotRootError(ctx: ToolContext): ToolError =
    val who = ctx.agentDef.map(_.name).getOrElse("<no agent session>")
    ToolError(
      s"AskUserQuestion: non-blocking mode is Nebula-root-only — this session is '$who' (depth=${ctx.depth}), " +
        s"so `mode=\"${AskMode.NonBlockingWire}\"` is not permitted here ($NonBlockingNotRootCode). " +
        "Legal options: (a) omit `mode` (blocking is the default and IS available to you); " +
        "(b) if you must not block, use a different channel (node: text result along the out edge / BLOCKED report) instead. " +
        s"Do not retry with mode=\"${AskMode.NonBlockingWire}\"."
    )

  /** 非阻塞可达性预检失败（B6）：问题**发不到任何窗口** ⇒ fail-closed 拒绝，
    * 且**零槽位**（预检是只读的，发生在任何 hub 注册之前）。 */
  def noRootWindowError(ctx: ToolContext): ToolError =
    val root = ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("<unknown>")
    ToolError(
      "AskUserQuestion: non-blocking mode needs an open window — this session's root " +
        s"'$root' has no live client window registered with the interaction hub, so a non-blocking question " +
        s"would never be shown (and nobody is waiting for it). Nothing was asked ($NoRootWindowCode). " +
        "Legal options: (a) omit `mode` (blocking falls back to any other registered window); " +
        "(b) ask again once a window is connected."
    )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // Headless benchmark mode: fail fast at the entry — no AskUser dispatch,
    // no wait; the error message pushes the agent to proceed on its own.
    askGuard() match
      case Some(err) => IO.pure(Left(err))
      case None =>
        // B3 兜底闸：解析 + 角色判定，**先于任何副作用**（纯函数，无 IO）。
        parseMode(input) match
          case Left(err) => IO.pure(Left(err))
          case Right(AskMode.Blocking) => askUser(input, ctx)
          case Right(AskMode.NonBlocking) =>
            if !ctx.isNebulaRoot then IO.pure(Left(nonBlockingNotRootError(ctx)))
            else askUserNonBlocking(input, ctx)

  /** 入参校验（阻塞/非阻塞共用；错误文案与历史逐字节一致）。 */
  private def parseOrError(input: JsonObject): Either[ToolError, List[AskItem]] =
    val questionsJson = input("questions").flatMap(_.asArray).getOrElse(Nil)
    if questionsJson.isEmpty then Left(ToolError("No valid questions provided"))
    else
      val items = parseItems(questionsJson)
      if items.isEmpty then Left(ToolError("No valid questions provided")) else Right(items)

  private def askUser(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    parseOrError(input) match
      case Left(err) => IO.pure(Left(err))
      case Right(items) =>
        ctx.agentActorRef match
          case Some(agentRef) =>
            // #250 第⑤项：requestId 熵强化（单点生成器，作用域 ask-）
            val requestId = InteractionRequestId.forAskUser()
            agentRef
              .?(
                (replyTo: ActorRef[List[String]]) => AgentCommand.AskUser(requestId, items, Some(replyTo)),
                timeout = None
              )
              .flatMap { answers =>
                // R2 closure (wait-timeout-fix): the answer landed — paired
                // un-mark for the WaitingForUser status set by the agent's
                // AskUser handler. Fresh activity stamp so the TaskStuckWatcher
                // idle window restarts from the answer, and the session is back
                // under true-stuck coverage while the turn continues.
                restoreRegistryAfterAnswer(ctx).as(Right(formatAnswer(items, answers)))
              }
          case None =>
            IO.pure(Left(ToolError("AskUserQuestion requires agent actor")))
      end match
  end askUser

  // ============================================================
  // 非阻塞分支（B5/L6，仅 Nebula root 可达——B3 闸已在本方法之前判过）
  // ============================================================

  /** 预检上限（B6）：只读查询的有界等待；超时 = fail-closed 拒绝（不静默放行）。 */
  val PreflightTimeout: FiniteDuration = 5.seconds

  /** 非阻塞 ack（L6）：机器可读（requestId + 问题数）+ 明确「不要在此等待」+
    * 未答兜底指令（D6：丢答案的降级必须是**设计内**的，不是静默的）。 */
  def nonBlockingAck(items: List[AskItem], requestId: String): String =
    s"requestId=$requestId · ${items.size} question(s) · non-blocking: the answer will arrive later as a " +
      "message in this session — do not wait for it; if no answer arrives and you cannot decide, proceed with your best judgment."

  /** 可达性预检（B6/M10）：**只读**问 hub「本会话 root 的窗口是否已注册」。
    *
    * WHY：非阻塞下没人等待 ⇒ root 不可达时卡被丢弃（hub 仅 warn）就会变成**静默
    * 丢失答案**（规格 §5.4 root 不可达风险行的唯一「必修正」项）。fail-closed：
    * 无 hub / 无 root 窗口 / hub 未在有界窗口内应答 ⇒ 显式拒绝。**零槽位**：预检
    * 不注册任何 pending（发生在 `AgentCommand.AskUser` 派发之前）。 */
  private def rootWindowReachable(ctx: ToolContext): IO[Either[ToolError, Unit]] =
    val rootSid = ctx.rootSessionId.filter(_.nonEmpty).orElse(ctx.sessionId.filter(_.nonEmpty)).getOrElse("")
    if rootSid.isEmpty then IO.pure(Left(noRootWindowError(ctx)))
    else
      ctx.sharedResources match
        case None => IO.pure(Left(noRootWindowError(ctx)))
        case Some(res) =>
          res.interactionHubRef.get.flatMap {
            case None => IO.pure(Left(noRootWindowError(ctx)))
            case Some(hub) =>
              hub
                .?(
                  (replyTo: ActorRef[Boolean]) => InteractionHubCommand.RootReachable(rootSid, replyTo),
                  timeout = Some(PreflightTimeout)
                )
                .map(reachable => if reachable then Right(()) else Left(noRootWindowError(ctx)))
                .handleErrorWith(_ => IO.pure(Left(noRootWindowError(ctx))))
          }

  /** 非阻塞执行链（L6）：同校验、同 items、同 hub 卡片链（`AgentCommand.AskUser`
    * → `AgentActor` → `InteractionHubCommand.Request`），两处不同：
    *  ① `replyTo` = 一次性桥接引用（[[AskUserAnswerBridge]]）而非工具 fiber 的回执；
    *  ② **不做 `.?`**（不等待）⇒ 派发后立刻返回 ack，turn 不暂停。
    * `AgentCommand.AskUser` 携带 `AskMode.NonBlocking` ⇒ `AgentActor` 跳过
    * `WaitingForUser` 标注与 `DelegateBudget.pause`（等待从未发生，无配对物）。 */
  private def askUserNonBlocking(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    parseOrError(input) match
      case Left(err) => IO.pure(Left(err))
      case Right(items) =>
        ctx.agentActorRef match
          case None => IO.pure(Left(ToolError("AskUserQuestion requires agent actor")))
          case Some(agentRef) =>
            rootWindowReachable(ctx).flatMap {
              case Left(err) => IO.pure(Left(err))
              case Right(_) =>
                // #250 第⑤项：requestId 熵强化（单点生成器，作用域 asknb-）
                val requestId = InteractionRequestId.forAskUserNonBlocking()
                val bridge = AskUserAnswerBridge.ref(agentRef, items, requestId, ctx)
                (agentRef ! AgentCommand.AskUser(requestId, items, Some(bridge), AskMode.NonBlocking))
                  .as(Right(nonBlockingAck(items, requestId)))
            }
      end match
  end askUserNonBlocking


  /** R2 (wait-timeout-fix): paired un-mark for the WaitingForUser status the
    * agent's AskUser handler set when this question was dispatched. Registry
    * entry present → status=Processing + fresh lastActivityMs (same touch
    * semantics as AgentCore.touchRegistryActivity — never creates a ghost
    * row). No-op when sharedResources/sessionId are absent (harness calls).
    * Failure-safe: a registry touch must never fail the user's answer.
    * private[tools]: ProjectCreateTool's path panel dispatches the same
    * AgentCommand.AskUser and must pair the same un-mark (one shared
    * implementation — no divergent copy). */
  private[tools] def restoreRegistryAfterAnswer(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        // R11 第 4 层 / U1=C-a + U8=(ii)：ask **答复单点**发恢复信号——内核的
        // 3600s wall-clock 预算从此刻继续累计（等待期不计入）。非 Delegate 会话
        // 无预算通道 ⇒ 无害 no-op。发起侧配对点 = AgentActor 的 AskUser 分支。
        nebflow.agent.DelegateBudget.resume(sid) *>
          res.agentRegistry.modify { m =>
            m.get(sid) match
              case Some(rec) => (m.updated(sid, rec.copy(status = AgentStatus.Processing, lastActivityMs = now)), ())
              case None      => (m, ())
          }.handleErrorWith(_ => IO.unit)
      case _ => IO.unit

  /** Normalize a multi-select answer for the LLM: canonical compact JSON array
    * (`["A","B"]`). The frontend serializes a multi-select answer as a JSON
    * array string in its answers slot (the wire stays List[String], one slot
    * per question). Non-JSON payloads (older frontends, joined text) pass
    * through unchanged.
    */
  private def formatMultiple(raw: String): String =
    io.circe.parser.decode[List[String]](raw) match
      case Right(values) => values.asJson.noSpaces
      case Left(_)       => raw

  /** Format user answers for display. */
  def formatAnswer(items: List[AskItem], answers: List[String]): String =
    def present(item: Option[AskItem], raw: String): String =
      item match
        case Some(i) if i.multiple => formatMultiple(raw)
        case _                     => raw
    if items.size <= 1 then
      answers.headOption.filter(_.nonEmpty).map(a => present(items.headOption, a)).getOrElse("")
    else
      items.zipWithIndex
        .map { case (item, idx) =>
          val raw = answers.lift(idx).getOrElse("")
          val answer = if raw.isEmpty then "(skipped)" else present(Some(item), raw)
          s"${idx + 1}. ${item.question.take(60)}\n   → $answer"
        }
        .mkString("\n")
end AskUserQuestionTool
