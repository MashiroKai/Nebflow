package nebflow.core.ask

import cats.effect.IO
import io.circe.JsonObject
import nebflow.core.tools.ToolContext

import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · query-only Bash face（openspec canvas-ask-restricted-edit，
 * CAE-REQ-002，修订 DSA-SCN-007/008 的文件绑定 Ask Bash 行为）。
 *
 * 形态：默认拒绝 + 只读白名单**执行器**——不是在普通 shell 前面加一层校验，
 * 而是把通过校验的命令解析成 argv 管道后**不经任何 shell**直接执行
 * （os.proc 链），shell 元字符因此根本没有解释器可吃：
 *   - 首词白名单：grep/rg/ls/cat/head/tail/find/wc + stand-recall/stand-messages
 *     + git（仅子命令 log/show/diff/status/branch(仅列举)/for-each-ref/rev-parse，
 *     diff/log/show 拒 --output 写通道；env 钉 GIT_PAGER=cat PAGER=cat）；
 *   - 管道仅白名单段相连（未加引号的 `|` 分段，每段独立过白名单）；
 *   - 拒绝一切重定向与写元字符（> < >> tee 通道不存在——argv 直执行），
 *     拒绝 $ 与反引号（命令替换/环境展开一律拒，含引号内）、; & 换行组合；
 *   - 参数黑名单：find 禁 -exec/-execdir/-ok/-okdir（前缀匹配），
 *     rg 禁 --pre/--pre-command（含 = 值形态）；
 *   - 解析失败一律拒绝（fail-closed：未闭合引号、空段、超限、未知首词……）。
 *
 * 残余风险（openspec proposal「best-effort enforcement」如实申报）：这是应用层
 * 词法校验，不是操作系统沙箱；校验器自身的解析缺陷是残余风险面。已知的刻意
 * 收紧（非缺陷）：引号外的 glob 字符（* ? [ ]）不展开、按字面量传参；`$` 在
 * 任何位置（含单引号内）都拒绝；含 > < ; & 的合法只读命令（如 grep "a>b"）
 * 一并拒绝——宁拒不放行。
 * ═══════════════════════════════════════════════════════════════════════════ */
object AskBashGuard:

  /** 只读查询首词白名单（git 单列——还有子命令闸）。 */
  val ReadCommands: Set[String] =
    Set("grep", "rg", "ls", "cat", "head", "tail", "find", "wc")

  /** 引擎自带检索/消息 CLI（tools/stand-*；PATH 解析，缺件即命令报错）。 */
  val UtilityCommands: Set[String] = Set("stand-recall", "stand-messages")

  /**
   * git 仅放行的只读子命令。读面裁定（2026-11-23）增补 `branch`（仅列举形态，见
   * [[validateGitBranch]]）、`for-each-ref` / `rev-parse`（纯读引用/解析）。
   */
  val GitSubcommands: Set[String] = Set("log", "show", "diff", "status", "branch", "for-each-ref", "rev-parse")

  /** git diff/log/show 的 `--output` 会把结果写入任意文件（写通道）——整族拒。 */
  val GitWriteFlagPrefixes: List[String] = List("--output")

  /** `git branch` 只放行列举形态的开关（无任何建/删/改/移/设上游开关）。 */
  val GitBranchListFlags: Set[String] =
    Set("-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "-l", "--list", "--show-current", "--no-color")

  /** `git branch` 的过滤开关（带一个值；值必须是引用名，不得以 `-` 开头）。 */
  val GitBranchFilterFlags: Set[String] = Set("--contains", "--merged", "--no-merged", "--points-at")

  /**
   * `git branch` 只读列举闸：允许开关 ∈ 列举/过滤集；过滤开关消费下一词（或 `=值`）；
   * 位置参数仅在 `--list`/`-l` 形态下允许（作为 glob 过滤，非建分支名）。其余一律拒。
   */
  def validateGitBranch(args: Vector[String]): Either[String, Unit] =
    val refused = Left(
      "git branch is limited to listing forms (no create/delete/rename/copy/set-upstream): refused `git branch " +
        args.mkString(" ") + "`"
    )
    var listing = false
    var ok = true
    var i = 0
    while ok && i < args.size do
      val t = args(i)
      if GitBranchListFlags.contains(t) then
        if t == "--list" || t == "-l" then listing = true
        i += 1
      else if t.startsWith("--format=") || t.startsWith("--sort=") || t.startsWith("--color") then i += 1
      else if GitBranchFilterFlags.contains(t) then
        if i + 1 < args.size && !args(i + 1).startsWith("-") then i += 2
        else ok = false
      else if GitBranchFilterFlags.exists(f => t.startsWith(f + "=")) then
        if t.indexOf('=') < t.length - 1 then i += 1 else ok = false
      else if !t.startsWith("-") then if listing then i += 1 else ok = false
      else ok = false
    end while
    if ok then Right(()) else refused
  end validateGitBranch

  /** git 环境钉死（分页器禁用——非交互通道吃不下 pager）。 */
  val QueryEnv: Map[String, String] = Map("GIT_PAGER" -> "cat", "PAGER" -> "cat")

  /** 命令长度 / 管道段 / 每段 token 数上限（防解析面被灌爆）。 */
  val MaxCommandChars: Int = 2000
  val MaxPipelineSegments: Int = 8
  val MaxTokensPerSegment: Int = 64

  /**
   * 原始扫描即拒的字符（任何位置、含引号内；fail-closed）。
   * `$` 反引号 = 替换/展开；`;` `&` = 组合；`>` `<` = 重定向；`\n` = 多行。
   */
  private val RawForbiddenChars: Set[Char] = Set('$', '`', ';', '&', '>', '<', '\n', '\r')

  /** 校验产物：argv 管道（每段 = 命令 + 参数，无任何 shell 解释）。 */
  final case class Validated(pipeline: Vector[Vector[String]]):
    /** 显示面（summarize 用；不含引号还原语义，仅回显）。 */
    def display: String = pipeline.map(_.mkString(" ")).mkString(" | ")

  /** 校验入口：拒绝返回 Left(原因)（fail-closed——解析不出就拒）。 */
  def validate(command: String): Either[String, Validated] =
    if command == null then Left("empty command")
    else if command.trim.isEmpty then Left("empty command")
    else if command.length > MaxCommandChars then Left(s"command longer than $MaxCommandChars chars")
    else if command.exists(RawForbiddenChars.apply) then
      Left(
        "write/expansion metacharacter refused (allowlist is query-only: no $, backtick, ;, &, >, < or newlines)"
      )
    else
      splitPipeline(command).flatMap { segments =>
        segments
          .map(validateSegment)
          .collectFirst { case Left(why) => Left(why) }
          .getOrElse {
            if segments.isEmpty then Left("empty command")
            else if segments.size > MaxPipelineSegments then Left(s"more than $MaxPipelineSegments pipeline segments")
            else Right(Validated(segments))
          }
      }
  end validate

  /** 按未加引号的 `|` 分段（引号内 `|` 是正则字面量，不分段）。 */
  private def splitPipeline(command: String): Either[String, Vector[Vector[String]]] =
    val segments = Vector.newBuilder[Vector[String]]
    val tokens = Vector.newBuilder[String]
    val cur = new StringBuilder
    var inSingle = false
    var inDouble = false
    var i = 0
    def endToken(): Unit =
      if cur.nonEmpty then tokens += cur.result()
      cur.clear()
    def endSegment(): Either[String, Vector[Vector[String]]] =
      endToken()
      val toks = tokens.result()
      if toks.isEmpty then Left("empty pipeline segment")
      else if toks.size > MaxTokensPerSegment then Left(s"segment has more than $MaxTokensPerSegment tokens")
      else
        segments += toks
        tokens.clear()
        Right(segments.result())
    while i < command.length do
      val c = command.charAt(i)
      if inSingle then
        if c == '\'' then inSingle = false else cur.append(c)
        i += 1
      else if inDouble then
        if c == '"' then inDouble = false else cur.append(c)
        i += 1
      else
        c match
          case '\'' =>
            inSingle = true; i += 1
            // 紧贴前文的引号（a'.'b）同 token 续读——与 shell 同形。
          case '"' =>
            inDouble = true; i += 1
          case '\\' =>
            if i + 1 >= command.length then return Left("trailing backslash")
            else
              cur.append(command.charAt(i + 1)); i += 2
          case '|' =>
            endSegment() match
              case Left(why) => return Left(why)
              case Right(_) => ()
            i += 1
          case ch if ch.isWhitespace => endToken(); i += 1
          case ch => cur.append(ch); i += 1
      end if
    end while
    if inSingle || inDouble then Left("unterminated quote")
    else endSegment()
  end splitPipeline

  /** 单段校验：首词白名单 + git 子命令闸 + 参数黑名单。 */
  private def validateSegment(tokens: Vector[String]): Either[String, Vector[String]] =
    val head = tokens.head
    def forbid(why: String): Left[String, Vector[String]] = Left(why)
    if head == "git" then
      val sub = tokens.drop(1).headOption.getOrElse("")
      if !GitSubcommands.contains(sub) then
        forbid(
          s"git is limited to read-only subcommands (${GitSubcommands.toList.sorted.mkString("/")}): refused `git ${sub.trim}`".trim
        )
      else
        val args = tokens.drop(2)
        args.find(t => GitWriteFlagPrefixes.exists(p => t == p || t.startsWith(p + "="))) match
          case Some(t) => forbid(s"git argument refused: $t (--output writes to an arbitrary file)")
          case None =>
            if sub == "branch" then validateGitBranch(args).map(_ => tokens)
            else Right(tokens)
    else if ReadCommands.contains(head) || UtilityCommands.contains(head) then
      val argFault: Option[String] = head match
        case "find" =>
          tokens
            .drop(1)
            .find(t => t.startsWith("-exec") || t.startsWith("-ok"))
            .map(t => s"find argument refused: $t (no -exec/-execdir/-ok/-okdir)")
        case "rg" =>
          tokens
            .drop(1)
            .find(t => t == "--pre" || t.startsWith("--pre=") || t == "--pre-command" || t.startsWith("--pre-command="))
            .map(t => s"rg argument refused: $t (no --pre/--pre-command)")
        case _ => None
      argFault match
        case Some(why) => forbid(why)
        case None => Right(tokens)
    else
      forbid(
        s"command not in the query-only allowlist (grep/rg/ls/cat/head/tail/find/wc, git log/show/diff/status/branch(list)/for-each-ref/rev-parse, stand-recall, stand-messages): refused `${head}`"
      )
    end if
  end validateSegment

end AskBashGuard

/* ═══════════════════════════════════════════════════════════════════════════
 * 校验通过后的执行腿：os.proc 链（无 shell）——段间 stdout → stdin 串接，
 * 环境钉 GIT_PAGER/PAGER=cat，超时硬杀（查询面不给长跑）。
 * ═══════════════════════════════════════════════════════════════════════════ */
object AskBashExecutor:

  /** 单段/整链超时上限（查询命令是秒级；30s 已宽）。 */
  val TimeoutMsCap: Long = 30000L
  val TimeoutMsDefault: Long = 30000L

  /** 输出内存帽（chars；超出截断——grep 大文件不拖垮会话）。 */
  val MaxOutputChars: Int = 200000

  /** 执行 argv 管道。cwd = 会话 cwd（file 域 = 工作区根，见 AskSessions.sessionCwd；缺省进程 cwd）。 */
  def run(
    validated: AskBashGuard.Validated,
    cwd: Option[String],
    timeoutMs: Long
  ): IO[Either[String, String]] =
    val cwdPath = cwd.filter(_.trim.nonEmpty).map(os.Path(_, os.pwd))
    val timeout = math.max(1000L, math.min(timeoutMs, TimeoutMsCap))
    IO.blocking {
      try
        var carried = "" // 上一段 stdout → 下一段 stdin
        var lastErr = ""
        var lastCode = 0
        validated.pipeline.foreach { segment =>
          val res = os
            .proc(segment)
            .call(
              cwd = cwdPath.getOrElse(os.pwd),
              env = AskBashGuard.QueryEnv,
              stdin = carried,
              timeout = timeout,
              check = false,
              mergeErrIntoOut = false
            )
          carried = res.out.text()
          lastErr = res.err.text()
          lastCode = res.exitCode
          if lastCode != 0 then throw new RuntimeException(s"segment `${segment.head}` exited ${lastCode}")
        }
        val out = cap(carried)
        val err = cap(lastErr)
        val suffix =
          (if err.nonEmpty then s"\n[stderr]\n$err" else "") +
            (if lastCode != 0 then s"\n[exit ${lastCode}]" else "")
        Right(if suffix.isEmpty then out else out + suffix)
      catch
        case _: java.util.concurrent.TimeoutException =>
          Left(s"query timed out after ${timeout}ms: ${validated.display}")
        case NonFatal(e) =>
          Left(s"query failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)} — ${validated.display}")
    }
  end run

  private def cap(text: String): String =
    if text.length <= MaxOutputChars then text else text.take(MaxOutputChars) + s"\n…（输出超 ${MaxOutputChars} chars 截断）"

end AskBashExecutor

/**
 * post-hook pre-routing 边界的 ask Bash 单点（AgentSessionExecution 消费）。
 *
 * ask-scope 批（2026-11-18）：本面是**只读执行器**，不是写通道（无 shell ⇒ 无
 * 重定向/无替换/无组合），因此不参与 AskPathBoundary 的路径判决；但**每一次拒绝
 * 都落 [[AskWriteAudit]]**（身份 / 工具 / 原始命令 / 时间 / 结果 / 理由）——
 * 「用 shell 试写别的文件」是有审计留痕的越权尝试，不是无声失败。放行不记账
 * （只读查询是正常业务，记账会把审计面灌成流水；判决面仍逐条可复核）。
 */
object AskBashFace:

  /** 入参抽取 + 校验 + 执行。返回 (result, isError)。 */
  def runAskBash(input: JsonObject, ctx: ToolContext): IO[(String, Boolean)] =
    val command = input("command").flatMap(_.asString).getOrElse("")
    val background = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    val timeoutMs = input("timeout")
      .flatMap(_.asNumber.flatMap(_.toLong))
      .getOrElse(AskBashExecutor.TimeoutMsDefault)
    val refusalPrefix = "Ask Bash refused (file-bound query-only face): "
    val identity = ctx.sessionId.getOrElse("")
    def refused(reason: String): IO[(String, Boolean)] =
      val msg = refusalPrefix + reason
      AskWriteAudit.record(
        identity = identity,
        tool = "Bash",
        requested = command,
        canonical = "",
        allowed = false,
        reason = msg
      )
      IO.pure((msg, true))
    if command.trim.isEmpty then IO.pure((refusalPrefix + "empty command", true))
    else if background then refused("background execution is not available on the query-only face")
    else
      AskBashGuard.validate(command) match
        case Left(why) => refused(why)
        case Right(validated) =>
          AskBashExecutor
            .run(validated, ctx.sessionCwd, timeoutMs)
            .map {
              case Right(out) => (out, false)
              case Left(why) => (refusalPrefix + why, true)
            }
  end runAskBash

end AskBashFace
