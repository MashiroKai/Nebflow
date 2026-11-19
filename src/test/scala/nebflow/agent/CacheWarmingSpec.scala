package nebflow.agent

import munit.FunSuite
import nebflow.agent.CacheWarming.*

/**
 * 保温安全与经济性契约（cache-warmup 批 2026-11-06，作者补充令 §四）。
 * 全程虚拟时钟（nowMs 注入），零真实 sleep；决策面 shadow-only。
 *
 * 契约：
 *   C-WARM-1 角色×状态硬闸表：终态/冻结/blocked/owner-gone ⇒ 零保温零补发；
 *      活跃工具往返（真实请求已续用）⇒ 零额外保温；主会话/kernel 纯空闲 ⇒
 *      零保温；新建短叶子 ⇒ 零保温；
 *   C-WARM-2 确定性经济夹具：预计节省 ≤ 全成本×(1+安全余量) ⇒ 不发；
 *      未知价格/TTL ⇒ shadow；预算/次数上限触顶 ⇒ 停止；累计保温 ≥ 单次
 *      冷读 ⇒ 拒绝；
 *   C-WARM-3 结构性 shadow：本批 evaluate 对任何输入都不产 WarmSend（无发送器、
 *      不创建 agent turn、不注入消息、不执行 tool_calls、不落历史/UI/任务状态）；
 *   C-WARM-4 构造器能力闸：不支持安全小输出上限 / thinking 预算不兼容 ⇒ 跳过；
 *      计划保留影响前缀的配置（tools/system/thinking 原样）；
 *   C-WARM-5 账本分列：保温决策只进保温账本，与业务归因账本（CacheAttribution
 *      business）互不污染。
 *
 * 变异红线（隔离副本执行，见批报告 §变异）：
 *   M1 去掉收益门（negative-expected-value 判据）⇒ 本规格 ⑩ 负收益行红；
 *   M2 去掉终态硬闸 ⇒ 本规格 ⑨ 终态行红。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.agent.CacheWarmingSpec"`
 * 归属 CI：既有 `sbt test` 家族。
 */
class CacheWarmingSpec extends FunSuite:

  // 账本为每测试独立实例（进程内存活；重启清零语义 = 无跨测试状态可漏）

  // ── 确定性经济夹具（价格/TTL 全部显式注入，不猜）────────────────────

  /** 已知价格域：in=$3/M，read=$0.1/M，write=$1/M，out=$15/M，TTL=300s。
    * tokens=200K ⇒ 冷读可避免 = 0.2×(3−0.1) = $0.58；保温全成本 = 0.2×1 + 64/1M×15 ≈ $0.20096。 */
  private val knownPositive = Economics(
    inputPerMTok = Some(BigDecimal("3")),
    cacheReadPerMTok = Some(BigDecimal("0.1")),
    cacheWritePerMTok = Some(BigDecimal("1")),
    outputPerMTok = Some(BigDecimal("15")),
    ttlSec = Some(300),
    safetyMargin = 0.5,
    maxWarmsPerSession = 8
  )
  private val unknownAll = Economics(maxWarmsPerSession = 8) // 价格与 TTL 全未知
  private val unknownTtl = knownPositive.copy(ttlSec = None)

  private def cand(
    role: Role = ParentAwaitingChild,
    state: SessionState = WaitingChildReturn,
    tokens: Long = 200_000
  ): Candidate =
    Candidate(
      sessionId = "warm-spec-session",
      role = role,
      state = state,
      contextTokens = tokens,
      coldBeforeNextBusinessProbability = 0.9,
      expectedNextBusinessMs = None,
      idleMs = 120_000
    )

  // ── C-WARM-1：角色×状态硬闸表（补充令 §四.9）────────────────────────

  test("⑨ role×state gate table: terminal/frozen/real-traffic/idle-main/fresh-leaf all forbid warming"):
    val cases: List[(Role, SessionState, String)] = List(
      (MainOrKernel, IdleUser, "idle-main-default-zero"),
      (MainOrKernel, ActiveToolRoundTrip, "real-traffic-renews"),
      (Dispatcher, Terminal, "terminal-state-zero-reissue"),
      (Dispatcher, BlockedOrFrozen, "blocked-frozen-owner-gone"),
      (ParentAwaitingChild, Terminal, "terminal-state-zero-reissue"),
      (ParentAwaitingChild, IdleUser, "idle-no-pending-work"),
      (SubagentOrNode, ActiveToolRoundTrip, "real-traffic-renews"),
      (SubagentOrNode, FreshShortLeaf, "fresh-short-leaf-no-warm"),
      (SubagentOrNode, BlockedOrFrozen, "blocked-frozen-owner-gone"),
      (SubagentOrNode, Terminal, "terminal-state-zero-reissue")
    )
    cases.foreach { case (role, state, expectedReason) =>
      val d = evaluate(cand(role, state), knownPositive, new Ledger(), nowMs = 1_000L)
      assertEquals(d.getClass.getSimpleName, "NoWarm", s"role=$role state=$state must never warm")
      assertEquals(d.asInstanceOf[NoWarm].reason, expectedReason, s"role=$role state=$state")
    }

  test("⑨ gating candidates (waiting-child / long-wait-resume) pass the hard gate and reach the economics gate"):
    List(WaitingChildReturn, LongWaitExpectedResume).foreach { state =>
      List(Dispatcher, ParentAwaitingChild, SubagentOrNode).foreach { role =>
        assertEquals(hardGate(cand(role, state)), None, s"role=$role state=$state must reach the economics gate")
      }
    }

  // ── C-WARM-2：确定性经济判据（补充令 §四.10）────────────────────────

  test("⑩ known prices + positive value → shadow with explicit cost/saving (never a real send)"):
    val d = evaluate(cand(), knownPositive, new Ledger(), nowMs = 1_000L)
    d match
      case WarmShadow(reason, cost, saving) =>
        assertEquals(reason, "send-capability-absent-shadow-only")
        assert(cost == BigDecimal("0.20096"), s"expected $$0.20096, got $cost")
        assert(saving == BigDecimal("0.58"), s"expected $$0.58 avoidable, got $saving")
      case other => fail(s"expected WarmShadow, got $other")

  test("⑩ negative expected value (saving ≤ cost×(1+margin)) → no warming"):
    // 冷读差价压到 0.05：saving=0.2×(3−2.95)=0.01 << cost×1.5
    val bad = knownPositive.copy(cacheReadPerMTok = Some(BigDecimal("2.95")))
    val d   = evaluate(cand(), bad, new Ledger(), nowMs = 1_000L)
    assertEquals(d, NoWarm("negative-expected-value"))

  test("⑩ unknown price or unknown TTL → shadow (never claims savings, never sends)"):
    List(unknownAll, unknownTtl).foreach { econ =>
      val d = evaluate(cand(), econ, new Ledger(), nowMs = 1_000L)
      assertEquals(d.getClass.getSimpleName, "WarmShadow", s"econ=$econ must shadow, not send")
      assertEquals(d.reason, "unknown-price-or-ttl")
    }

  test("⑩ session warm-count cap → stop"):
    val ledger = new Ledger()
    ledger.recordWarm("warm-spec-session", atMs = 1_000L, costUsd = BigDecimal("0.20096"))
    val econ = knownPositive.copy(maxWarmsPerSession = 1)
    assertEquals(evaluate(cand(), econ, ledger, nowMs = 2_000L), NoWarm("session-warm-count-cap"))

  test("⑩ maxWarmsPerSession = 0 is explicit warming-disabled → no warming"):
    assertEquals(
      evaluate(cand(), knownPositive.copy(maxWarmsPerSession = 0), new Ledger(), nowMs = 1_000L),
      NoWarm("warming-disabled-zero-cap")
    )

  test("⑩ budget exhausted → stop"):
    val ledger = new Ledger()
    ledger.recordWarm("warm-spec-session", atMs = 1_000L, costUsd = BigDecimal("0.5"))
    val econ = knownPositive.copy(budgetUsd = Some(BigDecimal("0.5")))
    assertEquals(evaluate(cand(), econ, ledger, nowMs = 2_000L), NoWarm("budget-exhausted"))

  test("⑩ cumulative warming costlier than one cold read → reject"):
    val ledger = new Ledger()
    val econ   = knownPositive.copy(maxWarmsPerSession = 8)
    // 两次正判据后的第 3 次：cost×(2+1) = 0.60288 ≥ 0.58 ⇒ 拒绝
    ledger.recordWarm("warm-spec-session", atMs = 1_000L, costUsd = BigDecimal("0.20096"))
    assertEquals(evaluate(cand(), econ, ledger, nowMs = 2_000L).getClass.getSimpleName, "WarmShadow",
      "2nd warm still cumulative-cheap: 0.40192 < 0.58")
    ledger.recordWarm("warm-spec-session", atMs = 2_000L, costUsd = BigDecimal("0.20096"))
    assertEquals(evaluate(cand(), econ, ledger, nowMs = 3_000L), NoWarm("cumulative-warming-costlier-than-cold"))

  // ── C-WARM-3：结构性 shadow ──────────────────────────────────────────

  test("⑨ evaluate NEVER returns WarmSend for any role×state×economics (no sender exists in this batch)"):
    val roles: List[Role]           = List(MainOrKernel, Dispatcher, ParentAwaitingChild, SubagentOrNode)
    val states: List[SessionState]  = List(ActiveToolRoundTrip, IdleUser, WaitingChildReturn, BlockedOrFrozen, Terminal, FreshShortLeaf, LongWaitExpectedResume)
    val econs: List[Economics]      = List(knownPositive, unknownAll, unknownTtl, knownPositive.copy(safetyMargin = 0))
    roles.foreach { role =>
      states.foreach { state =>
        econs.foreach { econ =>
          val d = evaluate(cand(role, state), econ, new Ledger(), nowMs = 42L)
          assert(d.isInstanceOf[NoWarm] || d.isInstanceOf[WarmShadow],
            s"structural shadow violated: role=$role state=$state econ=$econ produced ${d.getClass.getSimpleName}")
        }
      }
    }

  // ── C-WARM-4：构造器能力闸 ───────────────────────────────────────────

  test("⑩ builder skips when no safe small output cap / incompatible thinking budget; plan preserves prefix config"):
    assertEquals(
      buildWarmingPlan("s", "Zai", "glm", "tools-fp", "sys-fp", thinkingBudgetTokens = 32000, supportsSmallOutputCap = false),
      Left("no-safe-small-output-cap-skip")
    )
    assertEquals(
      buildWarmingPlan("s", "Zai", "glm", "tools-fp", "sys-fp", thinkingBudgetTokens = 512, supportsSmallOutputCap = true),
      Left("thinking-budget-incompatible-skip")
    )
    val plan = buildWarmingPlan("s", "Zai", "glm", "tools-fp", "sys-fp", thinkingBudgetTokens = 32000, supportsSmallOutputCap = true)
    assertEquals(plan, Right(WarmingPlan("s", "Zai", "glm", "tools-fp", "sys-fp", 32000)),
      "plan must preserve tools/system/thinking verbatim (no tool_choice:none, no thinking-off)")

  // ── C-WARM-5：账本分列 ───────────────────────────────────────────────

  test("⑨ warming ledger is separate from business attribution (business totals untouched)"):
    // 序无关口径（合并后批跑序含 CacheAttributionSpec 先行 ⇒ business 起点非零）：
    // 差量断言——保温动作前后业务账本必须零增量。
    val before = nebflow.llm.CacheAttribution.snapshot
    val ledger = new Ledger()
    evaluate(cand(), knownPositive, ledger, nowMs = 1_000L)
    ledger.recordWarm("warm-spec-session", atMs = 1_000L, costUsd = BigDecimal("0.20096"))
    assertEquals(ledger.warmCount("warm-spec-session"), 1)
    assertEquals(ledger.entryCount, 1)
    assertEquals(ledger.spentUsd, BigDecimal("0.20096"))
    // 业务归因账本（CacheAttribution.business）零污染——保温决策不产生 usage 记录
    val after = nebflow.llm.CacheAttribution.snapshot
    assertEquals(after.business.requests, before.business.requests, "warming must not add business usage records")
    assertEquals(after.business.inputTokens, before.business.inputTokens, "warming must not add business input tokens")
    assertEquals(after.warmup.requests, before.warmup.requests, "evaluate-only flow adds no warmup usage either")

end CacheWarmingSpec
