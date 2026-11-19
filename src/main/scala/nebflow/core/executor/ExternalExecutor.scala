package nebflow.core.executor

import io.circe.Json
import io.circe.parser.parse
import nebflow.shared.PathUtil

/**
 * ExternalExecutor —— 外部 CLI 执行器适配层（external-executors 批 #1225，
 * 2026-10-06）。R2/R11 外部腿真通路：Batch 1 的检测面（[[ExecutorRegistry]]）
 * 之上接真适配器，替换 DelegateTool/MailTool 的 `DELEGATE_EXECUTOR_PENDING`
 * fail-closed 墓碑。
 *
 * == 形态（增补任务书 §一/§二 定稿句）==
 *   - `ExternalExecutor` trait ＋ 三适配器：claude-code（`-p --output-format
 *     stream-json --verbose`）、codex（`exec --json`）、zcode（headless print）。
 *     hermes 占位 = [[ExecutorRegistry]].defs 里 `adapterReady=false`（检测＋
 *     图标占位，选择面显式拒绝），**不建**可启动的占位适配器——fail-closed。
 *   - stdout 逐行 → [[parseLine]] → 帧（agentStart / agentTextDelta /
 *     agentToolStart / agentDone 四帧由 runner 映射发射）。
 *   - 续聊 = 各 CLI 自带会话持久化（claude `--resume <sid>`、codex
 *     `exec resume <tid>`、zcode `--resume <sid>`）；cli 会话 id 从流里取
 *     （[[ExternalFrame.Start]]），落 [[nebflow.core.delegate.DelegateRegistry]]
 *     Record.cliSessionId。
 *
 * == fail-closed（增补条款 3.4，R2/R11）==
 * 适配器建立 / resume / 进程失败**不得静默回退 nebflow**——选择解析
 * （[[ExecutorRegistry.resolveRequested]]）与 runner 的每一条失败路径都以
 * 显式错误终态（blocked ＋原因），错误面走任务行 / AgentControl status。
 *
 * == 外部零注入（增补条款 3.4，R8）==
 * 外部执行器零插件注入、零平台工具注入——task brief 是它的全部上下文；
 * 主 agent 可用集段只列可用 id，不注入外部执行器本体。
 */
object ExternalExecutor:

  /** 适配器产出的一帧（stdout 单行解析结果；一行情值多帧时按序给全）。 */
  sealed trait Frame extends Product with Serializable

  object Frame:

    /** 会话开始；CLI 侧会话 id（续聊锚点）若流里给出则携带。 */
    final case class Start(cliSessionId: Option[String]) extends Frame

    /** 助手文本增量（整块到达即整块一发——适配流不拆字符）。 */
    final case class Text(text: String) extends Frame

    /** 工具调用开始（label 面向进度行）。 */
    final case class Tool(label: String) extends Frame

    /**
     * 终局帧：CLI final 文本（结果取 CLI final 文本——原书不变）。failed=true
     * 表示流面显式报错（result error / turn.failed）。
     */
    final case class Final(text: String, failed: Boolean) extends Frame

    /** A text content block at its original protocol index. */
    final case class ContentBlock(index: Int, text: String)

    /** A committed assistant message; identity is not inferred from its text. */
    final case class MessageComplete(messageId: Option[String], blocks: List[ContentBlock]) extends Frame

    /** Flush the preceding delta batch and identify the next one, if known. */
    final case class MessageBoundary(messageId: Option[String] = None) extends Frame

    /** Protocol termination, independent of the operating-system exit status. */
    final case class ExecutionEnd(
      failed: Boolean,
      result: Option[String] = None,
      messageId: Option[String] = None,
      metadata: Json = Json.Null
    ) extends Frame

  end Frame

  /** 单个外部执行器适配器。 */
  trait ExternalExecutor:
    def id: String
    def displayName: String

    /** 启动 argv：首元素为可执行名（检测路径由 runner 解析后替换）。 */
    def launchArgs(task: String, resumeSessionId: Option[String]): List[String]

    def launchArgsWithModel(task: String, resumeSessionId: Option[String], model: Option[String]): List[String] =
      val args = launchArgs(task, resumeSessionId)
      model.filter(_.nonEmpty).fold(args) { value =>
        id match
          case "claude-code" => args :++ List("--model", value)
          case "codex" => args.take(1) :++ List("--model", value) :++ args.drop(1)
          case _ => args
      }

    /** 单行 stdout 解析；非本格式行（噪声/空行）→ Nil，绝不抛。 */
    def parseLine(line: String): List[Frame]

    /** 「打开原软件」的交互恢复命令（openNative 模板用；resume 缺席时=裸启动）。 */
    def interactiveResumeCommand(cliSessionId: Option[String]): String
  end ExternalExecutor

  // ── 解析小工具 ────────────────────────────────────────────────

  private def parseJson(line: String): Option[Json] =
    val trimmed = line.trim
    if trimmed.isEmpty || !trimmed.startsWith("{") then None
    else parse(trimmed).toOption

  private def str(j: Json, field: String): Option[String] =
    j.hcursor.downField(field).as[String].toOption

  // ── claude-code 适配器 ────────────────────────────────────────

  /**
   * claude-code：`claude -p <task> --output-format stream-json --verbose`
   * （-p/--print、--output-format stream-json、--verbose、--resume <id> 均在册）。
   * 行格式（stream-json）：
   *   {"type":"system","subtype":"init","session_id":"..."}
   *   {"type":"assistant","message":{"content":[{"type":"text","text":...},
   *        {"type":"tool_use","name":"Bash",...}]}}
   *   {"type":"result","subtype":"success","result":"...","is_error":false}
   *
   * == 工作面放行（--add-dir ＋ headless 权限形态）==
   * claude 缺省工作区边界 = cwd（delegate 座位树）；数据根下的工作子目录靠
   * `--add-dir` 逐个点名（[[grantDirs]]）——**白名单制，根级凭据件
   * （auth.json / secrets/ / *.env / device.json / nebflow.json 等）永不入参**，
   * 越界 Read/Write 在 headless（-p）下按权限问询自动拒绝 = fail-closed。
   *  - `--permission-mode acceptEdits`：工作区（cwd ＋ add-dir 集）内文件编辑
   *    自动接受——headless 下问询即拒绝，不放开编辑外部执行器干不了活；
   *    白名单外工具（含工作区外写、网络工具）依旧显式拒绝。
   *  - `--allowedTools`：headless 工程活的常用命令白名单（构建 / 运行时 /
   *    只读检视件 / 进程收尾；git 按子命令枚举，push 不入枚举）。
   *    🔴 边界诚实声明（三段，均经真机实测）：① `--add-dir` 收窄**专用文件
   *    工具**（Read/Write/Edit）——白名单外路径读取被拒；② claude 2.x 对
   *    白名单内的文件命令件（cat / ls 等）同样施加工作区路径约束——工作区
   *    外 cat / ls 被拒，工作区内文件操作（含 rm）自动放行；③ 通用解释件
   *    （python3 / sed / awk 等）不受上述约束——工作区外路径访问放行。
   *    即：凭据路径对 ①② 封闭、对 ③ 不封闭；白名单是可用性让步，不是
   *    安全边界。
   *  - `--disallowedTools Bash(git push:*)`：零 push 纪律在执行器边界的
   *    显式闸（deny 优先于 allow；前缀形态匹配）。第二道 = git 子命令
   *    枚举：`git push` 与 `git -C <path> push` 均无 allow 匹配 ⇒
   *    自动拒绝。残余面 = 宿主 git 全局 alias 命名 push 等价形态（本闸
   *    不覆盖，依赖宿主配置面）。
   */
  object ClaudeCode extends ExternalExecutor:
    val id = "claude-code"
    val displayName = "Claude Code"

    /**
     * 放行目录集（spec 可驱动）：数据根下非凭据工作子目录（存在才放行）＋
     * `/tmp` 刮擦面（临时件落位约定面）。放行按**子目录点名**收窄，绝不整根
     * 放行——根级凭据件与备份件按路径形态天然排除在名单外。
     */
    private[executor] def grantDirs(root: os.Path): List[String] =
      val candidates = List("projects", "plugin-repos", "plugins", "docs", "general")
        .map(n => (root / n).toString) :+ "/tmp"
      candidates.filter(p => os.isDir(os.Path(p, os.root)))

    /**
     * headless 工程活的命令白名单（逗号分隔——pattern 内含空格，禁空格分隔）。
     *  git 按**子命令枚举**（本地读写与历史面）：push / remote / config /
     *  rebase / tag 与 `git -C` 形态不入枚举 ⇒ 无 allow 匹配，headless 下
     *  自动拒绝（枚举同时关掉 `-C <path> push` 这类绕行；deny 闸只按命令
     *  前缀匹配，罩不住它）。
     */
    private val allowedToolsArg: String = List(
      "Bash(git status:*)",
      "Bash(git add:*)",
      "Bash(git commit:*)",
      "Bash(git diff:*)",
      "Bash(git log:*)",
      "Bash(git show:*)",
      "Bash(git stash:*)",
      "Bash(git branch:*)",
      "Bash(git checkout:*)",
      "Bash(git switch:*)",
      "Bash(git restore:*)",
      "Bash(git init:*)",
      "Bash(git fetch:*)",
      "Bash(git pull:*)",
      "Bash(sbt:*)",
      "Bash(java:*)",
      "Bash(make:*)",
      "Bash(node:*)",
      "Bash(npm:*)",
      "Bash(npx:*)",
      "Bash(python3:*)",
      "Bash(ls:*)",
      "Bash(cat:*)",
      "Bash(head:*)",
      "Bash(tail:*)",
      "Bash(grep:*)",
      "Bash(rg:*)",
      "Bash(find:*)",
      "Bash(diff:*)",
      "Bash(sed:*)",
      "Bash(awk:*)",
      "Bash(wc:*)",
      "Bash(sort:*)",
      "Bash(mkdir:*)",
      "Bash(cp:*)",
      "Bash(mv:*)",
      "Bash(touch:*)",
      "Bash(kill:*)",
      "Bash(rm /tmp/:*)"
    ).mkString(",")

    def launchArgs(task: String, resumeSessionId: Option[String]): List[String] =
      val base = List("-p", task, "--output-format", "stream-json", "--verbose") :++
        List("--permission-mode", "acceptEdits") :++
        grantDirs(PathUtil.dataRoot).flatMap(d => List("--add-dir", d)) :++
        List("--allowedTools", allowedToolsArg, "--disallowedTools", "Bash(git push:*)")
      resumeSessionId.fold(base)(sid => base :++ List("--resume", sid))

    def parseLine(line: String): List[Frame] =
      parseJson(line).toList.flatMap { j =>
        j.hcursor.downField("type").as[String].toOption match
          case Some("system") if j.hcursor.downField("subtype").as[String].toOption.contains("init") =>
            List(Frame.Start(str(j, "session_id")))
          case Some("assistant") =>
            val message = j.hcursor.downField("message").focus.getOrElse(Json.Null)
            val content = message.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
            val blocks = content.zipWithIndex.flatMap { case (block, index) =>
              if str(block, "type").contains("text") then
                str(block, "text").map(text => Frame.ContentBlock(index, text)).toList
              else Nil
            }
            val tools = content.flatMap { block =>
              if str(block, "type").contains("tool_use") then str(block, "name").map(Frame.Tool.apply).toList
              else Nil
            }
            Frame.MessageComplete(str(message, "id"), blocks) :: tools
          case Some("result") =>
            val isErr = j.hcursor.downField("is_error").as[Boolean].toOption.getOrElse(false)
            val subtypeErr = j.hcursor.downField("subtype").as[String].toOption.exists(_.startsWith("error"))
            // The result UUID identifies the result event, not the assistant message.
            val messageId = str(j, "message_id").orElse(str(j, "assistant_message_id"))
            List(Frame.ExecutionEnd(isErr || subtypeErr, str(j, "result"), messageId, j))
          case _ => Nil
      }

    def interactiveResumeCommand(cliSessionId: Option[String]): String =
      cliSessionId.fold("claude")(sid => s"claude --resume $sid")
  end ClaudeCode

  // ── codex 适配器 ──────────────────────────────────────────────

  /**
   * codex：`codex exec --json <task>`（JSONL 事件流；`--skip-git-repo-check`
   * 使缺省 general 工作座（非 git 仓）不被 CLI 拒收——否则每个缺省派发都
   * fail 在 CLI 门上）。行格式：
   *   {"type":"thread.started","thread_id":"..."}
   *   {"type":"item.started","item":{"type":"command_execution","command":"..."}}
   *   {"type":"item.completed","item":{"type":"agent_message","text":"..."}}
   *   {"type":"turn.completed","usage":{...}} / {"type":"turn.failed","error":{...}}
   * 续聊：`codex exec resume <thread_id> <task>`。
   *
   * **网络前提（codex-spawn-fix 批 2026-10-19 实证）**：订阅模式直连 OpenAI
   * API——需代理可达，否则进程挂死（带 `https_proxy=http://127.0.0.1:7890`
   * 实测全通：SMOKE-OK / exit 0 / JSONL 正常）。引擎 spawn 不继承 shell rc
   * 的代理导出——经 nebflow.json `externalExecutors.proxyEnv` 点名注入
   * （[[ExternalSpawnConfig]]）。
   */
  object Codex extends ExternalExecutor:
    val id = "codex"
    val displayName = "Codex"

    def launchArgs(task: String, resumeSessionId: Option[String]): List[String] =
      resumeSessionId.fold(
        List("exec", "--json", "--skip-git-repo-check", task)
      )(tid => List("exec", "resume", "--json", tid, task))

    def parseLine(line: String): List[Frame] =
      parseJson(line).toList.flatMap { j =>
        j.hcursor.downField("type").as[String].toOption match
          case Some("thread.started") => List(Frame.Start(str(j, "thread_id")))
          case Some("item.started") =>
            j.hcursor.downField("item").focus.toList.collect {
              case item if item.hcursor.downField("type").as[String].toOption.contains("command_execution") =>
                val cmd = str(item, "command").getOrElse("")
                // label 取命令首词（进度行形态，与 agentToolStart label 同位）。
                Frame.Tool(cmd.split("\\s+").headOption.getOrElse("command"))
              case item if item.hcursor.downField("type").as[String].toOption.contains("mcp_tool_call") =>
                Frame.Tool(str(item, "tool").getOrElse("mcp_tool_call"))
            }
          case Some("item.completed") =>
            j.hcursor
              .downField("item")
              .focus
              .toList
              .collect {
                case item if item.hcursor.downField("type").as[String].toOption.contains("agent_message") =>
                  str(item, "text").toList.map { text =>
                    Frame.MessageComplete(str(item, "id"), List(Frame.ContentBlock(0, text)))
                  }
              }
              .flatten
          case Some("turn.failed") =>
            // Transport error envelopes are metadata, not assistant result text.
            List(Frame.ExecutionEnd(failed = true, metadata = j))
          case Some("turn.completed") =>
            List(Frame.ExecutionEnd(failed = false, metadata = j))
          case _ => Nil
      }

    def interactiveResumeCommand(cliSessionId: Option[String]): String =
      cliSessionId.fold("codex")(tid => s"codex resume $tid")
  end Codex

  // ── zcode 适配器 ──────────────────────────────────────────────

  /**
   * zcode：headless print 模式＝`zcode -p <task> --output-format stream-json`
   * （逐帧 JSONL 事件流——面板逐帧可见性面）。续聊＝`--resume <sid>` 前置
   * （zcode --resume 在册旗标）。
   *
   * == 输出方言（解析面兼容两种）==
   *  - stream-json（主形态，launchArgs 在用）：逐帧 JSONL 事件
   *    （`{type:"session.created"|"model.streaming"|"tool.updated"|
   *    "turn.failed"|…}`，payload.kind 驱动；`text_delta`→Text、
   *    `tool_call`/`tool_input_start`/`tool.updated kind started|scheduled`→Tool）
   *    ＋终局紧凑单行 `{type:"result",sessionId,response,…,projection{status}}`。
   *  - `--json` 摘要（兼容形态，解析保留）：turn 期间 stdout 静默，终局打印
   *    **单份 pretty JSON 摘要**（`JSON.stringify(x,null,2)+\n`）：
   *    `{sessionId, traceId, turnId?, response, usage?, eventCount,
   *    projection{status,…}}`。多行文档逐行进 [[parseLine]]：键级正则提取——
   *    JSON 字符串值转义后恒单行，行级提取可靠；`response` 值经 circe 再解析
   *    还原转义。失败路径不打印摘要（stderr＋exit≠0）＝runner 既有 exit 码
   *    fail-closed 兜底。
   *  - 行首可能残留 ANSI 控制序列（进度渲染面）：[[parseLine]] 入口统一剥离
   *    （[[AnsiEscape]]——raw ESC 只出现在 JSON 字符串值之外，剥离不伤载荷）。
   */
  object ZCode extends ExternalExecutor:
    val id = "zcode"
    val displayName = "ZCode"

    def launchArgs(task: String, resumeSessionId: Option[String]): List[String] =
      val base = List("-p", task, "--output-format", "stream-json")
      resumeSessionId.fold(base)(sid => List("--resume", sid) :++ base)

    // ANSI 清理（CSI / OSC / 双字符 ESC 形态）。raw ESC 在 JSON 行里只出现在
    // 字符串值之外（JSON 把控制字符转义成 \u001b 字面），解析前剥离绝不伤载荷。
    private val AnsiEscape = """\[[0-9;?]*[ -/]*[@-~]|\][^]*|[@-_]""".r

    def parseLine(line: String): List[Frame] =
      val cleaned = AnsiEscape.replaceAllIn(line, "")
      val trimmed = cleaned.trim
      if trimmed.isEmpty then Nil
      else
        parseJson(trimmed) match
          case Some(j) => parseFrameJson(j) // 方言一：整行 JSON（事件流 JSONL／紧凑 result 行）
          case None => parseSummaryLine(trimmed) // 方言二：--json pretty 摘要文档的键值行

    /** 方言一：JSONL 事件帧与紧凑 result 行。 */
    private def parseFrameJson(j: Json): List[Frame] =
      j.hcursor.downField("type").as[String].toOption match
        case Some("result") =>
          // 终局紧凑行：sessionId＝续聊锚点，response＝终稿文本。
          val sid = str(j, "sessionId")
          (if sid.isDefined then List(Frame.Start(sid)) else Nil) :+
            Frame.ExecutionEnd(
              failed = summaryFailed(j),
              result = str(j, "response"),
              messageId = str(j, "assistantMessageId"),
              metadata = j
            )
        case Some("session.created") =>
          str(j, "sessionId").map(sid => Frame.Start(Some(sid))).toList
        case Some("model.streaming") =>
          j.hcursor.downField("payload").focus.toList.flatMap { p =>
            p.hcursor.downField("kind").as[String].toOption match
              case Some("text_delta") =>
                p.hcursor.downField("delta").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Text.apply)
              case Some("start") =>
                List(Frame.MessageBoundary(str(p, "assistantMessageId")))
              case Some("finish") => List(Frame.MessageBoundary())
              case Some("tool_call") | Some("tool_input_start") =>
                p.hcursor.downField("toolName").as[String].toOption.toList.map(Frame.Tool.apply)
              case _ => Nil // Non-text content does not commit an assistant message.
          }
        case Some("tool.updated") =>
          j.hcursor
            .downField("payload")
            .focus
            .toList
            .collect {
              case p
                  if p.hcursor.downField("kind").as[String].toOption.exists(k => k == "started" || k == "scheduled") =>
                p.hcursor.downField("toolName").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Tool.apply)
            }
            .flatten
        case Some("turn.failed") =>
          // Transport error envelopes are metadata, not assistant result text.
          List(Frame.ExecutionEnd(failed = true, metadata = j))
        case _ => Nil

    /** 摘要 projection.status 有 error 词即带 failed（正常成功路径＝idle 等）。 */
    private def summaryFailed(j: Json): Boolean =
      j.hcursor
        .downField("projection")
        .downField("status")
        .as[String]
        .toOption
        .exists(_.equalsIgnoreCase("error"))

    // 方言二：pretty 摘要（JSON.stringify(x,null,2)）的一键一行形态；锚定行首键名，
    // 字符串值 JSON 转义后单行，行尾可有逗号。
    private val SessionIdLine = """^"sessionId"\s*:\s*"([^"]+)"\s*,?$""".r
    private val ResponseLine = """^"response"\s*:\s*"(.*)"\s*,?$""".r

    private def parseSummaryLine(trimmed: String): List[Frame] =
      trimmed match
        case SessionIdLine(sid) => List(Frame.Start(Some(sid)))
        case ResponseLine(escaped) =>
          // 值按 JSON 字符串字面量再解析还原转义（\" 与 \n 等）；坏形零帧不抛。
          io.circe.parser
            .parse("\"" + escaped + "\"")
            .toOption
            .flatMap(_.asString)
            .toList
            .map(text => Frame.ExecutionEnd(failed = false, result = Some(text)))
        case _ => Nil

    def interactiveResumeCommand(cliSessionId: Option[String]): String =
      cliSessionId.fold("zcode")(sid => s"zcode --resume $sid")
  end ZCode

  // ── 注册表 ────────────────────────────────────────────────────

  /**
   * 已建适配器（hermes 占位在 [[ExecutorRegistry]].defs，adapterReady=false，
   * 选择面显式拒绝——此处故意无占位对象）。
   */
  val all: List[ExternalExecutor] = List(ZCode, ClaudeCode, Codex)

  def forId(id: String): Option[ExternalExecutor] = all.find(_.id == id)

end ExternalExecutor
