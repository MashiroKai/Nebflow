package nebflow.core.delegate

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * DelegateStandbyCare（状态矩阵批看护 · B1 机械退避档）纯函数锚 + 待命槽写面
 * round-trip（案A 批 2026-10-31 · 作者 11:57 令「用 system reminder 提醒进入
 * standby，不进入就退避提醒」；状态矩阵批 2026-11-01 扩 IdleNudge 型——作者令
 * 「complete × idle（未申报）⇒ system reminder 提醒其调整状态，不终判」）：
 *
 *  - delayFor/totalAttempts：短档 10s→30s→60s 各一次后转 10min 长档（审计草案
 *    B1 档），长档 N 次穷尽；
 *  - nudgePayload：`<system-reminder>` 形态（作者令的注入形态）、**双语在册**
 *    （验收②：en 主文 + zh 摘要行随载荷）、IdleNudge 型催「立即补报 standby 或
 *    finish」（finish = 唯一可判完成的申报）、Declared 型带申报 detail、
 *    chainsInFlight/emptyTurn 两形态锚、穷尽腿明示「回纯事件驱动、零杀零超时」；
 *  - parkStandby/clearStandby round-trip + 幂等零写 + 查无记录显式 Left。
 */
class DelegateStandbyCareSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-standby-care"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")

  private val short = List(10_000L, 30_000L, 60_000L)

  test("delayFor: short tiers each once, then the long tier flat") {
    assertEquals(DelegateStandbyCare.delayFor(1, short, 600_000L), 10_000L)
    assertEquals(DelegateStandbyCare.delayFor(2, short, 600_000L), 30_000L)
    assertEquals(DelegateStandbyCare.delayFor(3, short, 600_000L), 60_000L)
    assertEquals(DelegateStandbyCare.delayFor(4, short, 600_000L), 600_000L, "long tier from attempt 4 on")
    assertEquals(DelegateStandbyCare.delayFor(9, short, 600_000L), 600_000L)
    // 空短档（prop 覆写成空回退缺省前的极端形态）：任何 attempt 走长档。
    assertEquals(DelegateStandbyCare.delayFor(1, Nil, 600_000L), 600_000L)
  }

  test("totalAttempts: short count + long cap") {
    assertEquals(DelegateStandbyCare.totalAttempts(short, 3), 6)
    assertEquals(DelegateStandbyCare.totalAttempts(short, 0), 3, "longMax=0 ⇒ short tiers only")
    assertEquals(DelegateStandbyCare.totalAttempts(Nil, 3), 3)
  }

  test("nudgePayload: <system-reminder> form; bilingual (en + zh) registered; IdleNudge demands an explicit standby-or-finish declaration; Declared carries the detail; the last reminder names the zero-timeout tail") {
    // 未申报空等腿（矩阵组合 complete×idle）：催「立即补报 standby 或 finish」。
    val idle = DelegateStandbyCare.nudgePayload(DelegateStandbyCare.IdleNudge, 1, "", "10s→30s→60s, then every 10min ×3", lastReminder = false)
    assert(idle.startsWith("<system-reminder>") && idle.trim.endsWith("</system-reminder>"), "the author-mandated injection form")
    assert(idle.contains("delegate_report(category=\"standby\""), "idle leg must demand entering standby explicitly")
    assert(idle.contains("finish"), "idle leg must offer the finish/merge escape (finish = the only judged-complete combination)")
    assert(idle.contains("NO terminal verdict") || idle.contains("did NOT judge"), "the reminder names why it fired (not judged)")
    // 双语在册（验收②）：en 主文 + zh 摘要行随载荷。
    assert(idle.contains("【状态矩阵】"), "the zh summary line must ride the payload (bilingual registry)")
    assert(idle.contains("[delegate-matrix]"), "the en body must ride the payload")
    assert(!idle.contains("LAST reminder"), "non-final reminder must not carry the exhaustion tail")

    // 链在飞形态锚（dispatcher 断供场景——S3/D1 对账）：点名等上游 = standby 用途。
    val idleChains = DelegateStandbyCare.nudgePayload(DelegateStandbyCare.IdleNudge, 1, "", "d", lastReminder = false, chainsInFlight = true)
    assert(idleChains.contains("chains are still in flight"), "the chains-in-flight form names the dispatcher scenario")
    assert(idleChains.contains("等上游"), "the zh line names the upstream-wait case too")

    // 空烧形态锚。
    val idleBurn = DelegateStandbyCare.nudgePayload(DelegateStandbyCare.IdleNudge, 2, "", "d", lastReminder = false, emptyTurn = true)
    assert(idleBurn.contains("empty burn"), "the empty-burn form is named to the reminder")

    // 显式申报腿：带申报 detail。
    val dec = DelegateStandbyCare.nudgePayload(DelegateStandbyCare.Declared, 2, "等作者拍板", "10s→30s→60s", lastReminder = false)
    assert(dec.contains("delegate-standby") && dec.contains("Reminder #2"))
    assert(dec.contains("等作者拍板"), "the declared leg carries the report detail")
    assert(dec.contains("【待命看护】"), "the declared leg is bilingual too")

    val last = DelegateStandbyCare.nudgePayload(DelegateStandbyCare.Declared, 6, "", "d", lastReminder = true)
    assert(last.contains("LAST reminder"), "the exhaustion leg is explicit")
    assert(last.contains("no timeout"), "zero-timeout red line rides the exhaustion leg")
    assert(last.contains("零杀零超时"), "the zh tail rides the exhaustion leg too")
  }

  test("delaysDesc: short chain + long tier wording") {
    assertEquals(DelegateStandbyCare.delaysDesc(short, 600_000L, 3), "10s→30s→60s, then every 10min ×3")
    assertEquals(DelegateStandbyCare.delaysDesc(short, 600_000L, 0), "10s→30s→60s")
  }

  test("standby slot writers: parkStandby/clearStandby round-trip, idempotent no-op, missing record explicit Left") {
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-care-1",
        executor = "nebflow",
        cwd = tempRoot.toString,
        project = None,
        taskId = None,
        parentSessionId = "nebula-root",
        title = "seeded",
        createdAt = 1L
      )
    )
    val parked = DelegateRegistry.parkStandby("delegate-care-1")
    assert(parked.isRight)
    assert(DelegateRegistry.find("delegate-care-1").flatMap(_.standbySince).isDefined)
    // 幂等重停：新时刻（新一轮待命），不抛。
    assert(DelegateRegistry.parkStandby("delegate-care-1").isRight)
    // clearStandby：清停靠槽 + 申报槽。
    DelegateRegistry.updateReport("delegate-care-1", "standby", "等 Mail", "")
    assertEquals(DelegateRegistry.clearStandby("delegate-care-1"), Right(()))
    val cleared = DelegateRegistry.find("delegate-care-1")
    assertEquals(cleared.flatMap(_.standbySince), None)
    assertEquals(cleared.flatMap(_.reportStatus), None)
    assertEquals(cleared.flatMap(_.reportDetail), None)
    // 幂等零写：非待命记录再清 = Right 且不炸。
    assertEquals(DelegateRegistry.clearStandby("delegate-care-1"), Right(()))
    // 查无记录 = 显式 Left（只对真实 spawn 生效）。
    assert(DelegateRegistry.parkStandby("delegate-care-ghost").isLeft)
    assert(DelegateRegistry.clearStandby("delegate-care-ghost").isLeft)
  }

end DelegateStandbyCareSpec
