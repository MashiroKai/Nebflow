package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.AskHandoffPort
import nebflow.core.ask.{AskGatewayServices, AskSessions}
import nebflow.shared.PathUtil

import scala.concurrent.duration.DurationLong

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas ask mode 工具面（机制稿 §5/§7，实施批 2026-10-28；2026-10-31 只读工具批
 * 增补第三件 read_file_content——设计清单 docs/design/20261031_113700_ask-readonly-
 * tool-design__delegate-nebflow-09ef7b2e.zh-CN.md，作者令「agent 报无法读取文件」）
 *
 * 白名单封闭枚举的三件（AskSessions.AllowedTools 单点）：
 *   - message_record_search —— 消息检索 CLI 封装（tools/stand-recall，只读）；
 *   - send_message_to_main_chat —— 单向信使（§7 ask_handoff；ask 会话唯一的
 *     对外动作），经 AskHandoffPort 注册器投递（gateway 侧 ensureAgent 主会话
 *     ! ExternalEvent(source="ask") ⇒ injected 气泡 + 来源行引擎呈现链）。
 *   - read_file_content —— 读本文件原文（只读）：路径锁绑定表（无 path 参数）、
 *     行窗口（start_line/max_lines，硬顶 500）、2MB 文件顶、64KB 输出顶。
 *
 * 双保险（DelegateReportTool 挂载先例）：挂载面 = buildAllowedToolSet 早退集
 * （ask 会话专属）；工具内侧 = ctx.sessionId 同判据拒绝（AskSessions 单点）。
 * 非会话上下文（ctx.sessionId 空 / REST 直调）一律拒绝。
 * ═══════════════════════════════════════════════════════════════════════════ */

/**
 * §7 频控状态：每 ask 会话最近一次成功转交时刻（内存态，重启即清——频控是
 *  防刷屏软约束，不作持久语义）。
 */
object AskHandoffRateLimit:
  val WindowMs: Long = 60L * 1000L
  private val lastAt = new TrieMap[String, Long]()

  /** true = 允许（并记账）；false = 窗内已被本会话占用。 */
  def tryAcquire(askSessionId: String, now: Long): Boolean =
    synchronized {
      val last = lastAt.getOrElse(askSessionId, 0L)
      if now - last >= WindowMs then
        lastAt.update(askSessionId, now)
        true
      else false
    }

  /** spec 注入口：清空全部记账。 */
  def reset(): Unit = lastAt.clear()
end AskHandoffRateLimit

/** 单向信使（机制稿 §7：`send_message_to_main_chat`）。 */
object SendMessageToMainChatTool extends Tool:

  val Name: String = "send_message_to_main_chat"

  override def name: String = Name

  override def description: String =
    """Send a handoff request to the MAIN session (the only outbound action an ask session has). Use it ONLY when the user explicitly asks to have the main session handle/follow up/execute something. After sending, tell the user it was sent and that the main session can ask back — then stay in this window and keep serving. Payload becomes an injected message in the main session tagged with this file's ask source."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "question" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "The request/question to hand to the main session. Required.".asJson
      ),
      "context_refs" -> Json.obj(
        "type" -> "array".asJson,
        "description" -> "Optional citation refs supporting the handoff ({source, loc} objects) — passed through to the main session verbatim.".asJson,
        "items" -> Json.obj("type" -> "object".asJson)
      )
    ),
    "required" -> Json.arr("question".asJson)
  )

  override def summarize(input: JsonObject): String =
    val q = input("question").flatMap(_.asString).getOrElse("").take(60)
    s"send_message_to_main_chat($q)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 160 then result.take(157) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val question = input("question").flatMap(_.asString).map(_.trim).getOrElse("")
    val refs = input("context_refs").flatMap(_.asArray).getOrElse(Vector.empty).toList
    val sid = ctx.sessionId.getOrElse("")

    // 身份双保险之工具内侧：非 ask 会话（含 REST 直调 / 其余一切身份）拒绝。
    if !AskSessions.isAskSession(sid) then
      IO.pure(
        Left(
          ToolError(
            "send_message_to_main_chat: permission denied — this tool is exclusive to file-ask sessions. (ASK_HANDOFF_FORBIDDEN)"
          )
        )
      )
    else if question.isEmpty then
      IO.pure(Left(ToolError("send_message_to_main_chat: `question` must be a non-empty string.")))
    else if ctx.agentDef.isEmpty then
      IO.pure(
        Left(ToolError("send_message_to_main_chat: no agent context — handoff unavailable. (ASK_HANDOFF_FORBIDDEN)"))
      )
    else
      // 绑定面回查：文件名 + 转交目标主会话（askOpen 时上报刷新；缺 = 未就绪）。
      val flow =
        for
          registry <- IO.pure(AskGatewayServices.instance.map(_.registry))
          bindingOpt <- registry match
            case Some(r) => r.byId(sid)
            case None => IO.pure(None)
          result <- bindingOpt match
            case None =>
              IO.pure(
                Left(
                  ToolError(
                    "send_message_to_main_chat: this ask session has no file binding — open the file card in canvas first. (ASK_HANDOFF_NO_BINDING)"
                  )
                )
              )
            case Some(binding) if binding.mainSessionId.trim.isEmpty =>
              IO.pure(
                Left(
                  ToolError(
                    "send_message_to_main_chat: main session not connected yet — reopen the ask dock, then try again. (ASK_HANDOFF_NO_TARGET)"
                  )
                )
              )
            case Some(binding) =>
              val now = System.currentTimeMillis()
              if !AskHandoffRateLimit.tryAcquire(sid, now) then
                IO.pure(Left(ToolError("稍后再转：转交频控为每文件每分钟 1 条（ASK_HANDOFF_RATE_LIMITED）。")))
              else
                AskHandoffPort
                  .deliver(
                    AskHandoffPort.Request(
                      askSessionId = sid,
                      file = binding.file,
                      fileName = binding.fileName,
                      question = question,
                      leg = "ask-tool",
                      contextRefs = refs
                    )
                  )
                  .map(_.map(_ => s"已发送到主会话：${question.take(80)}（它可回问，你仍在本窗继续服务）"))
              end if
        yield result
      flow.handleErrorWith(e => IO.pure(Left(ToolError(s"send_message_to_main_chat failed: ${e.getMessage}"))))
    end if
  end call
end SendMessageToMainChatTool

/**
 * 消息检索 CLI 封装（机制稿 §5：`message_record_search`）——tools/stand-recall
 * 的只读进程封装（关键词/时间窗/角色过滤/--json），数据源只读（CLI 自身纪律：
 * 绝不写 sessions 目录，只写 memory-db 目录）。
 */
object MessageRecordSearchTool extends Tool:

  val Name: String = "message_record_search"

  /** CLI 超时（检索是秒级 SQLite FTS5 查询；挂死即报错，不拖死问答轮）。 */
  val CliTimeoutSec: Long = 30

  override def name: String = Name

  override def description: String =
    """Search the indexed conversation history (the main session's memory corpus) with the stand-recall CLI — read-only. Use it to recall how something was discussed/decided before. Supports keyword query, role/time filters; returns JSON hits with ±1 neighbor sentences. Same index the CLI reads directly, so results match a manual `stand-recall` run."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "query" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Search keywords (CJK bigram aware). Required.".asJson
      ),
      "limit" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Max hits (default 5, cap 20).".asJson
      ),
      "role" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> Json.arr("user".asJson, "assistant".asJson),
        "description" -> "Optional role filter.".asJson
      ),
      "since" -> Json
        .obj("type" -> "string".asJson, "description" -> "Optional lower time bound \"YYYY-MM-DD[ HH:MM]\".".asJson),
      "until" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Optional upper time bound \"YYYY-MM-DD[ HH:MM]\".".asJson
      )
    ),
    "required" -> Json.arr("query".asJson)
  )

  override def summarize(input: JsonObject): String =
    val q = input("query").flatMap(_.asString).getOrElse("").take(60)
    s"message_record_search($q)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  /** CLI 定位（MemoryRecallDaemon 三候选同款单点；缺 = 显式错误，不静默空结果）。 */
  def resolveCli(): Option[os.Path] = nebflow.core.MemoryRecallDaemon.resolveToolPath(PathUtil.dataRoot)

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val query = input("query").flatMap(_.asString).map(_.trim).getOrElse("")
    val limit = input("limit").flatMap(_.asNumber.flatMap(_.toInt)).map(_.min(20)).getOrElse(5)
    val role = input("role").flatMap(_.asString).filter(r => r == "user" || r == "assistant")
    val since = input("since").flatMap(_.asString)
    val until = input("until").flatMap(_.asString)

    if query.isEmpty then IO.pure(Left(ToolError("message_record_search: `query` must be a non-empty string.")))
    else
      IO.blocking {
        resolveCli() match
          case None =>
            Left(
              ToolError(
                "message_record_search: stand-recall CLI not found (tools/stand-recall) — memory search unavailable."
              )
            )
          case Some(cli) =>
            val args = scala.collection.mutable.ListBuffer[String](
              "python3",
              cli.toString,
              "--json",
              "-n",
              limit.toString,
              "--context",
              "1",
              query
            )
            role.foreach(r => args ++= List("--role", r))
            since.foreach(s => args ++= List("--since", s))
            until.foreach(u => args ++= List("--until", u))
            val res = os
              .proc(args.toList)
              .call(
                cwd = os.pwd,
                timeout = CliTimeoutSec * 1000,
                check = false,
                mergeErrIntoOut = true
              )
            val out = res.out.text().trim
            if res.exitCode == 0 && out.nonEmpty then Right(out)
            else Left(ToolError(s"message_record_search: CLI exit ${res.exitCode} — ${out.take(300)}"))
      }.handleErrorWith(e => IO.pure(Left(ToolError(s"message_record_search failed: ${e.getMessage}"))))
    end if
  end call
end MessageRecordSearchTool

/**
 * 读本文件原文（2026-10-31 只读工具批：`read_file_content`）——ask 会话唯一的
 * 文件读取通道。设计清单（作者过目面）=
 * docs/design/20261031_113700_ask-readonly-tool-design__delegate-nebflow-09ef7b2e
 * .zh-CN.md，本对象逐字按清单落地：
 *   - 路径锁绑定表：无 `path` 参数，工具内侧经 AskSessionRegistry 回查本会话
 *     绑定文件——读「当前这份文件」与 ask 使命同边界，任意路径读取不成立；
 *   - 格式：UTF-8 文本；扩展名双保险复问 isAskable（绑定已过闸，此处防直调绕过）；
 *   - 体积上限：单文件 2MB（超出显式拒绝，不静默截文件级语义）；行窗口
 *     start_line（1 起，缺省 1）/ max_lines（缺省 200，硬顶 500）；单次输出
 *     64KB（窗口内再截，truncated:true + 截断标记——单行巨型文件撑不爆上下文）；
 *   - 返回 JSON 结构化结果（file/totalLines/startLine/returnedLines/truncated/
 *     content）。
 * 身份双保险同 SendMessageToMainChatTool：非 ask 会话（含 REST 直调）拒绝。
 */
object ReadFileContentTool extends Tool:

  val Name: String = "read_file_content"

  /** 文件级体积顶（清单 §体积上限：2MB 超出即拒）。 */
  val MaxFileBytes: Long = 2L * 1024L * 1024L

  /** 单次输出字符顶（清单 §单次输出上限 64KB）。 */
  val MaxOutputChars: Int = 64 * 1024

  /** 行窗口硬顶（清单：max_lines 缺省 200、硬顶 500）。 */
  val DefaultMaxLines: Int = 200
  val HardMaxLines: Int = 500

  override def name: String = Name

  override def description: String =
    """Read the original text of THIS session's bound file (the file the user is asking about) — read-only. The path is fixed to the session binding; you cannot read any other file. Content is UTF-8 text returned as a line window: use start_line (1-based, default 1) and max_lines (default 200, hard cap 500) to page through long files. Returns JSON with totalLines/returnedLines/truncated so you can plan the next window. Use it whenever the answer needs exact wording/structure beyond the assembled context snapshot."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "start_line" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "First line to return (1-based). Optional, default 1.".asJson
      ),
      "max_lines" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "Max lines to return. Optional, default 200, hard cap 500.".asJson
      )
    ),
    "required" -> Json.arr()
  )

  override def summarize(input: JsonObject): String =
    val s = input("start_line").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(1)
    val n = input("max_lines").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(DefaultMaxLines)
    s"read_file_content(lines $s..+${n.min(HardMaxLines)})"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 160 then result.take(157) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")

    // 身份双保险之工具内侧：非 ask 会话（含 REST 直调 / 其余一切身份）拒绝。
    if !AskSessions.isAskSession(sid) then
      IO.pure(
        Left(
          ToolError(
            "read_file_content: permission denied — this tool is exclusive to file-ask sessions. (ASK_READ_FORBIDDEN)"
          )
        )
      )
    else
      val startLine = input("start_line").flatMap(_.asNumber.flatMap(_.toInt)) match
        case Some(n) if n >= 1 => n
        case _                 => 1
      val maxLines = input("max_lines").flatMap(_.asNumber.flatMap(_.toInt)) match
        case Some(n) if n >= 1 => n.min(HardMaxLines)
        case _                 => DefaultMaxLines

      val flow =
        for
          registry <- IO.pure(AskGatewayServices.instance.map(_.registry))
          bindingOpt <- registry match
            case Some(r) => r.byId(sid)
            case None    => IO.pure(None)
          result <- bindingOpt match
            case None =>
              IO.pure(
                Left(
                  ToolError(
                    "read_file_content: this ask session has no file binding — open the file card in canvas first. (ASK_READ_NO_BINDING)"
                  )
                )
              )
            case Some(binding) if !AskSessions.isAskable(binding.file) =>
              // 扩展名双保险（清单 §可读格式）：绑定面已过闸，此处防直调绕过。
              IO.pure(
                Left(
                  ToolError(
                    s"read_file_content: file type not askable (md/html only): ${binding.fileName}. (ASK_READ_NOT_ASKABLE)"
                  )
                )
              )
            case Some(binding) =>
              IO.blocking {
                try
                  val p = os.Path(binding.file)
                  if !os.exists(p) || !os.isFile(p) then
                    Left(ToolError(s"read_file_content: file not found: ${binding.fileName}. (ASK_READ_NOT_FOUND)"))
                  else
                    val size = os.size(p)
                    if size > MaxFileBytes then
                      Left(
                        ToolError(
                          s"read_file_content: file too large (${size}B > ${MaxFileBytes}B cap): ${binding.fileName}. (ASK_READ_TOO_LARGE)"
                        )
                      )
                    else
                      val allLines = os.read.lines(p)
                      val total = allLines.length
                      val from = math.min(startLine, total + 1)
                      val slice =
                        if from > total then Vector.empty
                        else allLines.slice(from - 1, math.min(from - 1 + maxLines, total))
                      var content = slice.mkString("\n")
                      var truncated = from > total && total > 0
                      if content.length > MaxOutputChars then
                        content = content.take(MaxOutputChars) + "\n…[truncated at 64KB output cap — narrow max_lines or page with start_line]"
                        truncated = true
                      val json = Json.obj(
                        "file"          -> binding.fileName.asJson,
                        "totalLines"    -> (if total > 0 then total else 0).asJson,
                        "startLine"     -> from.asJson,
                        "returnedLines" -> slice.length.asJson,
                        "truncated"     -> truncated.asJson,
                        "content"       -> content.asJson
                      )
                      Right(json.noSpaces)
                catch
                  case NonFatal(e) =>
                    Left(ToolError(s"read_file_content failed: ${e.getMessage}"))
              }
        yield result
      flow.handleErrorWith(e => IO.pure(Left(ToolError(s"read_file_content failed: ${e.getMessage}"))))
    end if
  end call
end ReadFileContentTool
