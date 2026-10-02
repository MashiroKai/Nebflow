package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.core.*
import nebflow.shared.*

import scala.concurrent.duration.*

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):静态面经注册器倒置
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

Attachments:
- `attachments` (optional, top level, at most 9 entries) lists absolute local file paths to hand to the USER as clickable entries — the file opens in the Canvas panel when the user clicks it. Nothing is read into your context.
- Use it when a human needs to OPEN a file to decide. To make the MODEL see a file's content, use the `images` parameter instead. The same path may appear in both — they serve different readers.
- Each entry must be an absolute POSIX path inside a readable domain: projects, uploads, plots, workspace-items, voice-models, docs, evidence. Relative paths, `.`/`..`, `~`, symlinks that resolve outside those domains, non-regular files, unreadable files, credential paths and entries above 100 MB are rejected — the whole call fails with a readable error naming the offending path.

Behavior:
- This tool is non-blocking: it returns immediately with an acknowledgement, and the user's answer arrives later as a message in this session. Do not wait for it. If no answer arrives and you cannot decide, proceed with your best judgment and say so in your wrap-up."""

  /**
   * 本批（root 2026-10-02 令 #462 裁②）**不保留 `mode` 键**：schema 与 description
   * 对**所有身份**逐字节一致，无变体可分 —— 历史的三层授权面（定义层分化 /
   * 运行期拒绝 / description）随阻塞腿一并退场，`AgentCore.schemaVariantFor` 的
   * AskUser 分支整体删除。此处保留 `descriptionBase` 只为在 spec 中钉住「无第二份
   * 变体文本」的不变量。
   */
  val descriptionBase: String = description

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
        ),
        "attachments" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> ("Optional absolute file paths to hand to the user as clickable entries "
            + "(they open in the Canvas panel). Max 9 entries, 100 MB each. Nothing is read into your context.").asJson,
          "maxItems" -> 9.asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "string".asJson,
            "description" -> "Absolute path, inside a readable domain only.".asJson
          )
        )
      ),
      "required" -> io.circe.Json.arr("questions".asJson)
    )
  )

  // ============================================================
  // 无 schema 变体（root 2026-10-02 令 #462 裁②「不保留 mode 键」）：历史上的
  // 「基础变体 vs root 变体（基础 + `mode`）」分叉已整体移除 —— root 面与基础面
  // schema 逐字节相等，`schemaVariantFor` 的 AskUser 分支同步删除。
  // ============================================================

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

  /**
   * Parse the raw `questions` JSON array into AskItems. Pure — spec-covered.
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

  /**
   * Headless guard: NEBFLOW_HEADLESS=1 (benchmark mode) means no interactive
   * user — dispatching AgentCommand.AskUser would park the run on a reply
   * that never arrives. Returning a ToolError instead tells the agent to
   * decide autonomously and continue. Pure (flag passed in) so both branches
   * are spec-covered; the call touchpoint binds HeadlessMode.enabled.
   */
  def askGuard(headless: Boolean = HeadlessMode.enabled): Option[ToolError] =
    if headless then Some(ToolError(HeadlessErrorMessage)) else None

  // ============================================================
  // 面外参数闸（本批收敛）：`mode` 拒绝。位置 = `askGuard` **之后**、
  // `parseOrError` **之前**，**先于任何副作用**（此点之后才可能派发 hub 槽位）。
  // 理由：引擎无 JSON-Schema 校验器 ⇒ 面外参数本来会被**静默忽略**；本闸把
  // 「既有调用面携带 `mode`」折成显式可判读 `ToolError`，不静默降级、不回溯叙述。
  // ============================================================

  /** 携带已下线 `mode` 参数的错误码（机器可读锚）。 */
  val ModeParamRetiredCode = "ASKUSER_MODE_PARAM_RETIRED"

  /**
   * `mode` 参数拒绝（「不保留键方案」）：本工具一律非阻塞 ⇒ 参数面无合法 `mode`
   * 值，**出现即拒**（不按取值分叉）。文案给出：错在哪 / 期望是什么 / 错误码。
   */
  def modeParamError(value: io.circe.Json): ToolError =
    ToolError(
      s"AskUserQuestion does not accept a `mode` parameter (got ${value.noSpaces}) — this tool always asks in a " +
        s"non-blocking way ($ModeParamRetiredCode)."
    )

  /** 面外参数闸：`mode` 在场（不论取值）⇒ 显式拒绝；缺席 ⇒ 放行。 */
  def rejectRetiredModeParam(input: JsonObject): Option[ToolError] =
    input("mode").map(modeParamError)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // Headless benchmark mode: fail fast at the entry — no AskUser dispatch,
    // no wait; the error message pushes the agent to proceed on its own.
    askGuard() match
      case Some(err) => IO.pure(Left(err))
      case None =>
        // 面外参数闸（纯函数，先于任何副作用）。
        rejectRetiredModeParam(input) match
          case Some(err) => IO.pure(Left(err))
          case None => askUser(input, ctx)

  /** 入参校验（错误文案与历史逐字节一致）。 */
  private def parseOrError(input: JsonObject): Either[ToolError, List[AskItem]] =
    val questionsJson = input("questions").flatMap(_.asArray).getOrElse(Nil)
    if questionsJson.isEmpty then Left(ToolError("No valid questions provided"))
    else
      val items = parseItems(questionsJson)
      if items.isEmpty then Left(ToolError("No valid questions provided")) else Right(items)

  /**
   * 唯一的执行链（非阻塞-only）：`parseOrError` → 附件校验 → 派发 + 立即 ack。
   *
   * 与历史阻塞腿的差别（阻塞腿已整体移除）：
   *  ① `replyTo` = 一次性桥接引用（[[AskUserAnswerBridge]]）而非工具 fiber 的回执；
   *  ② **不做 `.?`**（不等待）⇒ 派发后立刻返回 ack，turn 不暂停。
   * 无窗口预检（`rootWindowReachable`）已随裁④ 去除：问题经 hub 扇出
   * （`InteractionHub.handleRequest:165-199`）或挂 pending（`snapshotFrames`）
   * 承接 ⇒ 绝对无窗也不再 fail-closed 拒绝。
   */
  private def askUser(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (parseOrError(input), parseAttachmentsOrError(input)) match
      case (Left(err), _) => IO.pure(Left(err))
      case (_, Left(err)) => IO.pure(Left(err))
      case (Right(items), Right(attachments)) =>
        ctx.agentActorRef match
          case None => IO.pure(Left(ToolError("AskUserQuestion requires agent actor")))
          case Some(agentRef) =>
            // #250 第⑤项：requestId 熵强化（单点生成器，作用域 asknb-）
            val requestId = InteractionRequestId.forAskUserNonBlocking()
            // 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M2):桥构造经注册器(agent 实现原地)。
            val bridge = AskUserAnswerPort.ref(agentRef, items, requestId, ctx)
            (agentRef ! AgentCommand.AskUser(requestId, items, Some(bridge), attachments))
              .as(Right(nonBlockingAck(items, requestId, attachments)))
    end match
  end askUser

  /**
   * 非阻塞 ack（唯一回执面）：机器可读（requestId + 问题数 + 附件数）+ 明确
   * 「不要在此等待」+ 未答兜底指令（丢答案的降级必须是**设计内**的，不是静默的）。
   */
  def nonBlockingAck(items: List[AskItem], requestId: String, attachments: List[String] = Nil): String =
    val attach = if attachments.isEmpty then "" else s" · ${attachments.size} attachment(s)"
    s"requestId=$requestId · ${items.size} question(s)$attach · non-blocking: the answer will arrive later as a " +
      "message in this session — do not wait for it; if no answer arrives and you cannot decide, proceed with your best judgment."

  /**
   * 附件引用（顶层可选参数 `attachments`）——**仅作人的可点击入口**，路径不被读取、
   * 不进模型上下文（需要模型看见内容 ⇒ 用 `images`）。
   *
   * 上限：件数 = [[nebflow.shared.AttachContract.MaxAttachmentsPerMessage]]（全仓
   * 消息附件口径的单一数值权威面）；单件 = [[MaxAttachmentBytes]]（对齐前端
   * `attachmentPreview.js` 的 `MAX_TEXT_BYTES` 打开闸 ⇒ 不产生「可贴但不可看」）。
   */
  val MaxAttachmentBytes: Long = 100L * 1024 * 1024

  /** 件数超限的错误码。 */
  val AttachCountCode = "ASKUSER_ATTACH_TOO_MANY"

  /** 附件域的**文案用**清单（判据本体不在此 —— 见 [[judgeAttachmentPath]]）。 */
  val ReadableDomainLabel: String =
    "projects, uploads, plots, workspace-items, voice-models, docs, evidence"

  private def attachmentError(code: String, message: String): ToolError =
    ToolError(s"$message ($code)")

  /**
   * 顶层 `attachments` 解析（与 `parseItems` 同族；与 `canvas` 同为顶层可选）。
   * 形态宽松读（缺省 / 非数组 ⇒ `Nil`），**纯** —— 判据本体在
   * [[validateAttachments]]（那需要 `FilePolicyPort`，属效果面）。
   */
  def parseAttachments(input: JsonObject): List[String] =
    input("attachments").flatMap(_.asArray) match
      case None => Nil
      case Some(arr) => arr.flatMap(_.asString).toList

  /**
   * 附件校验：**单次遍历、fail-closed**（任一不过 ⇒ 整次调用拒绝，不部分放行）。
   *
   * 🔴 判据本体**零复制**：白名单/凭据两层一律经 `FilePolicyPort` 发问（判据本体唯一
   * 实现在 `gateway.NfFilePolicy`，`FileRefs.scala:105-125` 明写禁第二份副本）；本文件
   * 内无白名单表、无凭据正则、无字符串 `contains` / 裸 `startsWith` 判定。
   */
  def validateAttachments(paths: List[String]): Either[ToolError, List[String]] =
    if paths.length > AttachContract.MaxAttachmentsPerMessage then
      Left(
        attachmentError(
          AttachCountCode,
          s"Too many attachments: ${paths.length} — at most ${AttachContract.MaxAttachmentsPerMessage} per call."
        )
      )
    else
      paths.foldLeft[Either[ToolError, List[String]]](Right(Nil)) { (acc, raw) =>
        acc.flatMap(done => validateOneAttachment(raw).map(done :+ _))
      }

  /** 逐条校验：词法形态 → 规范化 → 存在/普通文件/可读 → 大小 → 白名单/凭据层。 */
  private def validateOneAttachment(raw: String): Either[ToolError, String] =
    val abs = try java.nio.file.Paths.get(raw) catch case _: Throwable => null
    if abs == null || !abs.isAbsolute then
      Left(attachmentError("ASKUSER_ATTACH_NOT_ABSOLUTE", s"Attachment path must be absolute: $raw"))
    else if raw.contains("~") then
      // `~` 禁展开：展开会把接收端 home 暴露给发送端构造的字符串（同 TargetDirGuard 理由）。
      Left(attachmentError("ASKUSER_ATTACH_TILDE", s"Attachment path must not use `~`; give an absolute path: $raw"))
    else if raw.split('/').exists(s => s == "." || s == "..") then
      // `..` / `.` 词法 ⇒ 拒，**禁自动折叠**（折叠会把字符串静默改写为另一路径，审计面失去可比性）。
      Left(
        attachmentError(
          "ASKUSER_ATTACH_RELATIVE_SEGMENT",
          s"Attachment path must not contain `.` or `..` segments: $raw"
        )
      )
    else
      val real =
        try Some(abs.toRealPath()) // 符号链接按**真实路径**判，不做按原字符串判的旁路
        catch case _: Throwable => None
      real match
        case None =>
          Left(attachmentError("ASKUSER_ATTACH_UNREADABLE", s"Attachment is not readable: $raw"))
        case Some(rp) =>
          if !java.nio.file.Files.isRegularFile(rp) then
            Left(attachmentError("ASKUSER_ATTACH_NOT_REGULAR", s"Attachment is not a regular file: $raw"))
          else
            val size =
              try java.nio.file.Files.size(rp) catch case _: Throwable => -1L
            if size < 0 || !java.nio.file.Files.isReadable(rp) then
              Left(attachmentError("ASKUSER_ATTACH_UNREADABLE", s"Attachment is not readable: $raw"))
            else if size > MaxAttachmentBytes then
              Left(
                attachmentError(
                  "ASKUSER_ATTACH_TOO_LARGE",
                  s"Attachment exceeds the ${MaxAttachmentBytes / (1024 * 1024)} MB per-file limit: $raw"
                )
              )
            else judgeAttachmentPath(raw, rp)

  /**
   * 白名单 + 凭据两层：**只向 `FilePolicyPort` 发问**，本文件内零白名单表、零凭据
   * 正则。端口未接线 / 抛错 ⇒ **fail-closed**（拒绝，不假设可服务）。拒绝文案不给
   * 命中的具体凭据文件名、不给宿主绝对路径全貌。
   */
  private def judgeAttachmentPath(raw: String, real: java.nio.file.Path): Either[ToolError, String] =
    try
      FilePolicyPort.port.endpointVerdictLayer(real) match
        case Some((layer, _, _)) =>
          layer match
            case FilePolicyPort.NfDenyLayer.Credential | FilePolicyPort.NfDenyLayer.CredentialInode =>
              Left(
                attachmentError(
                  "ASKUSER_ATTACH_CREDENTIAL",
                  s"Attachment path is not allowed in a credential domain: $raw"
                )
              )
            case FilePolicyPort.NfDenyLayer.Namespace | FilePolicyPort.NfDenyLayer.FileType =>
              Left(
                attachmentError(
                  "ASKUSER_ATTACH_OUT_OF_DOMAIN",
                  s"Attachment path is outside the readable domains ($ReadableDomainLabel): $raw"
                )
              )
        case None =>
          if FilePolicyPort.port.credentialInodeHit(real) then
            Left(
              attachmentError(
                "ASKUSER_ATTACH_CREDENTIAL",
                s"Attachment path is not allowed in a credential domain: $raw"
              )
            )
          else Right(raw)
    catch
      case _: Throwable =>
        Left(
          attachmentError(
            "ASKUSER_ATTACH_JUDGE_UNAVAILABLE",
            s"Attachment path could not be checked, treating it as not served: $raw"
          )
        )

  /** 顶层附件参数的一体化入口：解析 + 校验。 */
  def parseAttachmentsOrError(input: JsonObject): Either[ToolError, List[String]] =
    validateAttachments(parseAttachments(input))

  /**
   * 答复落定后的配对恢复（每个**阻塞**调用方的答复单点调用一次）。
   *
   * 本批（root 2026-10-02 令 #462 裁①）后，`AskUserQuestionTool` 自身**不再**是
   * 此函数的调用方（一律非阻塞、无等待态）；唯一调用方 = `ProjectCreateTool.pathPanel`
   * —— 那个面板仍在自己的 fiber 里阻塞等待（答复即 `createChain` 的入参），
   * `AgentProcessing` 据此命令的 `awaitsAnswer=true` 标了 `WaitingForUser` 并
   * `DelegateBudget.pause`，本函数是**同一条链的配对解除点**（缺它 ⇒ 会话永久停在
   * 等待态、内核预算永久暂停 = 造出「永不解除的等待」）。
   *
   * 注册表行存在 ⇒ status=Processing + 新活动戳（同 `AgentCore.touchRegistryActivity`
   * 语义，**绝不**造幽灵行）。sharedResources/sessionId 缺席（spec harness）⇒ no-op。
   * 失败安全：一次注册表触碰绝不让用户的答复失败。
   */
  private[tools] def answerLanded(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        DelegateBudgetPort.resume(sid) *>
          res.agentRegistry
            .modify { m =>
              m.get(sid) match
                case Some(rec) => (m.updated(sid, rec.copy(status = AgentStatus.Processing, lastActivityMs = now)), ())
                case None => (m, ())
            }
            .handleErrorWith(_ => IO.unit)
      case _ => IO.unit

  /**
   * Normalize a multi-select answer for the LLM: canonical compact JSON array
   * (`["A","B"]`). The frontend serializes a multi-select answer as a JSON
   * array string in its answers slot (the wire stays List[String], one slot
   * per question). Non-JSON payloads (older frontends, joined text) pass
   * through unchanged.
   */
  private def formatMultiple(raw: String): String =
    io.circe.parser.decode[List[String]](raw) match
      case Right(values) => values.asJson.noSpaces
      case Left(_) => raw

  /** Format user answers for display. */
  def formatAnswer(items: List[AskItem], answers: List[String]): String =
    def present(item: Option[AskItem], raw: String): String =
      item match
        case Some(i) if i.multiple => formatMultiple(raw)
        case _ => raw
    if items.size <= 1 then answers.headOption.filter(_.nonEmpty).map(a => present(items.headOption, a)).getOrElse("")
    else
      items.zipWithIndex
        .map { case (item, idx) =>
          val raw = answers.lift(idx).getOrElse("")
          val answer = if raw.isEmpty then "(skipped)" else present(Some(item), raw)
          s"${idx + 1}. ${item.question.take(60)}\n   → $answer"
        }
        .mkString("\n")
end AskUserQuestionTool
