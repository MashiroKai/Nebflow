package nebflow.core.browser

import io.circe.Json


/**
 * 浏览器动词面的纯策略（无 IO，可单测）。
 *
 * 收口：会话名与 ref 形状、调用身份判据（单点，工具与挂载面共用）、URL 脱敏、
 * 页面状态分类（口径同种子 `lib.mjs` 的 pageState）、填充守卫（凭据红线）、Chrome
 * 候选路径、无障碍树解析与渲染（快照即真相：ref 只来自最近一次快照）。
 */
object BrowserPolicy:
  val DefaultSession: String = "default"
  val DefaultWaitMs: Long = 3000L
  val MaxWaitMs: Long = 30000L
  val DefaultDownloadTimeoutMs: Long = 120000L
  val MaxDownloadTimeoutMs: Long = 120000L
  val DefaultSnapshotNodes: Int = 300
  val MaxSnapshotNodes: Int = 1000
  val MaxNameChars: Int = 120
  val PermissionCode: String = "BROWSER_FORBIDDEN"

  def clampLong(v: Long, lo: Long, hi: Long): Long = math.max(lo, math.min(hi, v))
  def clampInt(v: Int, lo: Int, hi: Int): Int = math.max(lo, math.min(hi, v))

  /** 会话名：小写字母/数字/连字符/下划线，1–40 位（同时用作 profile 与 sessions 文件名，禁路径字符）。 */
  def validSessionName(s: String): Boolean = s.matches("[a-z0-9][a-z0-9_-]{0,39}")

  /** ref 形状：`e` + 数字（由快照分配，不接受任意选择器）。 */
  def parseRef(s: String): Option[String] = if s.matches("e[0-9]{1,6}") then Some(s) else None

  /**
   * 调用身份基本校验（作者 2026-11-22 插件分发裁定）：授权 = 项目派发挂载 browser-use 插件
   * （挂载即授权，不再按身份名单）。守卫只留身份基本校验：Nebula 根，或带会话身份的引擎
   * 会话（非空 sessionId）；匿名 REST 直调 fail-closed。凭据红线不在此处——见 fillRefusal。
   */
  def callerAllowed(isNebulaRoot: Boolean, sessionId: Option[String]): Boolean =
    isNebulaRoot || sessionId.exists(_.trim.nonEmpty)

  /** 输出 URL 脱敏：查询串整段替换（cookie/token 常在 query 里）。 */
  def redactUrl(u: String): String =
    val q = u.indexOf('?')
    if q >= 0 then u.take(q) + "?…" else u

  private val LoginishUrl = """(?i)log[-_]?in|sign[-_]?in|/auth|/sso|/cas|oauth|identify""".r

  def isLoginishUrl(url: String): Boolean = LoginishUrl.findFirstIn(url).isDefined

  /**
   * 页面状态分类（口径同 seed lib.mjs pageState）：反爬拦截页证据 = 命中防护标记且
   * （页面近空白 <500 字 或 challenge 文案命中）；拦截优先于登录判据。
   * 返回 OK / LOGIN / CAPTCHA / ANTIBOT。
   */
  def classify(markers: List[String], textLen: Int, hasPassword: Boolean, url: String, hasCaptcha: Boolean): String =
    val blocked = markers.nonEmpty && (textLen < 500 || markers.contains("challenge-text"))
    val loginish = hasPassword || (isLoginishUrl(url) && !blocked)
    if blocked then "ANTIBOT"
    else if loginish then "LOGIN"
    else if hasCaptcha then "CAPTCHA"
    else "OK"

  /**
   * 凭据红线（动词面侧）：本期不实现 `secret:` 解析（P1 由 daemon 侧 SecretsStore 注入），
   * 但模型参数里出现该保留语法一律拒绝，且拒绝文案不回显任何输入内容。
   */
  /**
   * 填充守卫（最终层）：text 在到达 CDP 前若仍以保留前缀开头即拒绝——正常路径下 secret 引用已由
   * BrowserSecretRef 解析为明文，明文若恰以 `secret:` 开头同样拒绝（防二次引用展开）。
   */
  def fillRefusal(text: String): Option[String] =
    if text.trim.startsWith("secret:") then
      Some("secret: 保留前缀的文本不允许直接填入（secret 引用须由引擎解析，不回显）。")
    else None

  /** Chrome 候选路径：显式环境变量优先，其后按平台内置位置。调用方取第一个存在的。 */
  def chromeCandidates(env: String => Option[String], osName: String): List[String] =
    val overridden = env("NEBFLOW_BROWSER_USE_CHROME").toList
    val os = osName.toLowerCase
    val builtin =
      if os.contains("mac") then
        List(
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
          "/Applications/Chromium.app/Contents/MacOS/Chromium"
        )
      else if os.contains("win") then
        List(env("ProgramFiles"), env("ProgramFiles(x86)"), env("LOCALAPPDATA")).flatten.flatMap { base =>
          List(
            base + "\\Google\\Chrome\\Application\\chrome.exe",
            base + "\\Microsoft\\Edge\\Application\\msedge.exe"
          )
        }
      else List("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser")
    overridden ++ builtin

  // ── 无障碍树（快照即真相） ─────────────────────────────────────────

  /** CDP Accessibility.getFullAXTree 的一个节点（已拍平为本模块所需字段）。 */
  final case class AxNode(
    id: String,
    ignored: Boolean,
    role: String,
    name: String,
    backendId: Option[Int],
    childIds: List[String]
  )

  final case class AxRender(text: String, refs: Map[String, Int], truncated: Boolean)

  private val Structural: Set[String] = Set("none", "generic", "presentation", "InlineTextBox", "LineBreak")

  /** 节点是否输出一行：未忽略、有角色、且不是「无名结构节点」。结构节点只下钻不出行。 */
  def emits(n: AxNode): Boolean =
    !n.ignored && n.role.nonEmpty && !(Structural.contains(n.role) && n.name.isEmpty)

  def clipName(s: String): String =
    val t = s.replaceAll("\\s+", " ").trim
    if t.length > MaxNameChars then t.take(MaxNameChars) + "…" else t

  /** 解析 CDP `nodes` 数组（每个元素为 AXNode JSON）。缺字段一律给空值，不抛异常。 */
  def parseAx(nodes: List[Json]): List[AxNode] =
    nodes.map { n =>
      val c = n.hcursor
      AxNode(
        id = c.get[String]("nodeId").getOrElse(""),
        ignored = c.get[Boolean]("ignored").getOrElse(false),
        role = c.downField("role").get[String]("value").getOrElse(""),
        name = c.downField("name").get[String]("value").getOrElse(""),
        backendId = c.get[Int]("backendDOMNodeId").toOption,
        childIds = c.get[List[String]]("childIds").getOrElse(Nil)
      )
    }

  /**
   * 渲染为缩进文本：`- role "name" [ref=eN]`。ref 只挂在有 DOM 后端的节点上（可点击/可填充
   * 的前提）；按 DFS 顺序编号 e1..eN；达到 maxNodes 即截断并注明。循环引用由 seen 集合兜住。
   */
  def renderAx(nodes: List[AxNode], maxNodes: Int): AxRender =
    nodes match
      case Nil => AxRender("(empty accessibility tree)", Map.empty, false)
      case root :: _ =>
        val byId = nodes.map(n => n.id -> n).toMap
        val lines = scala.collection.mutable.ArrayBuffer.empty[String]
        val refs = scala.collection.mutable.LinkedHashMap.empty[String, Int]
        val seen = scala.collection.mutable.HashSet.empty[String]
        var truncated = false

        def walk(id: String, depth: Int): Unit =
          if !truncated && seen.add(id) then
            byId.get(id).foreach { n =>
              if emits(n) then {
                if refs.size >= maxNodes then {
                  truncated = true
                } else {
                  val ref: Option[String] = n.backendId.map { bid =>
                    val r = "e" + (refs.size + 1)
                    refs += (r -> bid)
                    r
                  }
                  val nameTxt = if n.name.isEmpty then "" else " \"" + clipName(n.name) + "\""
                  val refTxt = ref.map(r => " [ref=" + r + "]").getOrElse("")
                  lines += ("  " * depth) + "- " + n.role + nameTxt + refTxt
                  n.childIds.foreach(walk(_, depth + 1))
                }
              } else {
                n.childIds.foreach(walk(_, depth))
              }
            }

        walk(root.id, 0)
        val tail = if truncated then s"\n… (truncated at $maxNodes nodes; pass max_nodes to widen)" else ""
        AxRender(lines.mkString("\n") + tail, refs.toMap, truncated)

end BrowserPolicy
