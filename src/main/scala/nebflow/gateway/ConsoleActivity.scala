package nebflow.gateway

import cats.effect.IO
import nebflow.shared.{Branding, PathUtil}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/**
 * 控制台在用判据（「工作过程中被开新标签页」批 2026-11-05 · 作者令）。
 *
 * ── 要治的现象 ──────────────────────────────────────────────────────────────
 * 作者已经在控制台工作着，浏览器里**莫名其妙冒出一个新标签页**，该标签页继续
 * 走门户自动进控制台 ⇒「又进了一次控制台」。
 *
 * ── 本对象管的那一半 ────────────────────────────────────────────────────────
 * 引擎侧唯一会「自己开浏览器」的两个调用点（`GatewayMain.browserLauncher`
 * boot 门 / `SingleInstanceGuard.openBrowserTo` focus 门）都必须在开门前问一句
 * **「作者是不是正在用控制台？」**——在用就**不许开**，事件只落日志。
 *
 * 判据是机械的、跨进程可读的：一个**控制台在用标记件**
 * `<dataRoot>/state/console-activity`，mtime = 最近一次控制台证据的时刻。
 * 写入点（本批接的三处，全部是「控制台真的在用」的服务端事实）：
 *   ① `WebSocketRoutes` 控制台连接建立；
 *   ② `WebSocketRoutes` 控制台连接收到任一文本帧（节流写，最密 5s 一次）；
 *   ③ `ConsoleTicketRoutes` 一次性 console ticket 兑换成功（= 页面刚落地）。
 *
 * 为什么用文件而不是内存：boot 门要能回答「**上一个**引擎生命周期里作者是不是
 * 正在用」（重启后控制台页会自己重连，不需要谁替他开窗）；focus 门要能回答
 * 「**另一个**进程里的实例是不是在用」（CLI 进程读不到网关的内存）。
 *
 * ── 判据口径 ────────────────────────────────────────────────────────────────
 *   `now - mtime < windowSec`（默认 900s）⇒ 在用 ⇒ 抑制开门。
 * 覆盖不到的面（如实登记，不装作用）：浏览器标签页开着但控制台 WS 早已断开、
 * 且窗口内零证据 ⇒ 判「不在用」⇒ 允许开门（这正是「作者没在用、需要进控制台」
 * 的正常入口，不能一刀切禁掉）。
 *
 * ── 逃生口 ──────────────────────────────────────────────────────────────────
 *   `NEBFLOW_DOOR_INUSE_POLICY=open`   ⇒ 恢复「无条件开门」（旧行为，逐字）；
 *   `NEBFLOW_DOOR_INUSE_WINDOW_SEC=N`  ⇒ 调窗口（0 ⇒ 等价于从不判在用）。
 * 两个都经 `Branding.env` 双前缀读（`STAND_*` / `NEBFLOW_*`），与 PortalUrl 同源。
 */
object ConsoleActivity:

  /** 窗口秒数 env（裸名，经 Branding.env 双前缀读）。 */
  val WindowEnv: String = "DOOR_INUSE_WINDOW_SEC"
  /** 策略 env：`suppress`（默认）/ `open`。 */
  val PolicyEnv: String = "DOOR_INUSE_POLICY"

  val PolicySuppress: String = "suppress"
  val PolicyOpen: String = "open"

  /** 默认窗口：15 分钟（一次「工作着」的判定粒度，远大于心跳 30s 的若干拍）。 */
  val DefaultWindowSec: Long = 900L

  /** 标记件写入节流：最密 5s 一次（每帧都落盘 = 白写磁盘，判据粒度不需要更细）。 */
  private val TouchThrottleMs: Long = 5000L

  /** boot 门判词（开 / 不开 + 人话理由），两条腿都只消费它。 */
  final case class Verdict(open: Boolean, why: String)

  /**
   * 纯判据核（env 注入式，可测）：
   *
   * @param policy     [[PolicySuppress]] / [[PolicyOpen]]
   * @param windowSec  「在用」窗口；`<= 0` ⇒ 从不判在用
   * @param lastMs     最近一次控制台证据的毫秒时刻（None = 本机从未有过控制台）
   * @param nowMs      当前毫秒
   */
  def decide(policy: String, windowSec: Long, lastMs: Option[Long], nowMs: Long): Verdict =
    if policy != PolicySuppress then Verdict(true, s"policy=${PolicyOpen}")
    else if windowSec <= 0L then Verdict(true, "in-use window disabled (NEBFLOW_DOOR_INUSE_WINDOW_SEC<=0)")
    else
      lastMs match
        case None => Verdict(true, "no console activity on record")
        case Some(ms) =>
          val ageSec = (nowMs - ms) / 1000L
          if nowMs - ms < windowSec * 1000L then
            Verdict(false, s"console in use (last console activity ${ageSec}s ago < window ${windowSec}s)")
          else Verdict(true, s"console idle (last console activity ${ageSec}s ago >= window ${windowSec}s)")

  /** 标记件路径（`<dataRoot>/state/console-activity`）。 */
  def markerPath: Path = Paths.get(PathUtil.dataRoot.toString, "state", "console-activity")

  @volatile private var lastTouchMs: Long = 0L

  /**
   * 落一笔控制台证据（节流）。**绝不失败**：标记件是判据的辅助面，任何 IO 错误
   * 都吞掉——开不了记录比拦住请求好得多（同 `NearPathStats` 的 best-effort 纪律）。
   *
   * @param source 证据来源（落到文件内容，仅供人读；判据只看 mtime）
   */
  def touch(source: String): IO[Unit] =
    IO.blocking {
      val now = System.currentTimeMillis()
      if now - lastTouchMs >= TouchThrottleMs then
        lastTouchMs = now
        try
          val p = markerPath
          Files.createDirectories(p.getParent)
          Files.write(
            p,
            s"$now $source\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        catch case _: Throwable => ()
    }

  /** 最近一次控制台证据的毫秒时刻（标记件 mtime）。读失败 ⇒ None（= 不在用）。 */
  def lastActivityMs: IO[Option[Long]] =
    IO.blocking {
      try
        val p = markerPath
        if Files.exists(p) then Some(Files.getLastModifiedTime(p).toMillis) else None
      catch case _: Throwable => None
    }

  private def envWindowSec: Long =
    Branding
      .env(WindowEnv)
      .flatMap(_.trim.toLongOption)
      .filter(_ >= 0L)
      .getOrElse(DefaultWindowSec)

  private def envPolicy: String =
    Branding
      .env(PolicyEnv)
      .map(_.trim.toLowerCase)
      .filter(p => p == PolicySuppress || p == PolicyOpen)
      .getOrElse(PolicySuppress)

  /** Live 判据（读真 env + 真标记件）：两条开门腿共用。 */
  def verdict: IO[Verdict] =
    lastActivityMs.map(last => decide(envPolicy, envWindowSec, last, System.currentTimeMillis()))
      .handleError(_ => Verdict(true, "in-use probe failed — defaulting to open"))

  /** focus 门审计件（`<dataRoot>/logs/door-focus.log`，追加）。 */
  def auditLogPath: Path = Paths.get(PathUtil.dataRoot.toString, "logs", "door-focus.log")

  /**
   * 落一行**持久**开门审计（best-effort，绝不失败）。
   *
   * 为什么需要它：focus 门的产出走 CLI 进程的 stdout（`Main.portGateThen` 里
   * `IO.println`），二实例撞端口时那行只活在那个**第二进程**的终端里——宿主
   * `stand.log` 里一行都没有。2026-11-05 的取证正是卡在这一格：作者工作期间冒出
   * 一个本地 origin 的控制台标签页，而宿主日志「零开门记录」，谁也说不清是谁开的。
   * 本件把同一判词写进 `<dataRoot>/logs/door-focus.log`（追加），**不依赖任何进程
   * 的 stdout 是否还在**——下次再发生，`grep door-focus` 就能点名时刻与判据。
   */
  def audit(line: String): IO[Unit] =
    IO.blocking {
      try
        val p = auditLogPath
        Files.createDirectories(p.getParent)
        Files.write(
          p,
          s"${java.time.Instant.now()} $line\n".getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.WRITE
        )
      catch case _: Throwable => ()
    }
