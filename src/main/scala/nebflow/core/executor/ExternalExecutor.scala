package nebflow.core.executor

import io.circe.Json
import io.circe.parser.parse

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

  end Frame

  /** 单个外部执行器适配器。 */
  trait ExternalExecutor:
    def id: String
    def displayName: String

    /** 启动 argv：首元素为可执行名（检测路径由 runner 解析后替换）。 */
    def launchArgs(task: String, resumeSessionId: Option[String]): List[String]

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
   * （现读宿主 claude --help 核对：-p/--print、--output-format stream-json、
   * --verbose、--resume <id> 均在册）。行格式（stream-json）：
   *   {"type":"system","subtype":"init","session_id":"..."}
   *   {"type":"assistant","message":{"content":[{"type":"text","text":...},
   *        {"type":"tool_use","name":"Bash",...}]}}
   *   {"type":"result","subtype":"success","result":"...","is_error":false}
   */
  object ClaudeCode extends ExternalExecutor:
    val id = "claude-code"
    val displayName = "Claude Code"

    def launchArgs(task: String, resumeSessionId: Option[String]): List[String] =
      val base = List("-p", task, "--output-format", "stream-json", "--verbose")
      resumeSessionId.fold(base)(sid => base :++ List("--resume", sid))

    def parseLine(line: String): List[Frame] =
      parseJson(line).toList.flatMap { j =>
        j.hcursor.downField("type").as[String].toOption match
          case Some("system") if j.hcursor.downField("subtype").as[String].toOption.contains("init") =>
            List(Frame.Start(str(j, "session_id")))
          case Some("assistant") =>
            val content = j.hcursor.downField("message").downField("content")
            content
              .as[List[Json]]
              .toOption
              .getOrElse(Nil)
              .collect {
                case block if block.hcursor.downField("type").as[String].toOption.contains("text") =>
                  block.hcursor.downField("text").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Text.apply)
                case block if block.hcursor.downField("type").as[String].toOption.contains("tool_use") =>
                  block.hcursor.downField("name").as[String].toOption.toList.map(Frame.Tool.apply)
              }
              .flatten
          case Some("result") =>
            val text = str(j, "result").getOrElse("")
            val isErr = j.hcursor.downField("is_error").as[Boolean].toOption.getOrElse(false)
            val subtypeErr = j.hcursor.downField("subtype").as[String].toOption.exists(_.startsWith("error"))
            List(Frame.Final(text, failed = isErr || subtypeErr))
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
                  item.hcursor.downField("text").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Text.apply)
              }
              .flatten
          case Some("turn.failed") =>
            val msg = j.hcursor
              .downField("error")
              .downField("message")
              .as[String]
              .toOption
              .orElse(j.hcursor.downField("error").as[String].toOption)
              .getOrElse("turn failed")
            List(Frame.Final(msg, failed = true))
          case _ => Nil
      }

    def interactiveResumeCommand(cliSessionId: Option[String]): String =
      cliSessionId.fold("codex")(tid => s"codex resume $tid")
  end Codex

  // ── zcode 适配器 ──────────────────────────────────────────────

  /**
   * zcode：headless print 模式（[[ExecutorRegistry]] 描述面口径）＝
   * `zcode -p <task> --json`（zcode-shim 联调批晨裁①-B 2026-10-07：旗标撤旧换
   * `--json`）。续聊＝`--resume <sid>` 前置（zcode --resume 在册旗标）。
   *
   * == 输出实况（zcode 0.16.9 bundle 走查＋装机 `--json` 惯例实测，2026-10-07）==
   *  - `--json`（options.json=true，outputFormat 未定义 ⇒ wantsJsonSummary）：
   *    turn 期间 stdout 静默，终局打印**单份 pretty JSON 摘要**（bundle `e0`＝
   *    `formatJson`＝`JSON.stringify(x,null,2)+\n`；`plugins list --json` 装机
   *    同格式实证）：`{sessionId, traceId, turnId?, response, usage?, eventCount,
   *    projection{status,…}}`。多行文档逐行进 [[parseLine]]：键级正则提取——
   *    JSON 字符串值转义后恒单行（raw 换行不会出现在值内），行级提取可靠；
   *    `response` 值经 circe 再解析还原转义。失败路径不打印摘要（stderr＋
   *    exit≠0）＝runner 既有 exit 码 fail-closed 兜底，此处零补丁。
   *  - 兼容方言（同窗解析）：`--output-format stream-json`（**隐藏旗标**——0.16.9
   *    help 不列，bundle parseArgs 白名单 `["text","json","stream-json"]` 在册，
   *    wantsEventStream 语义）＝逐帧 JSONL 事件流（`{type:"session.created"|
   *    "model.streaming"|"tool.updated"|"turn.failed"|…}`，payload.kind 驱动）
   *    ＋终局紧凑单行 `{type:"result",sessionId,response,…}`。候裁如复审旗标
   *    选型（stream-json 有逐帧流式进度），仅 launchArgs 一行翻转，解析面不动。
   */
  object ZCode extends ExternalExecutor:
    val id = "zcode"
    val displayName = "ZCode"

    def launchArgs(task: String, resumeSessionId: Option[String]): List[String] =
      val base = List("-p", task, "--json")
      resumeSessionId.fold(base)(sid => List("--resume", sid) :++ base)

    def parseLine(line: String): List[Frame] =
      val trimmed = line.trim
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
            Frame.Final(str(j, "response").getOrElse(""), failed = summaryFailed(j))
        case Some("session.created") =>
          str(j, "sessionId").map(sid => Frame.Start(Some(sid))).toList
        case Some("model.streaming") =>
          j.hcursor.downField("payload").focus.toList.flatMap { p =>
            p.hcursor.downField("kind").as[String].toOption match
              case Some("text_delta") =>
                p.hcursor.downField("delta").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Text.apply)
              case Some("tool_call") | Some("tool_input_start") =>
                p.hcursor.downField("toolName").as[String].toOption.toList.map(Frame.Tool.apply)
              case _ => Nil // reasoning_* / start / end / tool_input_delta 等零帧
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
          val payload = j.hcursor.downField("payload")
          val msg = payload.focus
            .flatMap(p => str(p, "message"))
            .orElse(payload.downField("error").focus.flatMap(p => str(p, "message")))
            .getOrElse("turn failed")
          List(Frame.Final(msg, failed = true))
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
            .map(text => Frame.Final(text, failed = false))
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
