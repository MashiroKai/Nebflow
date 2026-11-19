package nebflow.core.delegate

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * DelegateStandbyCare（看护纯函数锚 + 待命槽写面 round-trip）。
 *
 * 案A 批 2026-10-31 · 状态矩阵批 2026-11-01；**通知矩阵批 2026-11-03 · 作者 18:14
 * 裁定**后本对象只剩**一条提醒腿**（矩阵组合 complete×idle 未申报空等）：申报待命腿
 * （complete×standby）零注入零唤醒，其提醒文案随本批退役——本 spec 相应删除
 * Declared 载荷断言（不留假绿：没有提醒腿就没有提醒文案）。
 *
 *  - delayFor/totalAttempts：短档 10s→30s→60s 各一次后转 10min 长档，长档 N 次穷尽；
 *  - nudgePayload：`<system-reminder>` 形态、**双语在册**（en 主文 + zh 摘要行）、
 *    催「立即补报 standby 或 finish」（finish = 唯一可判完成的申报）、
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

  test(
    "nudgePayload: <system-reminder> form; bilingual (en + zh) registered; demands an explicit standby-or-finish declaration; the last reminder names the zero-timeout tail"
  ) {
    val idle = DelegateStandbyCare.nudgePayload(lastReminder = false)
    assert(
      idle.startsWith("<system-reminder>") && idle.trim.endsWith("</system-reminder>"),
      "the author-mandated injection form"
    )
    assert(
      idle.contains("STATE YOUR POSITION NOW"),
      "the idle leg must demand entering standby explicitly"
    )
    assert(
      idle.contains("write your final report as normal text output"),
      "the idle leg must offer the escape (the end state = the final report as normal text)"
    )
    assert(
      idle.contains("NO terminal verdict") || idle.contains("did NOT judge"),
      "the reminder names why it fired (not judged)"
    )
    // 双语在册：en 主文 + zh 摘要行随载荷。
    assert(idle.contains("【状态矩阵】"), "the zh summary line must ride the payload (bilingual registry)")
    assert(idle.contains("[delegate-matrix]"), "the en body must ride the payload")
    assert(!idle.contains("LAST reminder"), "non-final reminder must not carry the exhaustion tail")

    // 链在飞形态锚（dispatcher 断供场景）：点名等上游 = standby 用途。
    val idleChains = DelegateStandbyCare.nudgePayload(lastReminder = false, chainsInFlight = true)
    assert(idleChains.contains("chains are still in flight"), "the chains-in-flight form names the dispatcher scenario")
    assert(idleChains.contains("等上游"), "the zh line names the upstream-wait case too")

    // 空烧形态锚。
    val idleBurn = DelegateStandbyCare.nudgePayload(lastReminder = false, emptyTurn = true)
    assert(idleBurn.contains("empty burn"), "the empty-burn form is named to the reminder")

    // 穷尽腿：明示回纯事件驱动、零杀零超时。
    val last = DelegateStandbyCare.nudgePayload(lastReminder = true)
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
