package nebflow.shared

/**
 * LogNoiseHarness —— 日志降噪的**真机取数**跑车（日志降噪批 C，2026-11-04）。
 *
 * 为什么不是"单测"：本跑车是一个**独立 JVM 进程**，用**生产 logback.xml 原文**
 * （`-Dlogback.configurationFile=src/main/resources/logback.xml`）把日志写进
 * `$STAND_HOME/logs/stand.log`，行文经生产同款 encoder（`%d [%thread] %replace(%msg){…}%n`，
 * 剥 ANSI）。跑完由外部 `wc -l` / `grep -c` 对**盘上真实文件**取数 —— 行数是文件里
 * 长出来的，不是断言里算出来的。
 *
 * Environment model: today's live log (09:09→10:52) carries `presence sendData` 49 539
 * and `session-sync push` 49 539 lines over 6 201 s — **8.0 lines/s each** (16/s total).
 * This harness reproduces that rate (one tick every 125 ms emits one line of EACH family),
 * with device/channel/connection-state/session/status/bytes/targets all taken from the
 * measured distribution.
 *
 * Args: `--durationSec N` (default 125 = two 60s windows + tail), `--label X` (for printing).
 *
 * 跑法（隔离，绝不碰生产 6138）：
 * {{{
 *   STAND_HOME=/tmp/logslim-<name> sbt \
 *     "set Test/fork := true" \
 *     "set Test/javaOptions ++= Seq(\"-Dlogback.configurationFile=$PWD/src/main/resources/logback.xml\")" \
 *     "Test/runMain nebflow.shared.LogNoiseHarness --durationSec 125 --label aggregate"
 * }}}
 */
object LogNoiseHarness:

  /** 现网实测权重（stand.log 2026-11-04 09:09→10:52 统计）。 */
  private val statusWeights: Vector[(String, Int)] = Vector(
    "completed" -> 42267,
    "cancelled" -> 3839,
    "failed" -> 3313,
    "running" -> 110,
    "ready-to-merge" -> 8,
    "frozen" -> 2
  )

  private val statusSampler: Vector[String] =
    statusWeights.flatMap((st, n) => Vector.fill(math.max(1, n / 40))(st))

  /** 现网会话 id 前缀分布（1 651 个不同会话/1.72h ⇒ 每分钟 ~16 个新会话）。 */
  private val sessionPrefixes: Vector[(String, Int)] = Vector(
    "delegate-nebflow-" -> 29291,
    "subagent-" -> 9540,
    "delegate-Explorer-" -> 3562,
    "delegate-kernel-" -> 3030,
    "delegate-design-engineer-" -> 1837,
    "delegate-Coder-" -> 996,
    "subtask-" -> 861,
    "external-codex-" -> 210
  )

  private val sessionPrefixSampler: Vector[String] =
    sessionPrefixes.flatMap((p, n) => Vector.fill(math.max(1, n / 30))(p))

  private val deviceId = "40742cd4-851a-4ec4-a49d-6db2509bf03d"

  def main(args: Array[String]): Unit =
    var durationSec = 125
    var label = "run"
    var i = 0
    while i < args.length do
      args(i) match
        case "--durationSec" if i + 1 < args.length =>
          durationSec = args(i + 1).toInt; i += 2
        case "--label" if i + 1 < args.length =>
          label = args(i + 1); i += 2
        case _ => i += 1
    val log = NebflowLogger.forName("nebflow.neblink.session-sync")
    val presenceLog = NebflowLogger.forName("nebflow.neblink.presence")

    // 会话池：每分钟翻一批（现网每分钟 ~16 个新会话 ⇒ 池里同时活跃约 16 个）
    def sessionPoolAt(minute: Long): Vector[String] =
      val rnd = new scala.util.Random(minute * 7919L + 13L)
      Vector.fill(16) {
        val prefix = sessionPrefixSampler(rnd.nextInt(sessionPrefixSampler.size))
        prefix + java.lang.Long.toHexString(rnd.nextLong() >>> 12)
      }

    val startedAt = System.currentTimeMillis()
    var presenceCalls = 0L
    var syncCalls = 0L
    val tickMs = 125L
    var tick = 0L
    val deadlineMs = startedAt + durationSec * 1000L
    System.err.println(
      s"[harness] start label=$label mode=${TransportLog.currentMode.label} " +
        s"windowSec=${TransportLog.windowSec} durationSec=$durationSec pid=${ProcessHandle.current().pid()}"
    )
    while System.currentTimeMillis() < deadlineMs do
      tick += 1
      val now = System.currentTimeMillis()
      val minute = (now - startedAt) / 60_000L
      // presence：现网 99.97% agent-session + 每 ~1600 拍一发 agent-identity；连接态恒 false
      val channel = if tick % 1600 == 0 then "agent-identity" else "agent-session"
      TransportLog.presenceSendData(presenceLog, deviceId, channel, connectionsHasKey = false)
      presenceCalls += 1
      // session-sync：会话按分钟翻批、状态按现网权重抽、载荷字节按现网区间、targets=1
      val rnd = new scala.util.Random(now)
      val pool = sessionPoolAt(minute)
      val session = pool(rnd.nextInt(pool.size))
      val status = statusSampler(rnd.nextInt(statusSampler.size))
      val bytes = 400 + rnd.nextInt(120)
      TransportLog.sessionSyncPush(log, session, status, now, bytes, 1)
      syncCalls += 1
      val next = startedAt + tick * tickMs
      val sleepMs = next - System.currentTimeMillis()
      if sleepMs > 0 then Thread.sleep(sleepMs)
    end while
    // 收尾：最后一个不满窗也落盘（shutdown hook 同语义，这里显式冲一次）
    TransportLog.flushNow()
    Thread.sleep(300L) // 给 appender 落盘时间
    System.err.println(
      s"[harness] done label=$label mode=${TransportLog.currentMode.label} " +
        s"presenceCalls=$presenceCalls syncCalls=$syncCalls " +
        s"elapsedSec=${(System.currentTimeMillis() - startedAt) / 1000}"
    )
    System.exit(0)
  end main
end LogNoiseHarness
