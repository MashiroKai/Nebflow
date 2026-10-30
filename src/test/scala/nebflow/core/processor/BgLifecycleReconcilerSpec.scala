package nebflow.core.processor

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.tools.{BgHealth, BgTaskLedger, BgTaskRegistry}
import nebflow.shared.PathUtil

import scala.compiletime.{constValueTuple, uninitialized}
import scala.deriving.Mirror

/**
 * 后台任务生命周期 P1 批（设计稿 20261030_background-task-lifecycle §3/§7-P1，台账
 * #1120）—— [[BgLifecycleReconciler]] 决策面 spec。钉面：
 *
 *   - 决策表全覆盖：healthy/suspect/escalated 三态迁移、进展复位（重排不判死）、
 *     幂等（已升级不重复升级）；
 *   - 判据数值 = 稿 §9-③ 默认档：suspect = 空闲 > max(基线×3, 10min)；escalated =
 *     催注 3 次（10/20/40min 退避）无进展 或 空闲 > 基线×10；基线 = 头 10min
 *     活跃间隔中位（自基线，§3.3/§9-②）；
 *   - nudge 到点断言（累计退避 10/30/70min）+ 429 风暴抑制（催注留任、徽标照刷）；
 *   - 零红线守门：ScanAction 封闭类型无 Kill case（Mirror.SumOf 编译+运行断言）
 *     + 本件源码零杀伤语义 token 扫描；
 *   - 执行面记账件回归锚：BgTaskLedger 升级审计落盘/重建读面、BgTaskRegistry
 *     unread 激活（unread 桩接 escalated 语义）。
 *
 * scanCore 为纯函数——全部用例零 IO 直接断言决策与记账；IO 件（账本/registry）
 * 用 PathUtil.setDataRoot 隔离临时根，结尾还根。
 */
class BgLifecycleReconcilerSpec extends FunSuite:

  private val min = 60_000L

  /** 稿默认档参数（与 Defaults.BgLifecycle* 现读默认值逐字一致）。 */
  private val p = BgLifecycleReconciler.Params(
    baselineWindowMs = 10 * min,
    suspectBaselineFactor = 3,
    suspectMinIdleMs = 10 * min,
    escalateBaselineFactor = 10,
    maxNudges = 3,
    nudgeFirstMs = 10 * min,
    nudgeMaxBackoffMs = 40 * min
  )

  private def view(
    job: String,
    startedAt: Long,
    now: Long,
    lines: Long = 0L,
    rowStampAgoMs: Option[Long] = None,
    health: String = BgHealth.Healthy
  ): BgLifecycleReconciler.TaskView =
    BgLifecycleReconciler.TaskView(
      jobId = job,
      sessionId = s"sid-$job",
      description = s"desc-$job",
      startedAtMs = startedAt,
      lineCount = lines,
      rowLastActivityMs = rowStampAgoMs.map(now - _).getOrElse(startedAt),
      rowHealth = health
    )

  private def scan1(
    tasks: List[BgLifecycleReconciler.TaskView],
    obs: Map[String, BgLifecycleReconciler.Observation],
    now: Long,
    storm: Boolean = false
  ): (List[BgLifecycleReconciler.ScanAction], Map[String, BgLifecycleReconciler.Observation]) =
    BgLifecycleReconciler.scanCore(tasks, obs, now, p, storm)

  // ── 退避表（纯函数钉）─────────────────────────────────────────────────────

  test("退避表：单次 10/20/40 封顶，累计到点偏移 10/30/70min（稿 §9-③ 10/20/40 档）") {
    assertEquals(BgLifecycleReconciler.nudgeDelayMs(1, p), 10 * min)
    assertEquals(BgLifecycleReconciler.nudgeDelayMs(2, p), 20 * min)
    assertEquals(BgLifecycleReconciler.nudgeDelayMs(3, p), 40 * min)
    assertEquals(BgLifecycleReconciler.nudgeDelayMs(4, p), 40 * min, "封顶后走平")
    assertEquals(BgLifecycleReconciler.nudgeDueOffsetMs(1, p), 10 * min)
    assertEquals(BgLifecycleReconciler.nudgeDueOffsetMs(2, p), 30 * min)
    assertEquals(BgLifecycleReconciler.nudgeDueOffsetMs(3, p), 70 * min)
    assertEquals(BgLifecycleReconciler.median(Vector.empty), 0L, "零采样冻结基线 = 0（地板档）")
    assertEquals(BgLifecycleReconciler.median(Vector(1L, 5L, 3L)), 3L)
    assertEquals(BgLifecycleReconciler.median(Vector(5 * min, 5 * min)), 5 * min)
  }

  // ── 决策表：healthy 轨 ────────────────────────────────────────────────────

  test("D1 静止任务（无进展未超时）⇒ 零健康动作；首拍观测初始化取 max(行戳, 起始戳)") {
    val t = view("j-d1", startedAt = 0L, now = 5 * min)
    val (actions, next) = scan1(List(t), Map.empty, 5 * min)
    assertEquals(actions, List(BgLifecycleReconciler.BadgeSync))
    assertEquals(next("j-d1").lastActivityMs, 0L)
    assertEquals(next("j-d1").lastLines, 0L)
  }

  test("D2 healthy 空闲超地板（零采样基线 ⇒ max(0×3,10min)=10min）⇒ MarkSuspect + suspectSince=now") {
    val t = view("j-d2", startedAt = 0L, now = 11 * min)
    val (actions, next) = scan1(List(t), Map.empty, 11 * min)
    assertEquals(actions.collect { case m: BgLifecycleReconciler.MarkSuspect => m.jobId }, List("j-d2"))
    assertEquals(next("j-d2").suspectSinceMs, 11 * min)
    // 未到地板：9min 静止 ⇒ 不动
    val (a9, _) = scan1(List(view("j-d2b", 0L, 9 * min)), Map.empty, 9 * min)
    assertEquals(a9, List(BgLifecycleReconciler.BadgeSync))
  }

  test("D3 行内活动戳增长（远端心跳喂缝）⇒ 进展：RecordActivity + 观测戳推进，不误判 suspect") {
    val t = view("j-d3", startedAt = 0L, now = 20 * min, rowStampAgoMs = Some(30_000L))
    val obs = Map("j-d3" -> BgLifecycleReconciler.Observation(lastLines = 0L, lastActivityMs = 19 * min))
    val (actions, next) = scan1(List(t), obs, 20 * min)
    assertEquals(actions.collect { case r: BgLifecycleReconciler.RecordActivity => r.jobId }, List("j-d3"))
    assertEquals(actions.collect { case m: BgLifecycleReconciler.MarkSuspect => m }, Nil)
    assertEquals(next("j-d3").lastActivityMs, 20 * min, "进展把权威戳推进到本拍")
  }

  // ── 决策表：suspect 轨（nudge 到点断言）───────────────────────────────────

  test("D4 suspect 退避阶梯：+10min 首催、+30min 二催、+70min 三催，提前一拍不催") {
    val ts = 11 * min // suspectSince
    def obsAt(nudges: Int, lastNudge: Long) =
      Map("j-d4" -> BgLifecycleReconciler.Observation(
        lastActivityMs = 0L, suspectSinceMs = ts, nudges = nudges, lastNudgeAtMs = lastNudge
      ))
    def tick(now: Long, nudges: Int, lastNudge: Long) =
      scan1(List(view("j-d4", 0L, now, health = BgHealth.Suspect)), obsAt(nudges, lastNudge), now)

    // 首催前 1ms：不催
    assertEquals(tick(ts + 10 * min - 1, 0, 0L)._1.collect { case n: BgLifecycleReconciler.Nudge => n }, Nil)
    // 首催到点：attempt=1
    val (a1, n1) = tick(ts + 10 * min, 0, 0L)
    assertEquals(a1.collect { case n: BgLifecycleReconciler.Nudge => n.attempt }, List(1))
    assertEquals(n1("j-d4").nudges, 1)
    assertEquals(n1("j-d4").lastNudgeAtMs, ts + 10 * min)
    // 二催到点（累计 30min）：attempt=2
    val (a2, n2) = tick(ts + 30 * min, 1, ts + 10 * min)
    assertEquals(a2.collect { case n: BgLifecycleReconciler.Nudge => n.attempt }, List(2))
    assertEquals(n2("j-d4").nudges, 2)
    // 三催到点（累计 70min）：attempt=3
    val (a3, n3) = tick(ts + 70 * min, 2, ts + 30 * min)
    assertEquals(a3.collect { case n: BgLifecycleReconciler.Nudge => n.attempt }, List(3))
    assertEquals(n3("j-d4").nudges, 3)
    // 三催未到期（+69min）⇒ 不催也不升级
    val (a4, _) = tick(ts + 69 * min, 2, ts + 30 * min)
    assertEquals(a4.collect { case n: BgLifecycleReconciler.Nudge => n }, Nil)
    assertEquals(a4.collect { case e: BgLifecycleReconciler.Escalate => e }, Nil)
  }

  test("D5 催注穷尽：k=3 次后末催再过一个封顶窗（40min）仍无进展 ⇒ Escalate（退避穷尽腿）") {
    val ts = 11 * min
    val lastNudge = ts + 70 * min
    def tick(now: Long) =
      scan1(
        List(view("j-d5", 0L, now, health = BgHealth.Suspect)),
        Map("j-d5" -> BgLifecycleReconciler.Observation(
          lastActivityMs = 0L, suspectSinceMs = ts, nudges = 3, lastNudgeAtMs = lastNudge
        )),
        now
      )
    val (before, _) = tick(lastNudge + 40 * min - 1)
    assertEquals(before.collect { case e: BgLifecycleReconciler.Escalate => e }, Nil, "末催后观察窗未满 ⇒ 不升级")
    val (after, next) = tick(lastNudge + 40 * min)
    val esc = after.collect { case e: BgLifecycleReconciler.Escalate => e }
    assertEquals(esc.map(_.jobId), List("j-d5"))
    assertEquals(next("j-d5").nudges, 0, "升级后停滞计数清零（终态轨到进展/注销为止）")
  }

  test("D6 429 风暴抑制：到点不催、计数不进位（风暴过后到点照催），BadgeSync 恒发徽标照刷") {
    val ts = 11 * min
    val t = view("j-d6", 0L, ts + 10 * min, health = BgHealth.Suspect)
    val obs = Map("j-d6" -> BgLifecycleReconciler.Observation(
      lastActivityMs = 0L, suspectSinceMs = ts, nudges = 0, lastNudgeAtMs = 0L
    ))
    val (actions, next) = scan1(List(t), obs, ts + 10 * min, storm = true)
    assertEquals(actions.collect { case n: BgLifecycleReconciler.Nudge => n }, Nil, "风暴期 Nudge 被抑制")
    assertEquals(next("j-d6").nudges, 0, "抑制不进位——风暴过后同窗口照催")
    assertEquals(next("j-d6").lastNudgeAtMs, 0L)
    assert(actions.contains(BgLifecycleReconciler.BadgeSync), "徽标对账不抑制")
  }

  // ── 决策表：escalated 轨（×10 腿 + 幂等 + 进展复位）───────────────────────

  test("D7 ×10 腿：基线>0 且空闲 > 基线×10 ⇒ 直达 Escalate（无需催注穷尽；基线=0 时该腿永不触发）") {
    // 基线 5min ⇒ 升级线 50min；空闲 49min ⇒ 仅 suspect 线（15min）命中 ⇒ MarkSuspect
    val obs49 = Map("j-d7" -> BgLifecycleReconciler.Observation(
      lastLines = 5L, lastActivityMs = 0L, baselineFrozen = true, baselineMs = 5 * min
    ))
    val (a49, _) = scan1(List(view("j-d7", 0L, 49 * min, lines = 5L)), obs49, 49 * min)
    assertEquals(a49.collect { case e: BgLifecycleReconciler.Escalate => e }, Nil)
    assertEquals(a49.collect { case m: BgLifecycleReconciler.MarkSuspect => m.jobId }, List("j-d7"))
    // 空闲 51min ⇒ ×10 腿直达升级
    val obs51 = Map("j-d7" -> BgLifecycleReconciler.Observation(
      lastLines = 5L, lastActivityMs = 0L, baselineFrozen = true, baselineMs = 5 * min
    ))
    val (a51, _) = scan1(List(view("j-d7", 0L, 51 * min, lines = 5L)), obs51, 51 * min)
    assertEquals(a51.collect { case e: BgLifecycleReconciler.Escalate => e.jobId }, List("j-d7"))
    assertEquals(a51.collect { case m: BgLifecycleReconciler.MarkSuspect => m }, Nil)
    // 基线 = 0（零采样）⇒ 升级线 MaxValue：110min 空闲也只走催注穷尽腿
    val obs0 = Map("j-d7" -> BgLifecycleReconciler.Observation(
      lastActivityMs = 0L, suspectSinceMs = min, nudges = 1, lastNudgeAtMs = min
    ))
    val (a0, _) = scan1(List(view("j-d7", 0L, 110 * min, health = BgHealth.Suspect)), obs0, 110 * min)
    assertEquals(a0.collect { case e: BgLifecycleReconciler.Escalate => e }, Nil)
  }

  test("D8 幂等：已 escalated 行 ⇒ 零升级动作（重复升级由行状态挡掉，Escalate 不重复发）") {
    val t = view("j-d8", 0L, 200 * min, health = BgHealth.Escalated)
    val obs = Map("j-d8" -> BgLifecycleReconciler.Observation(
      lastActivityMs = 0L, baselineFrozen = true, baselineMs = 5 * min, nudges = 3, lastNudgeAtMs = 0L
    ))
    val (actions, next) = scan1(List(t), obs, 200 * min)
    assertEquals(actions, List(BgLifecycleReconciler.BadgeSync), "已升级：零健康动作")
    assertEquals(next("j-d8").nudges, 3, "记账原样保留（幂等不漂移）")
  }

  test("D9 进展复位（重排不判死）：suspect 行遇行增 ⇒ ProgressReset + RecordActivity，停滞计数清零") {
    val t = view("j-d9", 0L, 20 * min, lines = 6L, health = BgHealth.Suspect)
    val obs = Map("j-d9" -> BgLifecycleReconciler.Observation(
      lastLines = 5L, lastActivityMs = 5 * min, suspectSinceMs = 11 * min, nudges = 2, lastNudgeAtMs = 15 * min
    ))
    val (actions, next) = scan1(List(t), obs, 20 * min)
    assertEquals(actions.collect { case r: BgLifecycleReconciler.ProgressReset => r.jobId }, List("j-d9"))
    assertEquals(actions.collect { case r: BgLifecycleReconciler.RecordActivity => r.jobId }, List("j-d9"))
    assertEquals(actions.collect { case m: BgLifecycleReconciler.MarkSuspect => m }, Nil)
    assertEquals(actions.collect { case n: BgLifecycleReconciler.Nudge => n }, Nil)
    assertEquals(next("j-d9").suspectSinceMs, 0L)
    assertEquals(next("j-d9").nudges, 0)
    assertEquals(next("j-d9").lastActivityMs, 20 * min)
  }

  // ── 基线采样（自基线，§3.3）───────────────────────────────────────────────

  test("D10 基线冻结：到龄（>K=10min）冻结中位；冻结后判据用基线×N（5min 基线 ⇒ 15min 嫌疑线）") {
    // 窗口内采样 [1m,5m,3m] ⇒ 冻结中位 3min ⇒ 嫌疑线 max(9min,10min)=10min（地板兜住）
    val t1 = view("j-d10", 0L, 11 * min, lines = 3L)
    val obs1 = Map("j-d10" -> BgLifecycleReconciler.Observation(
      lastLines = 3L, lastActivityMs = 11 * min - 4 * min, gapsMs = Vector(1 * min, 5 * min, 3 * min)
    ))
    val (_, n1) = scan1(List(t1), obs1, 11 * min)
    assert(n1("j-d10").baselineFrozen, "到龄冻结")
    assertEquals(n1("j-d10").baselineMs, 3 * min)
    // 基线 5min ⇒ 嫌疑线 15min：空闲 16min ⇒ MarkSuspect；12min ⇒ 不动
    def obsB(idleMin: Long) = Map("j-d10" -> BgLifecycleReconciler.Observation(
      lastLines = 3L, lastActivityMs = idleMin * min, baselineFrozen = true, baselineMs = 5 * min
    ))
    val (a16, _) = scan1(List(view("j-d10", 0L, 16 * min, lines = 3L)), obsB(0L).map { case (k, o) => k -> o.copy(lastActivityMs = 0L) }, 16 * min)
    assertEquals(a16.collect { case m: BgLifecycleReconciler.MarkSuspect => m.jobId }, List("j-d10"))
    val (a12, _) = scan1(List(view("j-d10", 0L, 12 * min, lines = 3L)), Map("j-d10" -> BgLifecycleReconciler.Observation(
      lastLines = 3L, lastActivityMs = 0L, baselineFrozen = true, baselineMs = 5 * min
    )), 12 * min)
    assertEquals(a12, List(BgLifecycleReconciler.BadgeSync), "12min < 15min 嫌疑线 ⇒ 不动")
  }

  // ── 载荷文案 ──────────────────────────────────────────────────────────────

  test("P1 催注/升级载荷：nudge 带 jobId 与 attempt 序号；升级载荷含 ESCALATION 与零杀声明") {
    val nudge = BgLifecycleReconciler.nudgePayload("job-x", "build it", 2)
    assert(nudge.contains("job-x") && nudge.contains("#2"), "nudge 载荷带身份与催注序号")
    val esc = BgLifecycleReconciler.escalationPayload("job-x", "sid-x", "build it", 65 * min)
    assert(esc.contains("ESCALATION") && esc.contains("job-x"))
    assert(esc.toLowerCase.contains("nothing was killed"), "升级载荷明示零杀（红线话术）")
  }

  // ── 零红线守门 ────────────────────────────────────────────────────────────

  test("R1 零超时红线：ScanAction 封闭类型无 Kill case（Mirror.SumOf 编译+运行断言）") {
    val m = summon[Mirror.SumOf[BgLifecycleReconciler.ScanAction]]
    val labels = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    assertEquals(
      labels.toSet,
      Set("MarkSuspect", "ProgressReset", "RecordActivity", "Nudge", "Escalate", "BadgeSync"),
      "ScanAction case 集钉死——新增动作必须过本断言（Kill 永无立足位）"
    )
    assert(!labels.exists(_.toLowerCase.contains("kill")), "case 名禁含 kill 语义")
  }

  test("R2 零杀伤语义扫描：reconciler 源码无进程/会话杀伤 token（killSession/destroy/reclaim 等）") {
    val src = os.read(os.pwd / "src/main/scala/nebflow/core/processor/BgLifecycleReconciler.scala")
    val banned = List("killSession", "KillProcess", "destroy", "reclaimSession", "cancelNodes", "ProcessHandle", ".destroy()", "waitFor")
    banned.foreach { token =>
      assert(!src.contains(token), s"red-line token '$token' must not appear in BgLifecycleReconciler source")
    }
  }

  // ── 执行面记账件（IO，临时 dataRoot 隔离）────────────────────────────────

  private var prevRoot: os.Path = uninitialized
  private var tmp: os.Path = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.temp.dir()
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(tmp)

  test("E1 升级审计落盘：recordEscalation ⇒ kind=escalation/consumed=true（绝不经补投腿再投递）+ allEscalations 重建读面") {
    BgTaskLedger.recordEscalation("sid-e1", "job-e1", "long build", "ESCALATION payload body").unsafeRunSync()
    val rows = BgTaskLedger.allEscalations.unsafeRunSync()
    assertEquals(rows.map(_._1), List("sid-e1"))
    val e = rows.head._2
    assertEquals(e.kind, "escalation")
    assertEquals(e.eventType, "escalated")
    assert(e.consumed, "落笔即真：升级行经 consumed=true 豁免完成补投腿")
    assertEquals(e.payload, "ESCALATION payload body")
    assertEquals(e.payloadHead, "ESCALATION payload body".take(BgTaskLedger.PayloadHeadLen))
    // pending 面零泄漏（升级行不进补投集合）
    assertEquals(BgTaskLedger.pending("sid-e1").unsafeRunSync(), Nil)
    // 同 jobId 重复升级原位替换（recordedAt 刷新、无重复行）
    Thread.sleep(2)
    BgTaskLedger.recordEscalation("sid-e1", "job-e1", "long build", "v2").unsafeRunSync()
    val rows2 = BgTaskLedger.allEscalations.unsafeRunSync()
    assertEquals(rows2.size, 1)
    assertEquals(rows2.head._2.payload, "v2")
  }

  test("E2 unread 桩激活：markEscalationsUnread/Read ⇒ healthCounts unread 格随记账变化；注销清账") {
    val job = "job-e2"
    val io =
      BgTaskRegistry.register(job, "sid-e2", "spec task", "local") *>
        BgTaskRegistry.updateHealth(job, BgHealth.Escalated, nowMs = 1000L) *>
        BgTaskRegistry.healthCounts.map(c => assertEquals(c, BgTaskRegistry.BgHealthCounts(0, 0, 1))) *>
        BgTaskRegistry.markEscalationsUnread(Set(job)) *>
        BgTaskRegistry.healthCounts.map(c => assertEquals(c, BgTaskRegistry.BgHealthCounts(1, 0, 1))) *>
        BgTaskRegistry.markEscalationsRead(Set(job)) *>
        BgTaskRegistry.healthCounts.map(c => assertEquals(c, BgTaskRegistry.BgHealthCounts(0, 0, 1))) *>
        // 进展降级消解 + 注销清账
        BgTaskRegistry.markEscalationsUnread(Set(job)) *>
        BgTaskRegistry.updateHealth(job, BgHealth.Healthy) *>
        BgTaskRegistry.healthCounts.map(c => assertEquals(c, BgTaskRegistry.BgHealthCounts(0, 0, 0))) *>
        BgTaskRegistry.markEscalationsUnread(Set(job)) *>
        BgTaskRegistry.unregister(job) *>
        BgTaskRegistry.unreadEscalationSnapshot.map(s => assertEquals(s, Set.empty))
    io.unsafeRunSync()
  }

  test("E3 recordActivity 写缝：仅更新鲜戳抬升；行不存在 ⇒ false") {
    val job = "job-e3"
    val io =
      BgTaskRegistry.register(job, "sid-e3", "spec task", "local") *>
        BgTaskRegistry.recordActivity(job, 5000L).map(ok => assert(ok)) *>
        BgTaskRegistry.find(job).map(t => assertEquals(t.get.lastActivityMs, 5000L)) *>
        BgTaskRegistry.recordActivity(job, 3000L).map(ok => assert(!ok, "陈旧戳不抬升")) *>
        BgTaskRegistry.find(job).map(t => assertEquals(t.get.lastActivityMs, 5000L)) *>
        BgTaskRegistry.recordActivity("no-such", 9000L).map(ok => assert(!ok)) *>
        BgTaskRegistry.unregister(job)
    io.unsafeRunSync()
  }

end BgLifecycleReconcilerSpec
