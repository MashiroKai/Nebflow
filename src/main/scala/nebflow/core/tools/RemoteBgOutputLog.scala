package nebflow.core.tools

/**
 * 后台任务输出查看补全批（作者令 2026-10-25「这个截图是我们后台任务的进展查看
 * 面板，现在就是远端任务看不到，本地任务细节好像也看不到」）· 远端日志路径判据面。
 *
 * 远端后台任务（RemoteExecutor.executeRemoteBackground）的输出在任务结束前只存在
 * 于对端——本侧 fiber 是同步 HTTP 等待，结束才拿到全量。LLM 发起的远端命令照惯例
 * 把输出重定向到对端日志文件（实证：test5 的 `/c/Users/Kai/stand-win-test5.log`）。
 * 本面从命令文本里解析出该路径，作为 job 记录的 outputPath——详情面板据此经既有
 * 设备通道按需拉尾（远端 Bash `tail -c`，走 RemoteExecutor.readBgLogTail 的既有
 * P2P/relay 面，零新权限口子），无需实时流，轮询拉尾即可。
 *
 * 纯函数（零 IO、零状态）⇒ spec 直测（RemoteBgOutputLogSpec）。解析是**尽力而为
 * 的启发式**：解析不到（无重定向 / devnull / 引号内伪装）= outputPath 缺席，面板
 * 走「输出不可用 + 原因」降级，绝不猜路径。
 */
private[nebflow] object RemoteBgOutputLog:

  /** 尾窗字节上限（详情面板一次拉取的量；纯展示面，非权限闸）。 */
  val TailMaxBytes: Long = 64L * 1024

  /** 尾拉命令在对端 Bash 的 timeout 参数（毫秒）：快命令 + 设备无响应快速失败。 */
  val TailRemoteTimeoutMs: Long = 15000L

  /** 目标类别：stdout 类（`>`/`>>`/`1>`/`&>`…）、stderr 类（`2>`/`2>>`）、tee 管道。 */
  private enum Cl:
    case Stdout, Stderr, Tee

  /**
   * 解析命令文本的输出重定向目标（quote 感知扫描）。
   *
   * 规则：
   *   - 只认引号外的操作符（`echo "a > b"` 不算）；
   *   - stdout 类与 stderr 类分开记账，**最后一个 stdout 类目标胜出**（shell
   *     语义：后面的重定向覆盖前面的）；无 stdout 类时回退 stderr 类，再回退
   *     `|tee [-a] path`；
   *   - fd 复制（`2>&1` / `>&2` / `>&1`）不是文件目标，跳过；
   *   - 输入重定向（`<` `<<` `<<<`）与 `\>` 转义不产生目标；
   *   - `/dev/null` / `NUL`（Windows）/ 变量目标 = 无 outputPath。
   */
  def parseRedirect(command: String): Option[String] =
    val targets = scan(command)
    val stdoutClass = targets.collect { case (Cl.Stdout, p) => p }
    val stderrClass = targets.collect { case (Cl.Stderr, p) => p }
    val teeClass = targets.collect { case (Cl.Tee, p) => p }
    // 同类取最后一个（后面的重定向覆盖前面的——`> a.log > b.log` 实际写 b.log）。
    def lastOf(ps: List[String]) = ps.lastOption.filter(isRealFile)
    lastOf(stdoutClass).orElse(lastOf(stderrClass)).orElse(lastOf(teeClass))

  private def isRealFile(p: String): Boolean =
    val t = p.trim
    val low = t.toLowerCase
    t.nonEmpty && low != "/dev/null" && low != "nul" && !t.startsWith("&") && !t.startsWith("$")

  /** 同类目标取末位（last-wins），fd 复制不算目标。 */
  private def scan(command: String): List[(Cl, String)] =
    val out = List.newBuilder[(Cl, String)]
    val n = command.length
    var i = 0
    var inSingle = false
    var inDouble = false

    def skipSpaces(j: Int): Int =
      var k = j
      while k < n && command(k) == ' ' do k += 1
      k

    /** `2>` / `1>` 的 IO-number 判据：数字必须独立成词（行首/空白/`;|&(` 之后）——
      * `sort -k2> f` 的 `2` 是词的一部分，shell 里不是 IO-number（同判）。 */
    def atTokenStart(idx: Int): Boolean =
      idx == 0 || command(idx - 1).isWhitespace || ";|&(".contains(command(idx - 1))

    /** 读目标词：引号包裹读整段，否则读到空白/元字符。返回 (词原文, 下一步下标)。 */
    def readTarget(j: Int): (String, Int) =
      if j >= n then ("", n)
      else if command(j) == '\'' then
        val end = command.indexOf('\'', j + 1)
        if end < 0 then (command.substring(j + 1), n)
        else (command.substring(j + 1, end), end + 1)
      else if command(j) == '"' then
        val end = command.indexOf('"', j + 1)
        if end < 0 then (command.substring(j + 1), n)
        else (command.substring(j + 1, end), end + 1)
      else
        var k = j
        while k < n && !command(k).isWhitespace && "<>|&;".indexOf(command(k)) < 0 do k += 1
        (command.substring(j, k), k)

    /** 操作符处理：返回 (类别, 目标)（dup 无目标）与下一步下标。目标读取起点 =
      * 操作符自身的长度（`>`/`2>`/`1>`=1 字符，`&>`/`>>`/`2>>`…=2，`&>>`=3）——
      * 与目标间的空白由 skipSpaces 吸收（`>out.log` 无空格形态同路径）。 */
    def handleRedirect(opStart: Int): ((Cl, String), Int) =
      val c = command(opStart)
      val targetAt =
        if c == '&' then
          if opStart + 2 < n && command(opStart + 2) == '>' then opStart + 3 else opStart + 2 // &>> / &>
        else if opStart + 1 < n && command(opStart + 1) == '>' then
          if opStart + 2 < n && command(opStart + 2) == '>' then opStart + 3 else opStart + 2 // >> / 2>> / 1>>
        else opStart + 1 // > / 2> / 1>
      val cl =
        c match
          case '&' => Cl.Stdout
          case '2' => Cl.Stderr
          case '1' => Cl.Stdout
          case _ => Cl.Stdout
      // fd 复制（`>&1` / `> &1`）：目标以 & 开头 ⇒ 跳过整个记号，无目标。
      val j = skipSpaces(targetAt)
      if j < n && command(j) == '&' then ((cl, ""), j + 1)
      else
        val (t, ni) = readTarget(j)
        ((cl, t), ni)

    while i < n do
      val c = command(i)
      if c == '\'' && !inDouble then
        inSingle = !inSingle
        i += 1
      else if c == '"' && !inSingle then
        inDouble = !inDouble
        i += 1
      else if inSingle || inDouble then
        i += 1
      else if c == '\\' && i + 1 < n then
        i += 2 // 转义：`\>` 不是操作符
      else if c == '>' || (c == '&' && i + 1 < n && command(i + 1) == '>')
        || ((c == '2' || c == '1') && i + 1 < n && command(i + 1) == '>' && atTokenStart(i)) then
        val ((cl, t), ni) = handleRedirect(i)
        if t.nonEmpty then out += ((cl, t))
        i = ni
      else if c == '|' then
        // |tee [‑a] path（管道进 tee 也算落盘面）
        val j = skipSpaces(i + 1)
        val rest = command.substring(j)
        val teeRe = """^tee\s+(?:-a\s+)?(\S+)""".r
        teeRe.findPrefixMatchOf(rest) match
          case Some(m) =>
            val t = m.group(1)
            if t.nonEmpty && t != "-" then out += ((Cl.Tee, t))
            i = j + m.end
          case None =>
            i += 1
      else
        i += 1
    out.result()
  end scan

  /**
   * 对端尾拉命令：`tail -c N -- 'path'`（macOS BSD tail 与 MINGW GNU tail 同支持
   * `-c N` + `--`）。路径单引号包裹（内嵌 `'` 按 POSIX 拼接转义）——目标词来自
   * 本面自己的解析（引号已剥），二次包裹防对端 shell 二次解释。
   */
  def tailCommand(path: String, maxBytes: Long): String =
    val q = path.replace("'", "'\\''")
    s"tail -c $maxBytes -- '$q'"

  /**
   * 对端 Bash 回执里的非零 exit 标记（对端 formatResult 只在 exit≠0 时追加
   * `(exit N)` 行）。只认**末尾**出现的标记（`(?s).*\(exit N\)\s*\z`）——输出
   * 正文中的同名文本不误判；解析不到 = None（面板不虚报 exit 0）。
   */
  def parseExitMarker(output: String): Option[Int] =
    val re = """(?s).*\(exit (\d+)\)\s*\z""".r
    output match
      case re(code) => code.toIntOption
      case _ => None

end RemoteBgOutputLog
