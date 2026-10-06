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

    /** 终局帧：CLI final 文本（结果取 CLI final 文本——原书不变）。failed=true
      * 表示流面显式报错（result error / turn.failed）。 */
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
    val id                    = "claude-code"
    val displayName           = "Claude Code"

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
            content.as[List[Json]].toOption.getOrElse(Nil).collect {
              case block if block.hcursor.downField("type").as[String].toOption.contains("text") =>
                block.hcursor.downField("text").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Text.apply)
              case block if block.hcursor.downField("type").as[String].toOption.contains("tool_use") =>
                block.hcursor.downField("name").as[String].toOption.toList.map(Frame.Tool.apply)
            }.flatten
          case Some("result") =>
            val text  = str(j, "result").getOrElse("")
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
   */
  object Codex extends ExternalExecutor:
    val id          = "codex"
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
            j.hcursor.downField("item").focus.toList.collect {
              case item if item.hcursor.downField("type").as[String].toOption.contains("agent_message") =>
                item.hcursor.downField("text").as[String].toOption.filter(_.nonEmpty).toList.map(Frame.Text.apply)
            }.flatten
          case Some("turn.failed") =>
            val msg = j.hcursor.downField("error").downField("message").as[String].toOption
              .orElse(j.hcursor.downField("error").as[String].toOption).getOrElse("turn failed")
            List(Frame.Final(msg, failed = true))
          case _ => Nil
      }

    def interactiveResumeCommand(cliSessionId: Option[String]): String =
      cliSessionId.fold("codex")(tid => s"codex resume $tid")
  end Codex

  // ── zcode 适配器 ──────────────────────────────────────────────

  /**
   * zcode：headless print 模式（[[ExecutorRegistry]] 描述面口径）＝
   * `zcode -p <task> --output-format stream-json`（claude-code 同族 CLI 形态；
   * 宿主无 PATH zcode 可回放，精确旗标以装机联调复核——本批 fixture 回放按
   * 同族格式驱动，见 ExternalExecutorSpec）。续聊：`zcode --resume <sid>`。
   */
  object ZCode extends ExternalExecutor:
    val id          = "zcode"
    val displayName = "ZCode"

    def launchArgs(task: String, resumeSessionId: Option[String]): List[String] =
      val base = List("-p", task, "--output-format", "stream-json")
      resumeSessionId.fold(base)(sid => List("--resume", sid) :++ base)

    def parseLine(line: String): List[Frame] =
      // 同族 stream-json：解析核与 claude-code 共形（headless print 输出）。
      ClaudeCode.parseLine(line)

    def interactiveResumeCommand(cliSessionId: Option[String]): String =
      cliSessionId.fold("zcode")(sid => s"zcode --resume $sid")
  end ZCode

  // ── 注册表 ────────────────────────────────────────────────────

  /** 已建适配器（hermes 占位在 [[ExecutorRegistry]].defs，adapterReady=false，
    * 选择面显式拒绝——此处故意无占位对象）。 */
  val all: List[ExternalExecutor] = List(ZCode, ClaudeCode, Codex)

  def forId(id: String): Option[ExternalExecutor] = all.find(_.id == id)

end ExternalExecutor
