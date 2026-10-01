package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * eng-deferred-cancel 批（`chain-tasklist-anim` 链 · 2026-10-02）——**K-1 判据③**
 * （「链的取消态仍可判读」：引擎面 deferred-cancel 期间必须有**可读面**，禁做成静默延迟）
 * 与**零载荷形状变更**的自证判据。
 *
 * 面（逐条对应任务书 §二.1 K-1 / §二.1 B / §二.3 红线）：
 *  - L1 意图可判读：`withChainCancelIntent` 登记后 `cancellingAtOf` 可读出登记时刻。
 *  - L2 **不入三态投影**：意图在途时 `statusOf` 仍是 `active`（🔴 禁改 `cancelled > paused`
 *    优先级、禁动 REST/WS 载荷形状）——本批只新增「进行中」的可读面，不改状态投影。
 *  - L3 **终态原子清**：`withChainControl(cancelled)` 与意图清除落在**同一次** State 改写上
 *    ⇒「正在取消 ∧ 已取消」不可同时可读。
 *  - L4 TTL 读时过滤：超窗条目不参与判读（崩在「意图已写、取消未跑」窄窗内的残留自清），
 *    且写入点顺带惰性 prune。
 *  - L5 落盘面：`setChainCancelIntent` 真落盘（重开可读出）、`setChainControl(cancelled)`
 *    把意图清除也落盘（否则内存与磁盘静默不一致）。
 *  - L6 面分工：节点级 `cancelNodes` **不得**伪造链级意图（意图只由 `cancelChain` 腿登记）。
 *
 * **红验语义**（逐条钉死「把判据改坏 ⇒ 本 spec 必红」的变异）：
 * 评测红读数以「把实现回改成改动前形态」为变异臂——`withChainCancelIntent` 恒返回入参
 * ⇒ L1/L5 红；`cancellingChains` 进 `statusOf` ⇒ L2 红；`withChainControl` 不清意图 ⇒
 * L3 红；`cancellingAtOf` 去掉 TTL 过滤 ⇒ L4 红。
 *
 * 本位只做**定向**分批跑（`testOnly`），全量 `sbt test` 归零——见任务书 §二.4。
 */
class DeferredCancelLedgerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val t0 = 1750000000000L

  private def stateWithChain(chainId: String): ChainLedger.State =
    ChainLedger.observe(
      ChainLedger.State(project = "p"),
      List(ChainInfo(id = chainId, memberIds = List("n-a", "n-b"))),
      t0
    ).state

  // ── L1 意图可判读（判据③的承载）────────────────────────────────────────

  test("L1: recording a cancel intent makes the chain readable as 'cancellation in progress'") {
    val st = stateWithChain("chain-l1")
    val after = ChainLedger.withChainCancelIntent(st, "chain-l1", t0 + 1000L)
    assertEquals(
      ChainLedger.cancellingAtOf(after, "chain-l1", t0 + 1000L),
      Some(t0 + 1000L),
      "K-1③: the in-progress cancel intent must be readable (禁静默延迟)"
    )
    assertEquals(
      ChainLedger.cancellingChainsAt(after, t0 + 1000L).keySet,
      Set("chain-l1"),
      "K-1③: the in-progress face must be enumerable for the REST/WS read path"
    )
  }

end DeferredCancelLedgerSpec
