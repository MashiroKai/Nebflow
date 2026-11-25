package nebflow.core.browser

import io.circe.syntax.*
import io.circe.{Json, parser}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * 会话注册表记录。字段与格式同种子插件 `sessions/<name>.json`（port/pid/chromePath/
 * userDataDir/startedAt），两侧读写同一份文件：守护进程与种子脚本共享同一批会话。
 */
final case class BrowserRecord(
  name: String,
  port: Int,
  pid: Option[Long],
  chromePath: String,
  userDataDir: String,
  startedAt: String
):
  def toJson: Json = Json.obj(
    "name" -> name.asJson,
    "port" -> port.asJson,
    "pid" -> pid.asJson,
    "chromePath" -> chromePath.asJson,
    "userDataDir" -> userDataDir.asJson,
    "startedAt" -> startedAt.asJson
  )

/**
 * 守护进程侧常驻浏览器会话管理器（P0 一等工具面的运行期核心）。
 *
 * 机制复用种子 browser-session：有头本机 Chrome + 专用 profile（登录态留存于 profile）+
 * `--remote-debugging-port` CDP 接管 + sessions/<name>.json 注册表。区别在于：CDP 直接由
 * 本 JVM 经 [[CdpConnection]] 驱动，不再每次起 node 子进程。页内定位只认最近一次快照的
 * ref（快照即真相）；ref 失效即显式报错，绝不凭旧引用盲点。
 *
 * 目录约定不变：`$NEBFLOW_BROWSER_USE_HOME`，缺省 `~/.nebflow/browser-use`。
 * 同名会话的生命周期操作（open/close/句柄获取）串行；动作本身在句柄上并发安全。
 */
object BrowserSessions:
  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.browser.session")
  val takeoverRegistry = new BrowserTakeoverRegistry
  private val creator = new ThreadLocal[String]()

  def withCreator[A](owner: Option[String])(body: => A): A =
    owner.foreach(creator.set)
    try body
    finally creator.remove()

  /** 运行期句柄：页面 CDP 连接 + 最近一次快照的 ref → backendDOMNodeId 映射。 */
  final class Handle(val record: BrowserRecord, val page: CdpConnection):
    val refs: AtomicReference[Map[String, Int]] = new AtomicReference(Map.empty[String, Int])

  private val handles = new ConcurrentHashMap[String, Handle]()

  private val Utf8 = StandardCharsets.UTF_8

  // 页面状态读数（口径同种子 lib.mjs pageState 的 evaluate 段；纯读，不改页面）。
  private val PageStateJs: String = """(function () {
  var body = document.body ? String(document.body.innerText || '') : '';
  var html = String(document.documentElement ? document.documentElement.innerHTML : '').slice(0, 60000);
  var q = function (s) { return document.querySelector(s); };
  var vis = function (el) { return !!el && (el.offsetParent !== null || el.getClientRects().length > 0); };
  var title = String(document.title || '');
  var markers = [];
  var push = function (c, m) { if (c) markers.push(m); };
  push(!!q('#challenge-form') || !!q('#challenge-running') || /cf-challenge|cdn-cgi\/challenge-platform|__cf_chl/i.test(html), 'cloudflare');
  push(!!q('#px-captcha') || /_pxAppId|px-captcha|captcha-delivery\.com|press.*and.*hold/i.test(html + ' ' + body.slice(0, 6000)), 'perimeterx');
  push(/datadome\.co|ddv1|geo-captcha/i.test(html), 'datadome');
  push(/just a moment|attention required|请稍候|安全检查|确认您是真人|verify you are human|confirm you are a human/i.test(title + ' ' + body.slice(0, 600)), 'challenge-text');
  push(/radware bot manager|we apologize for the inconvenience/i.test(title + ' ' + body), 'radware');
  var hasPassword = Array.prototype.some.call(document.querySelectorAll('input[type=password]'), vis);
  var hasCaptcha = !!q('iframe[src*="captcha"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[title*="captcha" i]') || !!q('.g-recaptcha, .h-captcha, [class*="captcha" i]');
  return {url: location.href, docTitle: title, hasPassword: hasPassword, hasCaptcha: hasCaptcha, markers: markers, textLen: body.trim().length};
})()"""

  // ── 路径与注册表 ────────────────────────────────────────────────────

  def homeRoot(): Path =
    sys.env
      .get("NEBFLOW_BROWSER_USE_HOME")
      .filter(_.nonEmpty)
      .map(p => Paths.get(p))
      .getOrElse(Paths.get(System.getProperty("user.home"), ".nebflow", "browser-use"))

  private def sessionFile(home: Path, name: String): Path =
    home.resolve("sessions").resolve(name + ".json")

  def readRecord(home: Path, name: String): Option[BrowserRecord] =
    val f = sessionFile(home, name)
    if !Files.isRegularFile(f) then None
    else
      Try(Files.readString(f, Utf8)).toOption
        .flatMap(parser.parse(_).toOption)
        .map(_.hcursor)
        .flatMap { h =>
          h.get[Int]("port").toOption.map { port =>
            BrowserRecord(
              name = name,
              port = port,
              pid = h.get[Long]("pid").toOption,
              chromePath = h.get[String]("chromePath").getOrElse(""),
              userDataDir = h.get[String]("userDataDir").getOrElse(""),
              startedAt = h.get[String]("startedAt").getOrElse("")
            )
          }
        }

  def writeRecord(home: Path, rec: BrowserRecord): Unit =
    val f = sessionFile(home, rec.name)
    Files.createDirectories(f.getParent)
    Files.writeString(f, rec.toJson.spaces2, Utf8)

  private def deleteRecord(home: Path, name: String): Unit =
    Try(Files.deleteIfExists(sessionFile(home, name)))

  // ── 进程探测 ────────────────────────────────────────────────────────

  def pidAlive(pid: Long): Boolean =
    val h = ProcessHandle.of(pid)
    h.isPresent && h.get.isAlive

  private def commandOf(pid: Long): String =
    val h = ProcessHandle.of(pid)
    if h.isPresent then h.get.info().command().orElse("") else ""

  /** 会话在线 = 记录的 pid 仍活着（若记录了）且 CDP 端口可达。 */
  private def isLive(rec: BrowserRecord): Boolean =
    rec.pid.forall(pidAlive) && CdpConnection.cdpAlive(rec.port)

  private def waitUntil(timeoutMs: Long)(cond: => Boolean): Boolean =
    val deadline = System.currentTimeMillis + timeoutMs
    var ok = cond
    while !ok && System.currentTimeMillis < deadline do
      Thread.sleep(250)
      ok = cond
    ok

  // ── 启动 / 附着 / 关闭 ─────────────────────────────────────────────

  def findChrome(): Option[String] =
    BrowserPolicy
      .chromeCandidates(k => sys.env.get(k).filter(_.nonEmpty), System.getProperty("os.name"))
      .find(p => Files.isExecutable(Paths.get(p)))

  /** 启动有头 Chrome（专用 profile，远程调试端口随机）。30 秒内 CDP 不就绪即失败。 */
  private def launch(name: String, home: Path): BrowserRecord =
    val chrome = findChrome().getOrElse(
      throw new IllegalStateException(
        "未找到本机 Chrome/Edge。请设置环境变量 NEBFLOW_BROWSER_USE_CHROME 指向浏览器可执行文件。"
      )
    )
    val profile = home.resolve("profiles").resolve(name)
    Files.createDirectories(profile)
    val port = CdpConnection.freePort()
    val cmd = List(
      chrome,
      s"--user-data-dir=${profile.toString}",
      s"--remote-debugging-port=$port",
      "--no-first-run",
      "--no-default-browser-check",
      "--hide-crash-restore-bubble",
      "--disable-session-crashed-bubble",
      "--window-size=1400,900"
    )
    val pb = new ProcessBuilder(cmd.asJava)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
    val proc = pb.start()
    if !waitUntil(30000L)(CdpConnection.cdpAlive(port)) then
      throw new IllegalStateException(s"Chrome 已启动但 CDP 端口 $port 30 秒内未就绪（pid=${proc.pid()}）。")
    logger.infoSync("browser session launched", "session" -> name, "port" -> port.toString)
    BrowserRecord(name, port, Some(proc.pid()), chrome, profile.toString, Instant.now.toString)

  /** 附着页面目标（末个页面；无则新开）。附着即 enable 所需域，之后动作直接可用。 */
  private def attach(rec: BrowserRecord): Handle =
    val target = CdpConnection
      .pageTargets(rec.port)
      .lastOption
      .orElse(CdpConnection.newPageTarget(rec.port))
      .getOrElse(throw new IllegalStateException("CDP 无可用页面目标"))
    val conn = CdpConnection.connect(target.wsUrl)
    List("Page.enable", "DOM.enable", "Accessibility.enable", "Runtime.enable").foreach(m => conn.send(m))
    val h = new Handle(rec, conn)
    handles.put(rec.name, h)
    h

  private def closeHandle(name: String): Unit =
    Option(handles.remove(name)).foreach(h => Try(h.page.close()))

  /** 已有在线会话则复用（必要时重连页面），否则启动新浏览器。 */
  def ensure(name: String, home: Path = homeRoot()): Handle =
    readRecord(home, name) match
      case Some(rec) if isLive(rec) =>
        Option(handles.get(name)).filter(h => h.page.isOpen && h.record.port == rec.port) match
          case Some(h) => h
          case None =>
            closeHandle(name)
            attach(rec)
      case _ =>
        closeHandle(name)
        takeoverRegistry.remove(name)
        val rec = launch(name, home)
        Option(creator.get()).filter(_.nonEmpty).foreach(owner => takeoverRegistry.created(name, owner))
        writeRecord(home, rec)
        attach(rec)

  /** 取既有会话句柄（不启动新浏览器）。daemon 重启后据注册表重连。 */
  def existing(name: String, home: Path = homeRoot()): Handle =
    val rec = readRecord(home, name).getOrElse(
      throw new IllegalStateException(s"浏览器会话 '$name' 不存在。先调用 browser_open 打开它。")
    )
    if !isLive(rec) then
      throw new IllegalStateException(
        s"浏览器会话 '$name' 已不在线（进程或调试端口已退出）。调用 browser_open 重开；登录态保存在其 profile 中。"
      )
    Option(handles.get(name)).filter(h => h.page.isOpen && h.record.port == rec.port).getOrElse {
      closeHandle(name)
      attach(rec)
    }

  // ── 页面读数 ────────────────────────────────────────────────────────

  private def evalValue(page: CdpConnection, expr: String): Json =
    val res = page.send(
      "Runtime.evaluate",
      Json.obj("expression" -> expr.asJson, "returnByValue" -> true.asJson)
    )
    res.hcursor.downField("result").downField("value").focus.getOrElse(Json.Null)

  private def pageInfoJson(session: String, h: Handle): Json =
    val v = evalValue(h.page, PageStateJs).hcursor
    val url = v.get[String]("url").getOrElse("")
    val markers = v.get[List[String]]("markers").getOrElse(Nil)
    val hasPassword = v.get[Boolean]("hasPassword").getOrElse(false)
    val hasCaptcha = v.get[Boolean]("hasCaptcha").getOrElse(false)
    val textLen = v.get[Int]("textLen").getOrElse(0)
    val state = BrowserPolicy.classify(markers, textLen, hasPassword, url, hasCaptcha)
    Json.obj(
      "session" -> session.asJson,
      "alive" -> true.asJson,
      "url" -> BrowserPolicy.redactUrl(url).asJson,
      "title" -> v.get[String]("docTitle").getOrElse("").asJson,
      "state" -> state.asJson,
      "hasPassword" -> hasPassword.asJson,
      "hasCaptcha" -> hasCaptcha.asJson,
      "guardMarkers" -> markers.asJson,
      "textLen" -> textLen.asJson
    )

  private def navigate(h: Handle, url: String): Unit =
    val res = h.page.send("Page.navigate", Json.obj("url" -> url.asJson), 30.seconds)
    res.hcursor.get[String]("errorText").toOption.foreach { e =>
      throw new CdpException(s"导航失败（${BrowserPolicy.redactUrl(url)}）：$e")
    }
    h.refs.set(Map.empty)
    waitUntil(20000L) {
      val rs = evalValue(h.page, "document.readyState").asString.getOrElse("")
      rs == "complete" || rs == "interactive"
    }

  // ── 动词实现 ────────────────────────────────────────────────────────

  /** browser_open：打开/复用会话；url 非空则导航。返回页面读数（脱敏）。 */
  def open(name: String, url: Option[String], home: Path = homeRoot()): String =
    takeoverRegistry.withVerb(name) {
      val h = ensure(name, home)
      url.foreach(u => navigate(h, u))
      pageInfoJson(name, h).spaces2
    }

  /** browser_snapshot：无障碍树 + ref 映射（替换上一份映射——旧 ref 即刻失效）。 */
  def snapshot(name: String, maxNodes: Int, home: Path = homeRoot()): String =
    val h = existing(name, home)
    val res = h.page.send("Accessibility.getFullAXTree", Json.obj(), 20.seconds)
    val nodes = BrowserPolicy.parseAx(res.hcursor.get[List[Json]]("nodes").getOrElse(Nil))
    val rendered = BrowserPolicy.renderAx(nodes, BrowserPolicy.clampInt(maxNodes, 1, BrowserPolicy.MaxSnapshotNodes))
    h.refs.set(rendered.refs)
    val info = pageInfoJson(name, h).hcursor
    val header =
      s"# ${info.get[String]("title").getOrElse("")} (${info.get[String]("url").getOrElse("")}) state=${info.get[String]("state").getOrElse("")}"
    header + "\n" + rendered.text

  private def backendIdOf(h: Handle, ref: String): Int =
    BrowserPolicy
      .parseRef(ref)
      .flatMap(r => h.refs.get().get(r))
      .getOrElse(
        throw new IllegalArgumentException(
          s"ref '$ref' 不在最近一次快照中（快照即真相：页面可能已变化，先调用 browser_snapshot 取新快照再定位）。"
        )
      )

  private def staleRef(): Nothing =
    throw new IllegalStateException("该 ref 对应元素已不在页面上（页面可能已变化），先 browser_snapshot 取新快照。")

  private def clickAt(h: Handle, bid: Int): Unit =
    Try(h.page.send("DOM.scrollIntoViewIfNeeded", Json.obj("backendNodeId" -> bid.asJson)))
    val box = Try(h.page.send("DOM.getBoxModel", Json.obj("backendNodeId" -> bid.asJson))).getOrElse(staleRef())
    val quad = box.hcursor.downField("model").get[List[Double]]("content").getOrElse(Nil)
    if quad.size < 8 then
      throw new IllegalStateException("元素无几何信息（可能隐藏或不在视口内）。先 browser_snapshot 或滚动后重试。")
    val x = (quad(0) + quad(2) + quad(4) + quad(6)) / 4
    val y = (quad(1) + quad(3) + quad(5) + quad(7)) / 4
    def mouse(kind: String, extra: (String, Json)*): Unit =
      h.page.send(
        "Input.dispatchMouseEvent",
        Json.fromFields(Seq("type" -> kind.asJson, "x" -> x.asJson, "y" -> y.asJson) ++ extra)
      )
    mouse("mouseMoved")
    mouse("mousePressed", "button" -> "left".asJson, "clickCount" -> 1.asJson)
    mouse("mouseReleased", "button" -> "left".asJson, "clickCount" -> 1.asJson)

  /** browser_click：以快照 ref 定位，真实鼠标事件点中元素中心。 */
  def click(name: String, ref: String, home: Path = homeRoot()): String =
    val h = existing(name, home)
    clickAt(h, backendIdOf(h, ref))
    s"clicked $ref (page may have changed: re-snapshot before the next locate)"

  private def objectIdOf(h: Handle, bid: Int): String =
    val r = Try(h.page.send("DOM.resolveNode", Json.obj("backendNodeId" -> bid.asJson))).getOrElse(staleRef())
    r.hcursor.downField("object").get[String]("objectId").getOrElse(staleRef())

  private def callOn(h: Handle, objectId: String, fn: String): Json =
    h.page
      .send(
        "Runtime.callFunctionOn",
        Json.obj(
          "objectId" -> objectId.asJson,
          "functionDeclaration" -> fn.asJson,
          "returnByValue" -> true.asJson
        )
      )
      .hcursor
      .downField("result")
      .downField("value")
      .focus
      .getOrElse(Json.Null)

  /**
   * browser_fill：向快照 ref 的元素输入 text（先全选再替换）。
   * 凭据红线：`type=password` 字段一律拒绝（登录由人在可见浏览器完成，P1 再接 secret 引用）；
   * `secret:` 保留语法拒绝；成功回执只报长度，绝不回显 text。
   */
  /**
   * allowPassword：仅当 text 来自 secret 引用注入（BrowserSecretRef）时为真；模型直接键入的 password 字段仍拒绝。
   * 调用方应在此之前已解析引用，text 为明文值（不回显）。
   */
  def fill(name: String, ref: String, text: String, home: Path = homeRoot(), allowPassword: Boolean = false): String =
    BrowserPolicy.fillRefusal(text).foreach(msg => throw new IllegalArgumentException(msg))
    val h = existing(name, home)
    val bid = backendIdOf(h, ref)
    val oid = objectIdOf(h, bid)
    val kind = callOn(h, oid, "function () { return String(this.type || '').toLowerCase(); }").asString.getOrElse("")
    if allowPassword then
      // secret 注入只许进入 password 字段：防止凭据被填进普通输入框后经页面外泄。
      if kind != "password" then
        throw new IllegalArgumentException("secret 引用只能注入 password 字段（凭据不得进入普通输入框）。")
    else if kind == "password" then
      throw new IllegalArgumentException(
        "凭据类字段（type=password）不接受模型直接键入。请改用 secret 引用（text=\"secret:<name>\"）由引擎注入，或由人在可见浏览器中完成登录。"
      )
    h.page.send("DOM.focus", Json.obj("backendNodeId" -> bid.asJson))
    callOn(
      h,
      oid,
      "function () { try { if (typeof this.select === 'function') { this.select(); } else { document.execCommand('selectAll'); } } catch (e) {} return true; }"
    )
    h.page.send("Input.insertText", Json.obj("text" -> text.asJson))
    s"filled $ref (${text.length} chars)"

  /**
   * browser_wait：等待 text 出现在页面正文 / url_contains 出现在地址中（两者 AND）；
   * 二者皆空则仅停顿 ms。预算上限 30 秒，超时显式报错（不静默通过）。
   */
  def wait(name: String, text: Option[String], urlContains: Option[String], ms: Option[Long], home: Path = homeRoot()): String =
    val h = existing(name, home)
    val budget = BrowserPolicy.clampLong(ms.getOrElse(BrowserPolicy.DefaultWaitMs), 0L, BrowserPolicy.MaxWaitMs)
    if text.isEmpty && urlContains.isEmpty then
      Thread.sleep(budget)
      s"waited ${budget}ms"
    else
      val expr = waitExpr(text, urlContains)
      if waitUntil(budget)(evalValue(h.page, expr).asBoolean.getOrElse(false)) then "condition met"
      else throw new IllegalStateException(s"等待超时（${budget}ms）条件未满足。")

  private def waitExpr(text: Option[String], urlContains: Option[String]): String =
    val t = text.getOrElse("").asJson.noSpaces
    val u = urlContains.getOrElse("").asJson.noSpaces
    "(function(t,u){var b=(document.body&&document.body.innerText)||'';" +
      "var okT=!t||b.indexOf(t)>=0;var okU=!u||location.href.indexOf(u)>=0;return okT&&okU;})(" +
      t + "," + u + ")"

  private def stamp(): String =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(java.time.LocalDateTime.now)

  private[browser] def writePrivateScreenshot(file: Path, bytes: Array[Byte]): Unit =
    import java.nio.file.attribute.PosixFilePermissions
    if Files.getFileStore(file.getParent).supportsFileAttributeView("posix") then
      val attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
      Files.createFile(file, attrs)
      Files.write(file, bytes)
    else
      Files.write(file, bytes)
    ()

  /** browser_screenshot：PNG 落盘到 screenshots/<session>/，返回绝对路径（视觉判定请用 Read 打开）。 */
  def screenshot(name: String, fullPage: Boolean, home: Path = homeRoot()): String =
    val h = existing(name, home)
    val res = h.page.send(
      "Page.captureScreenshot",
      Json.obj("format" -> "png".asJson, "captureBeyondViewport" -> fullPage.asJson),
      30.seconds
    )
    val data = res.hcursor.get[String]("data").getOrElse(throw new IllegalStateException("截图无数据"))
    val dir = home.resolve("screenshots").resolve(name)
    Files.createDirectories(dir)
    val file = dir.resolve(stamp() + ".png")
    writePrivateScreenshot(file, Base64.getDecoder.decode(data))
    file.toAbsolutePath.toString

  /**
   * 文件快照：名字 → (大小, mtime)。按名字比较会漏掉「同名覆盖」的新下载（上次运行留下的同名文件），
   * 所以新增 / 大小或 mtime 变化都视为本次下载产物。
   */
  private def listFiles(dir: Path): Map[String, (Long, Long)] =
    val st = Files.list(dir)
    try
      st.iterator.asScala.toList
        .filter(p => Files.isRegularFile(p))
        .map(p => p.getFileName.toString -> (Files.size(p), Files.getLastModifiedTime(p).toMillis))
        .toMap
    finally st.close()

  private def listNew(dir: Path, before: Map[String, (Long, Long)]): List[Path] =
    val st = Files.list(dir)
    try
      st.iterator.asScala.toList.filter { p =>
        val n = p.getFileName.toString
        val sig = (Files.size(p), Files.getLastModifiedTime(p).toMillis)
        !n.endsWith(".crdownload") && Files.isRegularFile(p) && !before.get(n).contains(sig)
      }
    finally st.close()

  /**
   * browser_download：点击 ref 触发下载，浏览器下载行为固定到 downloads/<session>/，
   * 等待出现新的完成文件（大小连续两次采样不变）。超时显式报错。
   * 下载行为是浏览器级设置：finally 中恢复为 default，避免影响用户其它会话。
   */
  def download(name: String, ref: String, timeoutMs: Long, home: Path = homeRoot()): String =
    val h = existing(name, home)
    val staging = home.resolve("downloads").resolve(name)
    Files.createDirectories(staging)
    val wsUrl = CdpConnection.browserWsUrl(h.record.port).getOrElse(throw new IllegalStateException("无浏览器级 CDP 目标"))
    val bconn = CdpConnection.connect(wsUrl)
    try
      bconn.send(
        "Browser.setDownloadBehavior",
        Json.obj(
          "behavior" -> "allow".asJson,
          "downloadPath" -> staging.toAbsolutePath.toString.asJson,
          "eventsEnabled" -> true.asJson
        )
      )
      val before = listFiles(staging)
      clickAt(h, backendIdOf(h, ref))
      val budget = BrowserPolicy.clampLong(timeoutMs, 1000L, BrowserPolicy.MaxDownloadTimeoutMs)
      val deadline = System.currentTimeMillis + budget
      var prev = Map.empty[Path, Long]
      var done: Option[Path] = None
      while done.isEmpty && System.currentTimeMillis < deadline do
        Thread.sleep(500)
        val now = listNew(staging, before).map(p => p -> Files.size(p)).toMap
        done = now.collectFirst { case (p, s) if s > 0 && prev.get(p).contains(s) => p }
        prev = now
      done match
        case Some(p) => s"downloaded ${p.toAbsolutePath} (${Files.size(p)} bytes)"
        case None => throw new IllegalStateException(s"下载超时（${budget}ms）：未出现完成的新文件。")
    finally
      Try(bconn.send("Browser.setDownloadBehavior", Json.obj("behavior" -> "default".asJson)))
      bconn.close()

  /** browser_state：健康与登录态探针（纯读）。DEAD = 注册表有记录但进程/端口已退出；NONE = 无记录。 */
  def state(name: String, home: Path = homeRoot()): String =
    readRecord(home, name) match
      case None =>
        Json.obj("session" -> name.asJson, "alive" -> false.asJson, "state" -> "NONE".asJson).spaces2
      case Some(rec) if !isLive(rec) =>
        Json.obj("session" -> name.asJson, "alive" -> false.asJson, "state" -> "DEAD".asJson).spaces2
      case Some(_) =>
        pageInfoJson(name, existing(name, home)).spaces2

  /**
   * browser_close：优雅 Browser.close → 仅当 pid 仍是本会话记录的 Chrome 可执行文件时发 SIGTERM
   * （防 pid 复用误杀）→ 校验 pid 消失与端口无监听，把校验结果写进回执。profile 保留。
   */
  def close(name: String, home: Path = homeRoot()): String = takeoverRegistry.withVerb(name) {
    takeoverRegistry.remove(name)
    closeHandle(name)
    readRecord(home, name) match
      case None => s"no session '$name' (nothing to close)"
      case Some(r) =>
        if CdpConnection.cdpAlive(r.port) then
          CdpConnection.browserWsUrl(r.port).foreach { u =>
            Try {
              val c = CdpConnection.connect(u)
              try c.send("Browser.close") finally c.close()
            }
          }
        waitUntil(5000L)(!r.pid.exists(pidAlive))
        r.pid.foreach { pid =>
          if pidAlive(pid) && commandOf(pid) == r.chromePath then
            ProcessHandle.of(pid).get.destroy()
        }
        val pidGone = waitUntil(3000L)(!r.pid.exists(pidAlive))
        val portFree = waitUntil(3000L)(!CdpConnection.cdpAlive(r.port))
        deleteRecord(home, name)
        logger.infoSync("browser session closed", "session" -> name, "pidGone" -> pidGone.toString)
        s"closed session=$name pid_gone=$pidGone port_listener=${!portFree}"
  }

end BrowserSessions
