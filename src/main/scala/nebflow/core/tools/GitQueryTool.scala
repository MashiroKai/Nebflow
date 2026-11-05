package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.ask.AskSessions
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets.UTF_8

/* ═══════════════════════════════════════════════════════════════════════════
 * GitQuery —— ask 会话的**只读 git 查询**工具（gitquery 批 2026-11-05，作者令
 * 「ask 会话新增只读 GitQuery 工具，按你的思路来」）。
 *
 * 动因（本批背景，已核实）：ask 七件工具（Read / Glob / Grep / WebSearch /
 * WebFetch / message_record_search / send_message_to_main_chat）**零执行能力**。
 * 分身的主职是「文档 vs 代码对账」，而审计文末普遍挂提交号与可复跑命令
 * （`874e8be1f`、`a7ccb6df4`、`merge-base exit=128`），分身一条都复跑不了，
 * 只能转述文件里写死的值 ⇒ 可信度上限停在「文件说」。本工具把「文件说」抬到
 * 「我查了」。
 *
 * ── 一、不是 Bash 开口子（硬要求 1）──────────────────────────────────
 * schema 只暴露 `subcommand` 封闭枚举 + 六个**结构化**参数（repo / path / rev /
 * rev2 / limit / format，外加 fleet 的 device 自消费参数）；**零自由 argv 透传**：
 * 没有任何 `args` / `argv` / `cmd` 属性，`additionalProperties = false`，argv 全部
 * 由本文件的 [[argvFor]] 逐字拼装。禁一切选项注入面：`rev` / `path` 均**拒绝以
 * `-` 开头**（`-c`、`--exec-path`、`--upload-pack`、`--output` 都是这样进来的），
 * 拒绝 `::`（`ext::sh -c …` 传输语法）、拒绝控制字符与 shell 元字符；`rev` 连
 * `:` 都拒绝（blob 只能经显式 `path` 参数表达 ⇒ `rev:path` 无法被冒号走私）。
 *
 * ── 二、v1 子命令恰四件（硬要求 2）──────────────────────────────────
 *   - `log`        `-n` ≤ [[MaxLogLimit]]（50）、固定 format 档（sha/date/author/
 *                  subject，`--date=short`）、可按 `path` 收窄（`--` 分隔）；
 *   - `show`       `--stat`（无 path）或 `rev:path`（有 path）；
 *   - `diff`       `--stat` / `--name-status`，**强制 `--no-ext-diff --no-textconv`**；
 *   - `merge-base` `--is-ancestor`，对外返回**布尔**（`is-ancestor: true|false`），
 *                  绝不把裸 exit code 甩给模型。
 * v1.5 候选（本批**不实现**，只登记在 [[V15Candidates]]）：rev-parse / cat-file
 * (`-t`/`-s`) / branch --list --format / tag --list / status --porcelain
 * --no-optional-locks。永不进白名单（[[NeverAllow]]）：fetch / push / pull /
 * checkout / switch / reset / clean / stash / gc / worktree / submodule / config。
 *
 * ── 三、双保险（硬要求 3）──────────────────────────────────────────────
 * 挂载面 = `AskSessions.AllowedTools`（经 `AskExclusiveTools` 反剥集单点，ask 会话
 * 早退集唯一来源；其余身份连名字都被摘）。工具内侧 = `ctx.sessionId` 同判据
 * （`AskSessions.isAskSession`，`ask-` 前缀单点）拒绝一切非 ask 身份调用——含
 * REST 直调与 agent.json 声明（TaskBoard / node_report / delegate_report 同款纪律，
 * **DUAL 风格显式错误** GITQUERY_FORBIDDEN）。
 *
 * ── 四、输出纪律（硬要求 4）────────────────────────────────────────────
 * **每次回执首行恒为「解析到的仓根」**（`repo: <abs>`——防在错的仓里给错答案，
 * 分身最常见的错是「项目根 ≠ 仓根」）；正文截断 ≈ [[MaxOutputLines]] 行 /
 * [[MaxOutputBytes]] 字节并带边界读数；非 git 仓 / 路径不存在 / 参数越界一律给
 * **可读回执**（`GitQuery refused: … (GITQUERY_*)`），不抛裸异常、不给裸 exit code。
 *
 * ── 五、设备（硬要求 5）────────────────────────────────────────────────
 * v1 只跑本机：`device` 是**自消费参数**（缺省 / `local` 放行；其它设备名显式
 * 拒绝并指向 v2），不注册进 `RemoteExecutor.remoteableTools`（跨设备 worktree
 * 历史查询 = v2）。`RemoteExecutor.schemaDeclaresDeviceParam` 判据据此豁免
 * stray 告警（device 是本工具自己的业务入参，不是误投意图）。
 *
 * ── 六、逃逸面硬化（本批最关键的证明；见 spec T6）──────────────────────
 * 除 `--no-ext-diff` / `--no-textconv` 两道 diff 机制闸外，进程环境**钉死**
 * （[[PinnedEnv]]）：关掉 global/system 配置、`core.fsmonitor=false`（**实测：
 * `git diff <rev>` 会执行仓库级 `core.fsmonitor` 命令**——这是 `--no-ext-diff`
 * 盖不住的真逃逸面）、`GIT_OPTIONAL_LOCKS=0`（只读查询不抢索引锁）、
 * `GIT_NO_REPLACE_OBJECTS=1`（replace refs 不得伪造历史）、`GIT_TERMINAL_PROMPT=0`。
 * 这些值全部是引擎侧硬编码常量，**与模型输入无关** ⇒ 不构成第二参数通道。
 * ═══════════════════════════════════════════════════════════════════════════ */

object GitQueryTool extends Tool:

  val Name: String = "GitQuery"

  /** v1 子命令封闭枚举（schema `enum` 与执行面同源，禁第二份）。 */
  val Subcommands: List[String] = List("log", "show", "diff", "merge-base")

  /** v1.5 候选（本批**不实现**，只登记——见文件头 §二）。 */
  val V15Candidates: List[String] = List("rev-parse", "cat-file", "branch", "tag", "status")

  /** **永不进白名单**的写面 / 网络面 / 配置面（登记即断言：与 [[Subcommands]] 零交集）。 */
  val NeverAllow: Set[String] =
    Set(
      "fetch",
      "push",
      "pull",
      "checkout",
      "switch",
      "reset",
      "clean",
      "stash",
      "gc",
      "worktree",
      "submodule",
      "config"
    )

  /** `log -n` 上限（硬要求 2）；超出即钳到上限并回执给出读数。 */
  val MaxLogLimit: Int = 50

  /** `log -n` 缺省值。 */
  val DefaultLogLimit: Int = 20

  /** 回执正文行上限（硬要求 4）。 */
  val MaxOutputLines: Int = 200

  /** 回执正文字节上限（硬要求 4）。 */
  val MaxOutputBytes: Int = 8 * 1024

  /** 单次 git 调用超时（只读查询 = 秒级；挂死即报错，不拖死问答轮）。 */
  val TimeoutMs: Int = 20000

  /** 单参数长度上限（防超长 token 撑 argv）。 */
  val MaxTokenLength: Int = 200

  /** 缺省 rev（show / merge-base 的 descendant / diff 的单端）。 */
  val DefaultRev: String = "HEAD"

  override def name: String = Name

  override def description: String =
    """Read-only git query on a repository — log / show / diff / merge-base only (no shell, no arbitrary argv, no subcommand outside the enum). Use it to re-run the git evidence an audit or design doc cites (commit shas, ancestor checks, file history) instead of restating values the document wrote down. Every receipt's first line is the resolved repository root — check it before trusting the answer. Output is clipped at ~200 lines / 8KB with a boundary reading. `device` is local-only in v1."""

  /* ── schema：零 argv 透传面（硬要求 1 的证明点）────────────────────── */

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "subcommand" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> Json.arr(Subcommands.map(_.asJson)*),
        "description" -> "Which query. log | show | diff | merge-base. Nothing else is accepted.".asJson
      ),
      "repo" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Repository path (absolute, or relative to the project root). Defaults to the current project root.".asJson
      ),
      "path" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Path restriction (repo-relative). log/diff: pathspec filter; show: names a file inside rev (rev:path form).".asJson
      ),
      "rev" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Revision expression (sha / branch / tag / HEAD~2). show/diff: defaults to HEAD; merge-base: the possibly-ancestor side (required).".asJson
      ),
      "rev2" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Second revision — diff: the other side; merge-base: the possibly-descendant side (default HEAD).".asJson
      ),
      "limit" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> s"log only: max commits (default $DefaultLogLimit, hard cap $MaxLogLimit).".asJson
      ),
      "format" -> Json.obj(
        "type" -> "string".asJson,
        "enum" -> Json.arr("stat".asJson, "name-status".asJson),
        "description" -> "diff only: stat (default) or name-status.".asJson
      ),
      "device" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Target device. v1 runs locally only — pass \"local\" (or omit). Any other device is refused.".asJson
      )
    ),
    "required" -> Json.arr("subcommand".asJson),
    // 零自由透传：未知键直接拒绝（node_report / delegate_report 同款封闭面纪律）。
    "additionalProperties" -> false.asJson
  )

  override def summarize(input: JsonObject): String =
    val sub = input("subcommand").flatMap(_.asString).getOrElse("?")
    val path = input("path").flatMap(_.asString).map(p => s" path=$p").getOrElse("")
    val rev = input("rev").flatMap(_.asString).map(r => s" rev=$r").getOrElse("")
    s"GitQuery($sub$rev$path)"

  override def summarizeResult(input: JsonObject, result: String): String =
    val head = result.linesIterator.take(2).mkString(" · ")
    if head.length > 200 then head.take(197) + "..." else head

  /* ═══════════════════════════════════════════════════════════════════
   * 进程环境（逃逸面硬化，见文件头 §六）——全部引擎侧常量，零模型输入。
   * ═══════════════════════════════════════════════════════════════════ */

  private def devNull: String =
    if sys.props.getOrElse("os.name", "").toLowerCase.contains("win") then "NUL" else "/dev/null"

  /** 钉死环境：关配置逃逸面 + 只读语义。经 `os.proc.call(env = …)` 叠加在继承环境上。 */
  def PinnedEnv: Map[String, String] =
    Map(
      "GIT_TERMINAL_PROMPT" -> "0",
      "GIT_ASKPASS" -> "true",
      "GIT_PAGER" -> "cat",
      "PAGER" -> "cat",
      "GIT_OPTIONAL_LOCKS" -> "0", // 只读查询不抢索引锁（与 status --no-optional-locks 同义）
      "GIT_NO_REPLACE_OBJECTS" -> "1", // replace refs 不得伪造历史（对账要真对象）
      "GIT_CONFIG_NOSYSTEM" -> "1", // 系统级配置不入场
      "GIT_CONFIG_GLOBAL" -> devNull, // 用户级配置不入场（含 diff.external / alias / include）
      // 仓库级配置里唯一还能执行命令的键（实测 `git diff <rev>` 会调用它）——关掉。
      "GIT_CONFIG_COUNT" -> "1",
      "GIT_CONFIG_KEY_0" -> "core.fsmonitor",
      "GIT_CONFIG_VALUE_0" -> "false"
    )

  /* ═══════════════════════════════════════════════════════════════════
   * 参数校验（硬要求 1：零 argv 透传的落地判据）
   * ═══════════════════════════════════════════════════════════════════ */

  /** rev 的字符白名单（`:` 不在内 ⇒ `rev:path` 只能经显式 `path` 参数表达）。 */
  private val RevAllowed: String => Boolean = s =>
    s.nonEmpty && s.forall(c => c.isLetterOrDigit || "._/@{}^~-".contains(c))

  /** 公共前筛：拒绝空 / 超长 / 前导 `-`（选项注入）/ 控制字符 / 传输语法。 */
  private def tokenProblem(raw: String, label: String): Option[String] =
    if raw.isEmpty then Some(s"`$label` must be a non-empty string.")
    else if raw.length > MaxTokenLength then Some(s"`$label` is too long (> $MaxTokenLength chars).")
    else if raw.startsWith("-") then
      Some(s"`$label` must not start with '-' (option injection: -c / --exec-path / --upload-pack).")
    else if raw.exists(c => c.isControl || c == '\u0000') then Some(s"`$label` contains a control character.")
    else if raw.contains("::") then Some(s"`$label` contains '::' (transport syntax such as ext:: is not allowed).")
    else None

  /** rev 校验：先过公共前筛，再过字符白名单。 */
  def revProblem(raw: String, label: String = "rev"): Option[String] =
    tokenProblem(raw, label).orElse(
      Option.unless(RevAllowed(raw))(
        s"`$label` = \"$raw\" is not a plain revision expression (allowed: letters digits . _ / @ { } ^ ~ -)."
      )
    )

  /** path 校验：放开空格与 glob 元字符（repo 名常带空格、pathspec 常带 `*`），拒绝 shell 面。 */
  private val PathForbidden: Set[Char] = Set(':', '\\', '"', '\'', '`', '$', ';', '|', '&', '<', '>', '\n', '\r')

  def pathProblem(raw: String, label: String = "path"): Option[String] =
    tokenProblem(raw, label).orElse {
      raw.find(PathForbidden.contains) match
        case Some(c) => Some(s"`$label` contains a character that is not allowed in a path: '$c'.")
        case None    => None
    }

  /* ═══════════════════════════════════════════════════════════════════
   * argv 拼装（唯一 argv 来源；纯函数 ⇒ spec 可逐字钉死）
   * ═══════════════════════════════════════════════════════════════════ */

  /** diff / show 的 diff 机制闸（硬要求 2；v1.5 若开 patch 档自动继承）。 */
  val DiffGuards: List[String] = List("--no-ext-diff", "--no-textconv")

  /** 固定 format 档（log）：短 sha · 短日期 · 作者 · 标题。 */
  val LogFormat: String = "--format=%h %ad %an %s"

  /**
   * 子命令 argv（**不含** `git -C <root>` 前缀——由 [[argvFor]] 拼）。
   * Left = 参数越界（可读回执），Right = 逐字 argv。
   */
  def subArgv(
    sub: String,
    path: Option[String],
    rev: Option[String],
    rev2: Option[String],
    limit: Int,
    format: String
  ): Either[String, List[String]] =
    sub match
      case "log" =>
        // log 只吃 path / limit（v1 不做「指定 rev 的历史」：对账问的是仓 tip 的历史）。
        if rev.isDefined || rev2.isDefined then Left("`log` does not accept rev/rev2 in v1 (tip history only).")
        else
          Right(
            List("log", "-n", limit.toString, "--no-color", LogFormat, "--date=short") ++
              path.toList.flatMap(p => List("--", p))
          )
      case "show" =>
        if rev2.isDefined then Left("`show` does not accept rev2.")
        else if format != "stat" then Left("`show` accepts format=stat only (or pass `path` for the rev:path blob form).")
        else
          path match
            case Some(p) => Right(List("show", rev.getOrElse(DefaultRev) + ":" + p))
            case None    => Right(List("show", "--stat") ++ DiffGuards :+ rev.getOrElse(DefaultRev))
      case "diff" =>
        format match
          case "stat" =>
            Right(List("diff", "--no-color") ++ DiffGuards ++ List("--stat") ++ List(rev, rev2).flatten
              ++ path.toList.flatMap(p => List("--", p)))
          case "name-status" =>
            Right(List("diff", "--no-color") ++ DiffGuards ++ List("--name-status") ++ List(rev, rev2).flatten
              ++ path.toList.flatMap(p => List("--", p)))
          case other => Left(s"`format` = \"$other\" is not allowed for diff (stat | name-status).")
      case "merge-base" =>
        if path.isDefined then Left("`merge-base` does not accept path.")
        else if format != "stat" then Left("`merge-base` accepts no format.")
        else
          rev match
            case None    => Left("`merge-base` requires `rev` (the possibly-ancestor side).")
            case Some(a) => Right(List("merge-base", "--is-ancestor", a, rev2.getOrElse(DefaultRev)))
      case other => Left(s"subcommand \"$other\" is outside the v1 enum ${Subcommands.mkString("[", ", ", "]")}.")

  /** 完整 argv（含 `git -C <root>`；`-C` 是**我们**的位置参数，不是模型输入）。 */
  def argvFor(
    sub: String,
    root: os.Path,
    path: Option[String],
    rev: Option[String],
    rev2: Option[String],
    limit: Int,
    format: String
  ): Either[String, List[String]] =
    subArgv(sub, path, rev, rev2, limit, format).map(args => List("git", "-C", root.toString, "--no-pager") ++ args)

  /* ═══════════════════════════════════════════════════════════════════
   * 输出截断（硬要求 4）
   * ═══════════════════════════════════════════════════════════════════ */

  final case class Clip(kept: String, shownLines: Int, totalLines: Int, shownBytes: Int, totalBytes: Int):

    def capped: Boolean = shownLines < totalLines || shownBytes < totalBytes

    /** 边界读数行（截断真的发生时才有）。 */
    def notice: Option[String] =
      if !capped then None
      else
        Some(
          s"[GitQuery truncated: $shownLines of $totalLines lines, $shownBytes of $totalBytes bytes shown " +
            s"(caps: $MaxOutputLines lines / $MaxOutputBytes bytes) — narrow with path/rev or lower limit]"
        )

    def render: String = notice.fold(kept)(n => s"$kept\n$n")

  /** 行闸（≤200 行）⇒ 字节闸（≤8KB，按字符边界安全切）。 */
  def clipOutput(body: String): Clip =
    val allLines = body.linesIterator.toList
    val totalLines = allLines.size
    val totalBytes = body.getBytes(UTF_8).length
    val keptLines = allLines.take(MaxOutputLines)
    val lineText = keptLines.mkString("\n")
    if lineText.getBytes(UTF_8).length <= MaxOutputBytes then
      Clip(lineText, keptLines.size, totalLines, lineText.getBytes(UTF_8).length, totalBytes)
    else
      // 字节闸：退到 ≤ MaxOutputBytes 的最长字符前缀（UTF-8 边界安全）。
      val sb = new StringBuilder
      val chars = lineText.toCharArray
      var bytes = 0
      var idx = 0
      var full = false
      while idx < chars.length && !full do
        val b = chars(idx).toString.getBytes(UTF_8).length
        if bytes + b > MaxOutputBytes then full = true
        else
          sb.append(chars(idx))
          bytes += b
          idx += 1
      val clipped = sb.toString
      Clip(clipped, clipped.linesIterator.size, totalLines, clipped.getBytes(UTF_8).length, totalBytes)

  /* ═══════════════════════════════════════════════════════════════════
   * 进程执行
   * ═══════════════════════════════════════════════════════════════════ */

  private def run(argv: List[String], cwd: os.Path): Either[String, (Int, String)] =
    try
      val res = os
        .proc(argv)
        .call(cwd = cwd, env = PinnedEnv, timeout = TimeoutMs, check = false, mergeErrIntoOut = true)
      Right((res.exitCode, res.out.text()))
    catch
      case e: Throwable => Left(s"GITQUERY_GIT_UNAVAILABLE: git could not be executed (${e.getClass.getSimpleName})")

  /** 仓根解析（`rev-parse --show-toplevel`；worktree 得到 worktree 根，正是我们要的）。 */
  def resolveRoot(repo: os.Path): Either[String, os.Path] =
    if !os.exists(repo) then Left("GITQUERY_REPO_NOT_FOUND")
    else if !os.isDir(repo) then Left("GITQUERY_REPO_NOT_DIR")
    else
      run(List("git", "-C", repo.toString, "--no-pager", "rev-parse", "--show-toplevel"), repo) match
        case Left(msg) => Left(msg)
        case Right((0, out)) =>
          val line = out.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")
          if line.isEmpty then Left("GITQUERY_NOT_A_REPO") else Right(os.Path(line))
        case Right((_, out)) =>
          // git 自己的可读诊断（"not a git repository …"）原样转述，不外泄栈。
          Left(s"GITQUERY_NOT_A_REPO: ${out.trim.take(300)}")

  /* ═══════════════════════════════════════════════════════════════════
   * 调用面
   * ═══════════════════════════════════════════════════════════════════ */

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")
    // 双保险之工具内侧（DUAL 风格显式错误）：非 ask 会话 / REST 直调一律拒绝。
    if !AskSessions.isAskSession(sid) then
      IO.pure(
        Left(
          ToolError(
            "GitQuery: permission denied — this tool is exclusive to file-ask sessions (AskSessions.isAskSession). " +
              "(GITQUERY_FORBIDDEN)"
          )
        )
      )
    else execute(input, ctx).handleErrorWith(e => IO.pure(Left(ToolError(s"GitQuery failed: ${e.getMessage}"))))

  private def refuse(msg: String): Either[ToolError, String] = Left(ToolError(msg))

  private def execute(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val allowedKeys = Set("subcommand", "repo", "path", "rev", "rev2", "limit", "format", "device")
    val unknown = (input.keys.toSet -- allowedKeys).toList.sorted
    val sub = input("subcommand").flatMap(_.asString).map(_.trim).getOrElse("")
    val device = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val repoRaw = input("repo").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val pathRaw = input("path").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val revRaw = input("rev").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val rev2Raw = input("rev2").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val limitRaw = input("limit").flatMap(_.asNumber.flatMap(_.toInt))
    val formatRaw = input("format").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse("stat")

    val limitProblem =
      if limitRaw.isDefined && sub != "log" then Some("`limit` applies to `log` only.")
      else if limitRaw.exists(_ < 1) then Some("`limit` must be >= 1.")
      else None
    val problems: List[String] =
      List(
        pathRaw.flatMap(pathProblem(_)),
        revRaw.flatMap(revProblem(_)),
        rev2Raw.flatMap(r => revProblem(r, "rev2")),
        limitProblem
      ).flatten

    if unknown.nonEmpty then
      IO.pure(
        refuse(
          s"GitQuery refused: unknown parameter(s) ${unknown.mkString(", ")} — this tool takes no free-form argv; " +
            s"allowed keys = {${allowedKeys.toList.sorted.mkString(", ")}}. (GITQUERY_BAD_ARG)"
        )
      )
    else if device.exists(!_.equalsIgnoreCase("local")) then
      IO.pure(
        refuse(
          s"GitQuery refused: device=\"${device.getOrElse("")}\" — v1 runs on the LOCAL machine only " +
            "(cross-device worktree history is planned for v2). Pass device=\"local\" or omit it. " +
            "(GITQUERY_DEVICE_UNSUPPORTED)"
        )
      )
    else if !Subcommands.contains(sub) then
      IO.pure(
        refuse(
          s"GitQuery refused: subcommand \"$sub\" is outside the v1 enum — allowed = " +
            s"${Subcommands.mkString("[", ", ", "]")}. Write/network subcommands " +
            s"(${NeverAllow.toList.sorted.mkString(", ")}) are never whitelisted. (GITQUERY_BAD_SUBCOMMAND)"
        )
      )
    else if problems.nonEmpty then
      IO.pure(refuse(s"GitQuery refused: ${problems.head} (GITQUERY_BAD_ARG)"))
    else
      val base = Option(ctx.projectRoot).map(_.trim).filter(_.nonEmpty).getOrElse(".")
      val repoPath = repoRaw match
        case None => os.Path(base, os.pwd)
        case Some(raw) =>
          val expanded = PathUtil.expandTilde(raw)
          if PathUtil.isAbsolute(expanded) then os.Path(expanded) else os.Path(base, os.pwd) / expanded
      val capped = limitRaw.getOrElse(DefaultLogLimit).min(MaxLogLimit)
      val limitNote =
        Option.when(limitRaw.exists(_ > MaxLogLimit))(s"limit: $capped (requested ${limitRaw.get}, hard cap $MaxLogLimit)")

      IO.blocking {
        resolveRoot(repoPath) match
          case Right(root) =>
            argvFor(sub, root, pathRaw, revRaw, rev2Raw, capped, formatRaw) match
              case Left(msg) => refuse(s"repo: $root\nGitQuery refused: $msg (GITQUERY_BAD_ARG)")
              case Right(argv) =>
                val header = List(s"repo: $root", "cmd: " + argv.mkString(" ")) ++ limitNote.toList
                run(argv, root) match
                  case Left(err) => refuse(s"repo: $root\nGitQuery refused: $err")
                  case Right((code, out)) =>
                    val tail = finish(sub, root, code, out, revRaw, rev2Raw)
                    tail.map(t => header.mkString("\n") + "\n" + t)
          case Left(reason) => repoFailure(repoPath, reason)
      }
    end if
  end execute

  /** 仓根解析失败的可读回执（非 git 仓 / 路径不存在 / git 缺装——第一行照样写解析目标）。 */
  private def repoFailure(repoPath: os.Path, reason: String): Either[ToolError, String] =
    val (code, detail) =
      if reason.startsWith("GITQUERY_NOT_A_REPO:") then
        ("GITQUERY_NOT_A_REPO", reason.stripPrefix("GITQUERY_NOT_A_REPO:").trim)
      else if reason.startsWith("GITQUERY_GIT_UNAVAILABLE") then
        ("GITQUERY_GIT_UNAVAILABLE", "git could not be executed on this machine")
      else if reason == "GITQUERY_REPO_NOT_DIR" then
        ("GITQUERY_REPO_NOT_DIR", "`repo` points at a file, not a directory")
      else if reason == "GITQUERY_REPO_NOT_FOUND" then
        ("GITQUERY_REPO_NOT_FOUND", "the given repo path does not exist on this machine")
      else (reason, reason)
    val hint =
      if code == "GITQUERY_NOT_A_REPO" then " — no git repository there, so there is nothing to query."
      else if code == "GITQUERY_REPO_NOT_FOUND" || code == "GITQUERY_REPO_NOT_DIR" then
        " — omit `repo` to use the project root, or pass an existing repository directory."
      else "."
    refuse(
      s"repo: $repoPath (unresolved)\n" +
        s"GitQuery refused: $detail$hint ($code)"
    )

  /** 成功路径的正文（merge-base 走布尔面；其余走截断面）。 */
  private def finish(
    sub: String,
    root: os.Path,
    code: Int,
    out: String,
    rev: Option[String],
    rev2: Option[String]
  ): Either[ToolError, String] =
    val descendant = rev2.getOrElse(DefaultRev)
    val ancestor = rev.getOrElse("")
    if sub == "merge-base" then
      code match
        case 0 => Right(s"is-ancestor: true\n(true = $ancestor IS an ancestor of $descendant)")
        case 1 => Right(s"is-ancestor: false\n(false = $ancestor is NOT an ancestor of $descendant)")
        case other =>
          refuse(
            s"repo: $root\nGitQuery refused: merge-base exited $other — probably an unresolvable revision. " +
              s"${out.trim.take(300)} (GITQUERY_GIT_ERROR)"
          )
    else if code == 0 then
      val body = out.stripTrailing()
      if body.isEmpty then Right("(no output)") else Right(clipOutput(body).render)
    else
      refuse(
        s"repo: $root\nGitQuery refused: git exited $code — ${out.trim.take(500)} (GITQUERY_GIT_ERROR)"
      )

end GitQueryTool
