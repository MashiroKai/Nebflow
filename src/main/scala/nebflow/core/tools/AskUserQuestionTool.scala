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

  /**
   * 附件判据词汇（解析/探针/拒绝枚举）——**Pop 文件面同一套**（askuser-attach-align
   *  批：禁第二套域规则/第二权威，作者 2026-10-07 09:41 令）。
   */
  import FileRefs.*

  /** 工具名（`AgentCore.schemaVariantFor` 与本工具共用此一处字面量）。 */
  val Name = "AskUserQuestion"

  val name = Name

  /**
   * 附件引用（顶层可选参数 `attachments`）的**单件大小上限**——仅作人的可点击入口，
   * 路径不被读取、不进模型上下文（需要模型看见内容 ⇒ 用 `images`）。
   *
   * 上限：件数 = [[nebflow.shared.AttachContract.MaxAttachmentsPerMessage]]（全仓
   * 消息附件口径的单一数值权威面）；单件 = 本常量（对齐前端 `attachmentPreview.js`
   * 的 `MAX_TEXT_BYTES` 打开闸 ⇒ 不产生「可贴但不可看」）。
   *
   * ⚠ 声明位置在本对象**最前段**（早于 `description` / `inputSchema`）：两者都以
   * 插值引用本常量，Scala 单例的字段按声明序初始化 ⇒ 放在后面会读到 0（`0 MB`）。
   */
  val MaxAttachmentBytes: Long = 100L * 1024 * 1024

  /** 件数超限的错误码。 */
  val AttachCountCode = "ASKUSER_ATTACH_TOO_MANY"

  /**
   * 双维度自解释尾注（作者 09:35 令②(b)「报错必须双维度齐全」·09:41 令同步改）：
   * 附件面的拒绝维 = **路径/位置**（不可解析/不存在/非普通文件/不可读/凭据身份/
   * 超限），**类型面永不是拒绝维**——扩展名门已随 Pop 同判撤除（Canvas viewer
   * 注册表渲染注册类型；未注册类型点击走下载兜底）。每条拒绝文案附此注，读者
   * 一眼可判「哪一维失败、哪一维无关」——作者四撞的全因即旧文案只列目录清单、
   * 两个维度混在一个单码里。
   */
  val RejectionTypeNote: String =
    "The failing dimension is the PATH/LOCATION, never the file type: any readable local " +
      "file type is accepted (registered types render in Canvas; everything else downloads on click)."

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

Secret questions (secret: true):
- Use for API keys, tokens, passwords, verification codes — anything the user must not paste as plain text. The question renders a password box instead of options.
- The value travels exactly one path: card password box → gateway → the secrets store (owner-only 0600 file). YOU NEVER SEE THE VALUE: the answer message carries only a pointer receipt `{"stored":true,"keyRef":...,"location":...,"variableName":...}` (or `{"stored":false,"reason":...}` on failure).
- Consume the value inside a Bash command via `$(cat <location>)` — it exists only inside that sub-shell. If the secret is allowlisted in nebflow.json (`secrets.inject`), it is also available as `$VARIABLE`. Discipline: the value is a service input only — never print it, write it to a file, or forward it.
- `ephemeral: true` marks a one-time verification code (OTP/2FA): the store keeps it at most `ttlSec` seconds (default 600, clamp 10–86400) and the FIRST read consumes it — a second read fails. Ask again for a fresh code rather than retrying.
- `secretName` optionally names the stored file (lowercase `[a-z0-9._-]`); default `askuser-<requestId>-<questionIndex>`. Secret questions do not accept options/dirPicker/canvas (options are stripped, violating questions are skipped).

Conditional branching (dependsOn):
- Give the upstream question an `id`, then set `dependsOn: {"ref": "<id>", "equals": "<answer>"}` on the dependent question.
- The dependent question is only shown when the referenced answer matches `equals`.
- Rule of thumb: if you would otherwise ask sequentially ("first A, then depending on the answer, ask B"), express the full question tree with dependsOn in a single call instead.
- Common scenarios: stack choice — ask "Which language?" (id: lang) and "Which framework?" (dependsOn: lang=Python → Django/FastAPI; lang=Rust → Actix/Axum); deployment — ask "Deploy where?" (id: target) and if Vercel → "Custom domain?"; testing — ask "Test type?" and if Unit → "Mock library?".
- Independent questions don't need dependsOn — just include them all in one call.

Attachments:
- `attachments` (optional, top level, at most 9 entries) lists local file paths to hand to the USER as clickable entries — the file opens in the Canvas panel when the user clicks it, or downloads when no viewer claims its type. Nothing is read into your context.
- Use it when a human needs to OPEN a file to decide. To make the MODEL see a file's content, use the `images` parameter instead. The same path may appear in both — they serve different readers.
- Each entry must resolve to a regular local file that is safe to show a human — the SAME rules as Pop's file face: absolute paths, `~/…` (expands to the home directory), and space / `%20`-containing spellings are all accepted (closest match wins); `.`/`..` segments are folded; relative paths resolve against the process working directory. Any readable local location works — `/tmp/…`, `~/.nebflow/…`, data-directory and project files alike. Refused: non-regular or unreadable files, files above 100 MB, credential locations and credential-shaped names (`auth.json`, `id_rsa`, `*.pem`, `.ssh`/`.aws`/… subtrees), and hard links sharing a credential file's inode. There is NO extension restriction and no directory whitelist: registered viewer types open in Canvas, everything else downloads on click. The whole call fails with a readable error naming the offending path, the failing reason, and which dimension failed.

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
              "secret" -> io.circe.Json.obj(
                "type" -> "boolean".asJson,
                "description" ->
                  "Render a password input instead of options. The user's value is stored server-side (owner-only file); you receive ONLY a pointer receipt {stored, keyRef, location, variableName} — never the value. Incompatible with options/dirPicker/canvas (options are stripped, violating questions are skipped).".asJson
              ),
              "secretName" -> io.circe.Json.obj(
                "type" -> "string".asJson,
                "description" ->
                  "Optional storage name for a secret question (lowercase [a-z0-9._-], max 64). Default: askuser-<requestId>-<questionIndex>.".asJson
              ),
              "ephemeral" -> io.circe.Json.obj(
                "type" -> "boolean".asJson,
                "description" ->
                  "One-time verification-code mode for a secret question: the value expires (default 10 min) and the first read consumes it. Use for OTP/2FA codes, not long-lived keys.".asJson
              ),
              "ttlSec" -> io.circe.Json.obj(
                "type" -> "integer".asJson,
                "description" -> "TTL seconds for an ephemeral secret question (10-86400; default 600).".asJson
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
            + s"(they open in the Canvas panel). Max ${AttachContract.MaxAttachmentsPerMessage} entries, "
            + s"${MaxAttachmentBytes / (1024 * 1024)} MB each. Nothing is read into your context.").asJson,
          // 件数上限的**机器读数**取自全仓单点常量（禁第二份数值）；description 散文里的
          // 同一数字由 spec 的「散文钉常量」断言守住漂移。
          "maxItems" -> AttachContract.MaxAttachmentsPerMessage.asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "string".asJson,
            "description" -> "Local file path — absolute, ~/…, or space/%20-encoded form; judged like Pop's file face (credential locations refused). Unregistered types download on click.".asJson
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
   *
   * secret 型题（本批，规格 §1/§5.7）的解析裁定：
   *   · `secret:true` 与 `canvas` **互斥**——违例题**整条跳过**（与
   *     空问题同一「跳过畸形条目」先例；秘密题渲染对比页 = 通道语义
   *     直接矛盾，不静默改写发起方的题）。dirPicker 不经本工具下发。
   *   · `secret:true` 携带 options ⇒ **secret 胜出**：选项剥除（密码面不渲染
   *     选项，前端同样忽略），题保留——剥掉的是无意义的附件，不是问题本身。
   *   · `ttlSec` 非法（非数 / ≤0）⇒ None（落库侧再钳位到 10–86400）。
   * 四个新字段缺席 = 逐字节旧行为（`AskUserBuildJsonSpec` 字节契约不受影响）。
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
        val secret = q.hcursor.downField("secret").as[Boolean].toOption.getOrElse(false)
        // 互斥闸（规格 §5.7）：secret × canvas = 畸形条目，整条跳过（与空问题同一
        // 「跳过畸形条目」先例）。dirPicker 不经本工具下发（仅 ProjectTool 面板自造），
        // 工具面无需第二条互斥腿。
        if secret && canvas.nonEmpty then None
        else
          val secretName = q.hcursor.downField("secretName").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          val ephemeral = q.hcursor.downField("ephemeral").as[Boolean].toOption.getOrElse(false)
          val ttlSec = q.hcursor.downField("ttlSec").as[Int].toOption.filter(_ > 0)
          val options = q.hcursor.downField("options").as[List[io.circe.Json]].getOrElse(Nil)
          val opts =
            if secret then Nil // secret 胜出：密码面不携带选项（规格 §5.7）
            else
              options.flatMap { o =>
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
          Some(
            AskItem(
              question,
              opts,
              id = id,
              dependsOn = dependsOn,
              multiple = multiple,
              canvas = canvas,
              secret = secret,
              secretName = secretName,
              ephemeral = ephemeral,
              ttlSec = ttlSec
            )
          )
        end if
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
  val ModeParamRetiredCode = "ASKUSER_MODE_PARAM_UNSUPPORTED"

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
   * 无窗口可达性预检（历史上的 root 窗口探针）已随裁④ 去除：问题经 hub 扇出
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
   *
   * secret 型题（本批）追加一段预期管理：`N secret question(s)` —— 告知模型
   * 「答案稍后到达的形态是**指针回执**（keyRef/location），值永远不到达会话」，
   * 防止模型把回执当缺失答案反复重问。纯函数；无 secret 题 ⇒ 输出与改前逐字节一致。
   */
  def nonBlockingAck(items: List[AskItem], requestId: String, attachments: List[String] = Nil): String =
    val attach = if attachments.isEmpty then "" else s" · ${attachments.size} attachment(s)"
    val secrets = items.count(_.secret)
    val secretNote =
      if secrets == 0 then ""
      else
        s" · $secrets secret question(s): each answer arrives as a stored pointer (keyRef/location) — the value itself never enters this session; consume it via $$(cat <location>)"
    s"requestId=$requestId · ${items.size} question(s)$attach$secretNote · non-blocking: the answer will arrive later as a " +
      "message in this session — do not wait for it; if no answer arrives and you cannot decide, proceed with your best judgment."

  /**
   * 顶层 `attachments` 解析（与 `parseItems` 同族；与 `canvas` 同为顶层可选）。
   * 形态宽松读（缺省 / 非数组 ⇒ `Nil`），**纯** —— 判据本体在
   * [[validateAttachments]]（那需要 `FilePolicyPort`，属效果面）。
   */
  def parseAttachments(input: JsonObject): List[String] =
    input("attachments").flatMap(_.asArray) match
      case None => Nil
      case Some(arr) => arr.flatMap(_.asString).toList

  private def attachmentError(code: String, message: String): ToolError =
    ToolError(s"$message ($code)")

  /**
   * 附件校验：**单次遍历、fail-closed**（任一不过 ⇒ 整次调用拒绝，不部分放行）。
   *
   * 🔴 判据 = **Pop 文件面的同一套**（作者 2026-10-07 09:35/09:41 两令·
   * 「跟 Pop 保持一致」/「Pop 能挂的 AskUser 就能挂」，禁第二套域规则/第二权威）：
   * 解析走 `FileRefs.resolveCandidates`（`~` 展开 + `%20`/`+` 变形候选，变形最小
   * 优先——与 Pop 逐字同一解析源），准入走 `FileRefs.probeFile`（存在/普通文件/
   * ≤200MB/realpath 上的端点同一份阶梯 = 凭据身份 + R2 inode；**无目录域门、
   * 无扩展名门**——`/tmp`、`~/.nebflow` 等本机可读路径全部可用）。本文件在此之上
   * 只保留**一把**自己的尺：单件 ≤ [[MaxAttachmentBytes]]（前端 Canvas 打开闸同
   * 值；probe 的 200MB 代理闸在其上）。判据不可得 ⇒ fail-closed（拒绝，不假设
   * 可服务）。拒绝文案不给命中的具体凭据表项、不给宿主绝对路径全貌。
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

  /**
   * 逐条校验（askuser-attach-align 批重写）：解析（Pop 同源）→ probe（Pop 同一
   * 探针，内部含端点同一份凭据阶梯）→ 自有大小尺。**通过 ⇒ 返回解析后的绝对路径**
   * （`FileRefs.describe`，与 Pop 载荷同口径）——帧/`.ui.json` 落盘/前端三腿直接
   * 可用，`~`/`%20` 形态不再依赖前端二次解析。
   */
  private def validateOneAttachment(raw: String): Either[ToolError, String] =
    val hit = FileRefs.resolveCandidates(
      raw.trim,
      FileRefs.resolvePath,
      v => unresolvableAttachment(v)
    )
    hit.path match
      case None =>
        // 任何形态都解析不成文件系统路径（含空串/纯空白）。
        hit.decision match
          case RefDecision.Reject(rejected) =>
            Left(attachmentProbeError("ASKUSER_ATTACH_UNRESOLVED", raw, rejected.detail))
          case _ =>
            Left(attachmentProbeError("ASKUSER_ATTACH_UNRESOLVED", raw, s"the reference could not be resolved: $raw"))
      case Some(path) =>
        hit.decision match
          case RefDecision.Reject(rejected) => Left(rejectionFromProbe(raw, rejected))
          case _ =>
            // 自有尺：单件 ≤ MaxAttachmentBytes（probe 的 200MB 代理闸已过）。
            val size =
              try java.nio.file.Files.size(path)
              catch case _: Throwable => -1L
            if size < 0 then
              Left(
                attachmentProbeError(
                  "ASKUSER_ATTACH_UNREADABLE",
                  raw,
                  s"the file is present but its size could not be read: ${FileRefs.describe(path)}"
                )
              )
            else if size > MaxAttachmentBytes then
              Left(
                attachmentProbeError(
                  "ASKUSER_ATTACH_TOO_LARGE",
                  raw,
                  s"exceeds the ${MaxAttachmentBytes / (1024 * 1024)} MB per-file limit (resolved: ${FileRefs.describe(path)})"
                )
              )
            else Right(FileRefs.describe(path))

            end if

    end match

  end validateOneAttachment

  /** 解析失败的共用构造（resolveCandidates 的 whenUnresolvable 腿）。 */
  private def unresolvableAttachment(value: String): RefDecision =
    RefDecision.Reject(
      RejectedRef(
        value,
        None,
        FileRefFailure.Unresolvable,
        s"the path could not be resolved to a filesystem path: $value"
      )
    )

  /**
   * 拒绝文案 = 输入路径回显 + probe 自己的可判读 detail（与 Pop 警告逐字同源）
   *  + 双维度尾注 ——「何域可服务（类型面全放）、当前路径为何拒（detail）」齐备。
   */
  private def attachmentProbeError(fallbackCode: String, raw: String, detail: String): ToolError =
    attachmentError(fallbackCode, s"Attachment refused for `$raw` — $detail. ${RejectionTypeNote}")

  /** probe 的拒绝枚举 → 工具错误码（凭据族保住既有 `ASKUSER_ATTACH_CREDENTIAL` 锚）。 */
  private def rejectionFromProbe(raw: String, rejected: RejectedRef): ToolError =
    val code = rejected.failure match
      case FileRefFailure.NotFound => "ASKUSER_ATTACH_NOT_FOUND"
      case FileRefFailure.NotRegularFile => "ASKUSER_ATTACH_NOT_REGULAR"
      case FileRefFailure.NotReadable => "ASKUSER_ATTACH_UNREADABLE"
      case FileRefFailure.SizeExceeded => "ASKUSER_ATTACH_TOO_LARGE"
      case FileRefFailure.Unresolvable => "ASKUSER_ATTACH_UNRESOLVED"
      case FileRefFailure.NotServable =>
        rejected.layer match
          case Some(FilePolicyPort.NfDenyLayer.Credential) | Some(FilePolicyPort.NfDenyLayer.CredentialInode) =>
            "ASKUSER_ATTACH_CREDENTIAL"
          case _ => "ASKUSER_ATTACH_UNSERVABLE"
      case _ => "ASKUSER_ATTACH_UNSERVABLE" // ExtensionNotAllowed 已不可产出（门已撤）；Other 兜底
    attachmentProbeError(code, raw, rejected.detail)

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
   * 🔴 2026-10-09 作者裁定：内核 wall-clock 预算已整体拆除——`resume` 现为无害
   * no-op（DelegateBudget 墓碑壳）；本函数剩余职责 = 等待态解除（registry 触碰），
   * 等待期治理只剩 `TaskStuckWatcher` 的 `WaitingForUser` 豁免。
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
        case Some(i) if i.secret => formatSecretReceipt(raw)
        case Some(i) if i.multiple => formatMultiple(raw)
        case _ => raw
    if items.size <= 1 then answers.headOption.filter(_.nonEmpty).map(a => present(items.headOption, a)).getOrElse("")
    else
      items.zipWithIndex
        .map { case (item, idx) =>
          val raw = answers.lift(idx).getOrElse("")
          // 空槽位：普通题保持既有 "(skipped)" 文案（逐字节不变）；secret 题给
          // 「未提供」指引（渠道只答非 secret 题时该槽为空，模型须知道去哪补）。
          val answer =
            if raw.isEmpty then if item.secret then formatSecretReceipt(raw) else "(skipped)"
            else present(Some(item), raw)
          s"${idx + 1}. ${item.question.take(60)}\n   → $answer"
        }
        .mkString("\n")

  end formatAnswer

  /**
   * secret 题槽位的呈现（规格 §2「指针文本」单一渲染点，阻塞/非阻塞两腿与
   * formatAnswer 同源）。槽位携带的是 hub `complete` 写入的**回执 JSON**
   * （`{stored:true,keyRef,location,variableName[,ephemeral]}` / `{stored:false,reason}`）；
   * 渲染成可执行的消费指引（`$(cat <location>)`）+ 工具纪律行（值只作服务输入，
   * 禁打印/落盘/转发）。宽松读：非 JSON（旧快照/渠道空槽）原样透传或给「未提供」——
   * 秘密题的槽位**从不**携带值，因此渲染面无需任何脱敏逻辑。
   */
  private def formatSecretReceipt(raw: String): String =
    if raw.isBlank then "not provided (a secret question must be answered on the desktop panel)"
    else
      io.circe.parser.parse(raw).toOption.flatMap(_.asObject) match
        case None => raw // 非 JSON：透传（防御性；正常链路只产回执 JSON）
        case Some(o) =>
          def str(k: String): String = o(k).flatMap(_.asString).getOrElse("")
          val stored = o("stored").flatMap(_.asBoolean).getOrElse(false)
          if stored then
            val keyRef = str("keyRef")
            val location = str("location")
            val variableName = str("variableName")
            val ephemeral = o("ephemeral").flatMap(_.asBoolean).getOrElse(false)
            val oneTime =
              if ephemeral then " ONE-TIME code: reading it consumes the file — ask for a fresh code on failure."
              else ""
            s"stored as keyRef=$keyRef → consume via $$(cat $location)$oneTime " +
              s"(allowlisted env variable: $variableName). Discipline: the value is a service input only — " +
              "never print it, write it to disk, or forward it."
          else
            val reason = str("reason")
            s"NOT stored${if reason.isEmpty then "" else s" ($reason)"} — re-ask the user on the desktop panel."
end AskUserQuestionTool
