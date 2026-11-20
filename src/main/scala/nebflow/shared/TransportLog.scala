package nebflow.shared

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.collection.mutable

/**
 * TransportLog —— **传输面高频日志的降噪闸**（日志降噪批 C，作者令 2026-11-04 10:43）。
 *
 * ── 为什么要有这一层 ──────────────────────────────────────────────────────
 * 现网实测（2026-11-04 09:09→10:45，`~/.stand/logs/stand.log`）：`presence sendData`
 * 45 881 行 + `session-sync push` 45 853 行 = **91 734 / 103 415 = 88.7%** 的当日日志
 * 是这两条 INFO 成功路径的逐条回声。它们的信息量接近零（同一设备同一通道每秒重复），
 * 却把真有价值的 WARN/ERROR 冲散、把日志卷在 50MB 处反复滚动（今日 1h36m 写了 20MB）。
 *
 * ── 三条硬口径（本闸的设计约束）────────────────────────────────────────────
 *  1. **只动日志面，不动行为**：调用点只换「怎么记」，`sendData` 照样发帧、
 *     `pushTask` 照样推目标、返回值与副作用一律不变（本批 diff 里两处都只是把
 *     一行 `logger.info*` 换成一行 `TransportLog.*`）。
 *  2. **不许硬删**：原始逐条行文的构造器（[[presenceMessage]] / [[sessionSyncMessage]]）
 *     是唯一出处，per-line / debug 两种模式**逐字节**复用它 —— 排查时 `grep` 老行文
 *     照旧可用，回切开关一开就回到降噪前形态。
 *  3. **失败/非预期分支不降级**：本闸只接管两条「成功路径」INFO。所有失败、拒绝、
 *     断线、重连、兜底腿失败的行（`session-sync frame REJECTED` = WARN、
 *     `relay fallback failed` / `WS send … did not complete` = DEBUG 保持原级）
 *     **不经过本闸**，级别分毫不动。
 *
 * ── 三种模式（缺省 = 安静形态）───────────────────────────────────────────
 * | 模式 | 触发 | 输出 |
 * |---|---|---|
 * | `aggregate`（**缺省**） | `-Dnebflow.log.transport.mode=aggregate` 或不设 | 每个窗口每个家族**一行摘要**：`×N in 60s (devices=… conn{…} top[…])` |
 * | `per-line`（**回切**） | `-Dnebflow.log.transport.mode=per-line`（或 env `NEBFLOW_LOG_TRANSPORT_MODE=per-line`） | 逐条 INFO，行文与降噪前逐字节相同 |
 * | `debug` | `-Dnebflow.log.transport.mode=debug` | 逐条 **DEBUG**（痕迹不丢但让出 INFO 面；FILE appender threshold=DEBUG ⇒ 仍在盘上可查） |
 *
 * 窗口：`-Dnebflow.log.transport.windowSec=60`（env `NEBFLOW_LOG_TRANSPORT_WINDOW_SEC`），
 * 缺省 60s。窗口冲刷线程是 daemon（不拖住 JVM 退出），另挂一个 shutdown hook 把
 * 最后一个**不满窗**的计数也冲出来 —— 引擎停/重启不吞帐。
 *
 * ── 基数帽 ──────────────────────────────────────────────────────────────
 * 单家族窗口内不同键（设备×通道 / 会话×状态）上限 [[MaxDistinctKeys]]，超出并入
 * `(+other)` 桶。降噪本身绝不能变成新的内存/延迟风险：一段窗口里的键数由「在线
 * 设备数 × 通道数」或「活跃会话数」决定，正常量级是两位数，帽只是故障期的护栏。
 */
object TransportLog:

  // ── 配置面 ─────────────────────────────────────────────────────────────

  /** 输出形态。 */
  enum Mode(val label: String):
    /** 缺省：按窗口聚合成一行摘要。 */
    case Aggregate extends Mode("aggregate")

    /** 回切：逐条 INFO（降噪前原貌，行文逐字节相同）。 */
    case PerLine extends Mode("per-line")

    /** 逐条 DEBUG（保留痕迹、让出 INFO 面）。 */
    case Debug extends Mode("debug")

  object Mode:

    def parse(raw: String): Option[Mode] =
      Option(raw).map(_.trim.toLowerCase) match
        case Some("aggregate") | Some("agg") | Some("quiet") => Some(Mode.Aggregate)
        case Some("per-line") | Some("perline") | Some("line") | Some("verbose") =>
          Some(Mode.PerLine)
        case Some("debug") => Some(Mode.Debug)
        case _ => None

  /** 模式开关（系统属性优先，其次 env，最后缺省 aggregate）。 */
  final val ModeProperty: String = "nebflow.log.transport.mode"
  final val ModeEnv: String = "NEBFLOW_LOG_TRANSPORT_MODE"

  /** 聚合窗口开关（秒）。 */
  final val WindowProperty: String = "nebflow.log.transport.windowSec"
  final val WindowEnv: String = "NEBFLOW_LOG_TRANSPORT_WINDOW_SEC"

  final val DefaultWindowSec: Long = 60L

  /** 单家族窗口内不同键上限（故障期护栏；见类注释「基数帽」）。 */
  final val MaxDistinctKeys: Int = 512

  /** 超出基数帽后所有调用并入的桶名。 */
  final val OverflowKey: String = "(+other)"

  /** 摘要里 top 列表的条数上限。 */
  final val TopListSize: Int = 5

  private def configuredMode: Mode =
    sys.props
      .get(ModeProperty)
      .orElse(sys.env.get(ModeEnv))
      .flatMap(Mode.parse)
      .getOrElse(Mode.Aggregate)

  private def configuredWindowSec: Long =
    val raw = sys.props.get(WindowProperty).orElse(sys.env.get(WindowEnv))
    val parsed = raw.flatMap(v => scala.util.Try(v.trim.toLong).toOption).getOrElse(DefaultWindowSec)
    if parsed < 1L then DefaultWindowSec else parsed

  private val modeOverride = new AtomicReference[Option[Mode]](None)

  /** 当前生效模式（测试可经 [[setModeForTest]] 覆盖）。 */
  def currentMode: Mode = modeOverride.get().getOrElse(configuredModeCache)

  /** 当前生效窗口（秒）。 */
  def windowSec: Long = windowSecCache

  // 属性只在 JVM 生命周期内读一次（每次日志调用都读 System props 会白付一把
  // Hashtable 锁 —— 降噪本身不该成为发送面的成本）。
  private lazy val configuredModeCache: Mode = configuredMode
  private lazy val windowSecCache: Long = configuredWindowSec

  // ── 原始行文（唯一出处；per-line / debug 模式逐字节复用）─────────────────

  /** 降噪前 `presence sendData` 的 INFO 行文（逐字节保留，回切形态）。 */
  def presenceMessage(deviceId: String, channel: String, connectionsHasKey: Boolean): String =
    s"presence sendData: device=$deviceId channel=$channel connectionsHasKey=$connectionsHasKey"

  /** 降噪前 `session-sync push` 的 INFO 行文（逐字节保留，回切形态）。 */
  def sessionSyncMessage(
    sessionId: String,
    status: String,
    updatedAt: Long,
    bytes: Int,
    targets: Int
  ): String =
    s"session-sync push: session=$sessionId status=$status updatedAt=$updatedAt bytes=$bytes targets=$targets"

  // ── 计数仓（每家族一个；record 与 flush 同锁 ⇒ 不会丢拍也不会重复计）──────

  private final class FamilyState(val name: String):
    private val lock = new Object
    private val counts = mutable.HashMap.empty[String, Long]
    private var total = 0L
    private var bytesSum = 0L
    private var targetsSum = 0L
    @volatile var logger: NebflowLogger = null
    @volatile var windowStartMs: Long = System.currentTimeMillis()

    def record(log: NebflowLogger, key: String, bytes: Int, targets: Int): Unit =
      if logger eq null then logger = log
      lock.synchronized {
        val k =
          if counts.contains(key) || counts.size < MaxDistinctKeys then key
          else OverflowKey
        counts.update(k, counts.getOrElse(k, 0L) + 1L)
        total += 1L
        if bytes > 0 then bytesSum += bytes.toLong
        if targets > 0 then targetsSum += targets.toLong
      }

    /** 取走本窗全部读数并复位（窗口起点翻成本次冲刷时刻）。 */
    def drain(): FamilyReading =
      lock.synchronized {
        val now = System.currentTimeMillis()
        val reading = FamilyReading(
          counts = counts.toMap,
          total = total,
          bytesSum = bytesSum,
          targetsSum = targetsSum,
          windowStartMs = windowStartMs,
          windowEndMs = now
        )
        counts.clear()
        total = 0L
        bytesSum = 0L
        targetsSum = 0L
        windowStartMs = now
        reading
      }

    /** 仅测试用：清空计数而不产生摘要。 */
    def clear(): Unit = lock.synchronized {
      counts.clear()
      total = 0L
      bytesSum = 0L
      targetsSum = 0L
      windowStartMs = System.currentTimeMillis()
    }

    /** 仅测试用：当前未冲刷的调用数。 */
    def pendingTotal: Long = lock.synchronized(total)

  end FamilyState

  /** 一个窗口的读数（`counts` 键 = 家族自有维度键）。 */
  final case class FamilyReading(
    counts: Map[String, Long],
    total: Long,
    bytesSum: Long,
    targetsSum: Long,
    windowStartMs: Long,
    windowEndMs: Long
  ):

    def windowSec: Long =
      math.max(1L, math.round((windowEndMs - windowStartMs) / 1000.0))

  private val PresenceFamily = "presence"
  private val SessionSyncFamily = "session-sync"

  private val presenceState = new FamilyState(PresenceFamily)
  private val sessionSyncState = new FamilyState(SessionSyncFamily)

  // ── 记录入口（两个调用点唯一接触面）─────────────────────────────────────

  /**
   * `presence sendData` 的一次记账（**只记日志，不碰收发**）。
   *
   * 语义 = 降噪前那行 `logger.infoSync`：成功路径的读数。`connectionsHasKey` 是
   * 发送时的连接表快照（false = 无直连 ⇒ 上层会走 relay 兜底，那条兜底失败仍有
   * 自己的 DEBUG/WARN 行，不经本闸）。
   */
  def presenceSendData(
    log: NebflowLogger,
    deviceId: String,
    channel: String,
    connectionsHasKey: Boolean
  ): Unit =
    val msg = presenceMessage(deviceId, channel, connectionsHasKey)
    emit(
      log,
      msg,
      presenceState,
      key = s"$deviceId|$channel|$connectionsHasKey",
      bytes = 0,
      targets = 0
    )

  end presenceSendData

  /**
   * `session-sync push` 的一次记账（**只记日志，不碰推送**）。
   *
   * 语义 = 降噪前那几行 `logger.info`：单会话外推的读数（bytes = 载荷字节数，
   * targets = 本轮目标设备数）。推送失败分支（`device … unreachable`）保持原级。
   */
  def sessionSyncPush(
    log: NebflowLogger,
    sessionId: String,
    status: String,
    updatedAt: Long,
    bytes: Int,
    targets: Int
  ): Unit =
    val msg = sessionSyncMessage(sessionId, status, updatedAt, bytes, targets)
    emit(log, msg, sessionSyncState, key = s"$sessionId|$status", bytes = bytes, targets = targets)

  private def emit(
    log: NebflowLogger,
    msg: String,
    state: FamilyState,
    key: String,
    bytes: Int,
    targets: Int
  ): Unit =
    currentMode match
      case Mode.PerLine => log.infoSync(msg)
      case Mode.Debug => log.debugSync(msg)
      case Mode.Aggregate =>
        if state.logger eq null then state.logger = log
        state.record(log, key, bytes, targets)
        ensureFlusher()

  // ── 摘要面 ─────────────────────────────────────────────────────────────

  /** 立即冲刷两个家族（窗口到期、优雅停机、以及测试取数）。 */
  def flushNow(): Unit =
    try
      emitSummary(presenceState, presenceSummary)
      emitSummary(sessionSyncState, sessionSyncSummary)
    catch case _: Throwable => () // 记账面绝不因日志面抛错

  private def emitSummary(state: FamilyState, render: FamilyReading => String): Unit =
    val reading = state.drain()
    if reading.total > 0 then
      val log = state.logger
      Option(log).foreach(_.infoSync(render(reading)))

  /** 键拆解结果（`a|b|c` 形态；缺段一律补 `?`，绝不在日志面抛错）。 */
  private def splitKey(key: String, n: Int): List[String] =
    val parts = key.split('|').toList
    parts ++ List.fill(math.max(0, n - parts.size))("?")

  /** `presence sendData ×N in Ts (devices=K channels=C conn{true=a,false=b} top[…])` */
  private[shared] def presenceSummary(r: FamilyReading): String =
    val entries = r.counts.iterator.map { (key, n) =>
      val parts = splitKey(key, 3)
      (parts(0), parts(1), parts(2), n)
    }.toList
    val known = entries.filter(_._1 != OverflowKey)
    val devices = known.map(_._1).distinct.size
    val channels = known.map(_._2).distinct.size
    val connTrue = entries.filter(_._3 == "true").map(_._4).sum
    val connFalse = entries.filter(_._3 == "false").map(_._4).sum
    val top = topList(entries.map((dev, _, conn, n) => s"$dev(conn=$conn)" -> n))
    s"presence sendData ×${r.total} in ${r.windowSec}s (devices=$devices channels=$channels " +
      s"conn{true=$connTrue,false=$connFalse} top[$top])"

  /** `session-sync push ×N in Ts (sessions=M status{…} targetsAvg=… bytesAvg=… top[…])` */
  private[shared] def sessionSyncSummary(r: FamilyReading): String =
    val entries = r.counts.iterator.map { (key, n) =>
      val parts = splitKey(key, 2)
      (parts(0), parts(1), n)
    }.toList
    val known = entries.filter(_._1 != OverflowKey)
    val sessions = known.map(_._1).distinct.size
    val statusCounts = entries
      .groupBy(_._2)
      .map((st, rows) => st -> rows.map(_._3).sum)
    val statusStr = statusCounts.toList.sortBy((st, n) => (-n, st)).map((st, n) => s"$st=$n").mkString(",")
    val targetsAvg = f"${r.targetsSum.toDouble / r.total.toDouble}%.1f"
    val bytesAvg = if r.total > 0 then r.bytesSum / r.total else 0L
    val top = topList(entries.map((sid, st, n) => s"$sid($st)" -> n))
    s"session-sync push ×${r.total} in ${r.windowSec}s (sessions=$sessions " +
      s"status{$statusStr} targetsAvg=$targetsAvg bytesAvg=$bytesAvg top[$top])"

  end sessionSyncSummary

  /** 计数从高到低取前 [[TopListSize]] 条，余量折成 `+N more`。 */
  private def topList(entries: List[(String, Long)]): String =
    val sorted = entries.sortBy(e => (-e._2, e._1))
    val head = sorted.take(TopListSize).map((k, n) => s"$k=$n").mkString(",")
    val rest = sorted.size - TopListSize
    if rest > 0 then s"$head,+$rest more" else head

  // ── 窗口冲刷（懒启动 daemon）────────────────────────────────────────────

  private lazy val scheduler: ScheduledExecutorService =
    val factory = new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r, "nebflow-log-noise-flusher")
        t.setDaemon(true)
        t
    val ex = Executors.newSingleThreadScheduledExecutor(factory)
    val periodMs = math.max(1L, windowSec) * 1000L
    // 冲账动作先落成具名 Runnable（格式批 2026-11-21）：`catch case … => ()` 直接写成
    // scheduleWithFixedDelay 的实参时，scalafmt 会把后继实参折进 catch 行
    // （`catch case _: Throwable => (), periodMs,`），该形态连它自己都无法再解析
    // （scalafmtCheck 直接 parse error）⇒ 提为局部值，实参与处理器都不再相邻。
    val flushOnce: Runnable = () =>
      try flushNow()
      catch case _: Throwable => ()
    ex.scheduleWithFixedDelay(flushOnce, periodMs, periodMs, TimeUnit.MILLISECONDS)
    // 停/重启不吞帐：最后一个不满窗的计数在 JVM 退出时也冲出来。
    // （JVM 已在退出过程中时 addShutdownHook 会抛 IllegalStateException —— 那不是
    //  发送面的错误，吞掉即可，绝不让日志面把异常带回 `sendData` 的调用者。）
    val flushAtStop = new Thread(flushOnce, "nebflow-log-noise-flush-on-stop")
    try Runtime.getRuntime.addShutdownHook(flushAtStop)
    catch case _: Throwable => ()
    ex

  end scheduler

  private def ensureFlusher(): Unit =
    // 触碰即初始化（单线程 daemon，幂等）。日志面任何异常都不许逃回业务调用者
    // （`sendData` 在 IO.blocking 里、`pushTask` 在推送链里），故整段兜底。
    try scheduler
    catch case _: Throwable => ()

  // ── 测试钩子（生产代码不得调用）────────────────────────────────────────

  /** 测试用：强制模式（`None` = 回到配置读取）。 */
  def setModeForTest(mode: Option[Mode]): Unit = modeOverride.set(mode)

  /** 测试用：清空计数与覆盖，回到初始态。 */
  def resetForTest(): Unit =
    presenceState.clear()
    sessionSyncState.clear()
    modeOverride.set(None)

  /** 测试用：当前未冲刷的调用数（不含摘要行），键 = 家族名。 */
  def pendingCounts: Map[String, Long] =
    Map(
      PresenceFamily -> presenceState.pendingTotal,
      SessionSyncFamily -> sessionSyncState.pendingTotal
    )
end TransportLog
