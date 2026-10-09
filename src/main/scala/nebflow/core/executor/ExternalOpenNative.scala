package nebflow.core.executor

/**
 * ExternalOpenNative —— 「打开原软件」命令模板（external-executors 批 #1225，
 * 原书 AgentControl 面：外部行可从面板/弹窗打开原 CLI 的原生终端并 resume）。
 *
 * 模板 = 纯函数（argv 形态，外层**不走 shell 拼接**——参数表直传 ProcessBuilder，
 * cwd/会话 id 不进引号炼狱）；三平台：
 *   - macOS：osascript 驱动 Terminal.app 开新窗并执行 `cd <dir> && <resume>`；
 *   - Windows：`cmd /c start "" cmd /k "cd /d <dir> && <resume>"`；
 *   - Linux：探测 x-terminal-emulator / gnome-terminal / konsole / xterm 之一
 *     （`-e` 形态执行）。
 * resume 命令 = 适配器的 interactiveResumeCommand（claude `--resume <sid>` /
 * codex `resume <tid>` / zcode `--resume <sid>`；无锚点 = 裸启动该 CLI）。
 *
 * 执行面 = PresenceRoutes `POST /api/delegates/:id/open-native`（fire-and-forget，
 * 响应带回显命令行供面板确认）。防注入：cliSessionId 白名单字符校验（与
 * DelegateRegistry.validId 同族），cwd 单引号转义仅用于**内层** shell 文本。
 */
object ExternalOpenNative:

  /** 内层 shell 文本的单引号转义（' → '\''）。 */
  def shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** CLI 会话 id 白名单（防把任意文本拼进内层 shell）。 */
  def validCliSessionId(sid: String): Boolean =
    sid.nonEmpty && sid.length <= 128 && sid.matches("^[a-zA-Z0-9._-]+$")

  /** 恢复命令片段（execId 未知适配器 → 空串 = 不追加 resume）。 */
  def resumeCommand(executorId: String, cliSessionId: Option[String]): String =
    cliSessionId.filter(validCliSessionId) match
      case None =>
        ExternalExecutor.forId(executorId).map(_.interactiveResumeCommand(None)).getOrElse(executorId)
      case Some(sid) =>
        ExternalExecutor.forId(executorId) match
          case Some(ex) if ex.id == "codex" => s"codex resume $sid"
          case Some(ex)                     => ex.interactiveResumeCommand(Some(sid))
          case None                         => executorId

  /**
   * 平台模板（argv）。osName 取 `os.name` 小写子串判定（"mac"/"win"，其余按
   * Linux 族）；linuxTerminals = 宿主探测（第一个在 PATH 的项；空 = 回退
   * xterm——执行面 NotFound 时回显提示）。
   */
  def templateFor(
      osName: String,
      cwd: String,
      executorId: String,
      cliSessionId: Option[String],
      linuxTerminals: List[String] = List("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
  ): (String, List[String]) =
    val os      = osName.toLowerCase
    val resume  = resumeCommand(executorId, cliSessionId)
    val inner   = s"cd ${shQuote(cwd)} && $resume"
    if os.contains("mac") then
      // Terminal.app：do script 打开新窗并执行（＋resume 一气呵成）。
      val script = s"""tell application \"Terminal\" to do script ${'"'}$inner${'"'}"""
      ("osascript", List("-e", script))
    else if os.contains("win") then
      ("cmd", List("/c", "start", "", "cmd", "/k", inner))
    else
      val term = linuxTerminals.headOption.getOrElse("xterm")
      (term, List("-e", s"sh -c ${shQuote(inner)}"))

end ExternalOpenNative
