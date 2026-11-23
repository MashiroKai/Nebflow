package nebflow.core.desktop

/* ═══════════════════════════════════════════════════════════════════════════
 * desktop-observe-p0（2026-11-22）· 桌面观测面纯策略（无副作用，可单测）
 *
 * 观测半边的全部判据集中于此：宿主拒绝名单、身份判据、窗口行/区域/应用名解析、
 * 剪贴板输出上限。写类动作不在本模块（红线：无点击/键入/窗口操纵/AX 注入）。
 *
 * 宿主保护（HOST-PROTECTED）口径承袭 seed 包 scripts/desktop-observe.sh 的拒绝名单，
 * 并在引擎侧做同样的前置过滤：宿主目标在审批流之前即拒绝、不做任何系统查询。
 * ═══════════════════════════════════════════════════════════════════════════ */
object DesktopPolicy:

  /** 宿主拒绝名单（小写子串匹配，fail-closed：宁拒不放）。与 desktop-observe.sh DENY_NAMES_DEFAULT 同源并扩 stand。 */
  val HostDenyNames: Vector[String] = Vector("nebflow", "nebula", "stand", "sbt", "java", "openjdk")

  /** 宿主保护错误码（与 seed 脚本退出码 3 的 HOST-PROTECTED 同名）。 */
  val HostProtectedCode: String = "HOST-PROTECTED"

  /** 剪贴板读取输出上限（字节）；超出截断并标注。 */
  val ClipboardCapBytes: Int = 64 * 1024

  /** 应用名允许字符：字母数字、空格、点、加号、下划线、连字符；1–80 位。禁路径与引号字符。 */
  private val AppNamePattern = "^[A-Za-z0-9 .+_-]{1,80}$".r

  /**
   * 工具内身份基本校验（插件分发裁定后收窄，同 browser-verbs-p0 BrowserPolicy.callerAllowed）：
   * 挂载 computer-use 插件即授权（授权判据在 AgentSessionExecution 的挂载腿）；此处只拒绝无会话身份的直调。
   */
  def callerAllowed(isNebulaRoot: Boolean, sessionId: Option[String]): Boolean =
    isNebulaRoot || sessionId.exists(_.trim.nonEmpty)

  /** 应用名形状校验；非法返回 None。 */
  def validAppName(raw: String): Option[String] =
    val t = raw.trim
    if AppNamePattern.pattern.matcher(t).matches() then Some(t) else None

  /** 名字是否落入宿主拒绝名单（大小写不敏感子串）。 */
  def isHostName(name: String): Boolean =
    val lc = name.toLowerCase
    HostDenyNames.exists(n => lc.contains(n))

  /** 区域 `x,y,w,h`（整数，w/h > 0）；非法返回 None。 */
  def parseRegion(raw: String): Option[(Int, Int, Int, Int)] =
    raw.split(",").map(_.trim).toList match
      case List(xs, ys, ws, hs) =>
        (xs.toIntOption, ys.toIntOption, ws.toIntOption, hs.toIntOption) match
          case (Some(x), Some(y), Some(w), Some(h)) if w > 0 && h > 0 => Some((x, y, w, h))
          case _ => None
      case _ => None

  /** 一条窗口记录（System Events 只读查询的单行结果）。 */
  final case class WindowRow(app: String, title: String, x: Int, y: Int, w: Int, h: Int)

  /** 解析窗口行：`app\ttitle\tx\ty\tw\th`；坏行跳过（不抛错）。title 中的制表符/换行被替换为空格。 */
  def parseWindowLines(text: String): List[WindowRow] =
    text.linesIterator.flatMap { line =>
      line.split("\t", -1).toList match
        case List(app, title, xs, ys, ws, hs) =>
          for
            x <- xs.trim.toIntOption
            y <- ys.trim.toIntOption
            w <- ws.trim.toIntOption
            h <- hs.trim.toIntOption
          yield WindowRow(app.trim, title.replaceAll("[\\t\\r\\n]", " ").trim, x, y, w, h)
        case _ => None
    }.toList

  /** 进程行：`name\tpid`。 */
  final case class AppRow(name: String, pid: Long)

  /** 解析进程行；坏行跳过。 */
  def parseAppLines(text: String): List[AppRow] =
    text.linesIterator.flatMap { line =>
      line.split("\t", -1).toList match
        case List(name, pid) if name.trim.nonEmpty => pid.trim.toLongOption.map(p => AppRow(name.trim, p))
        case _ => None
    }.toList

  /** 截断剪贴板文本到字节上限（UTF-8，整字符边界）；返回 (文本, 是否截断)。线性一趟。 */
  def capClipboard(text: String): (String, Boolean) =
    if text.getBytes("UTF-8").length <= ClipboardCapBytes then (text, false)
    else
      val sb = new StringBuilder
      var bytes = 0
      var i = 0
      var stop = false
      while !stop && i < text.length do
        val cp = text.codePointAt(i)
        val len = new String(Character.toChars(cp)).getBytes("UTF-8").length
        if bytes + len > ClipboardCapBytes then stop = true
        else
          sb.append(new String(Character.toChars(cp)))
          bytes += len
          i += Character.charCount(cp)
      (sb.toString, true)

end DesktopPolicy
