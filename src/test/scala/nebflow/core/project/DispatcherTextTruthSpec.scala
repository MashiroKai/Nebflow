package nebflow.core.project

import munit.CatsEffectSuite
import nebflow.agent.AgentCore

/**
 * **D4 机械验收**：三处互斥说辞已与真实工具集对齐（RCA
 * `docs/20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md`
 * §3.1「三方文本互相打架」/ §7-P2）。
 *
 * 背景（RCA §3.1 逐字）：同一件「分发器怎么把话送到 root」的事，仓里有三句互斥说法——
 *   - `ProjectActor.scala`（R7-b 注释）「分发器必须自己 `Mail(address="Nebula", …)`」；
 *   - `PromptCatalog.scala`（分发器提示词）「You hold no messaging tool」；
 *   - `DispatchNotify.scala`（通知文本）「自动投递 Nebula」。
 * 真实行为由 [[AgentCore.DispatcherFixedTools]]（无 `Mail`，2026-10-02 agentflow 批摘除）
 * 裁决 ⇒ 前两句主张与第三句承诺**都不成立**，模型照第三句做（写终文即当作已上报），
 * 那段裁定请求**没有任何出口**。
 *
 * P2 已把三处改成与真实工具集一致的说法。本 spec 是那批改动的**机械判据**：读源文件
 * 子串 / 计数，不跑长链路，防「文本再次漂回互斥态」。
 *
 * 判据面（每条都在改前必红，读数见批报告）：
 *   - **T1** 工具集是**裁决者**：`DispatcherFixedTools` 恰 9 件且**不含** `Mail`。
 *   - **T2** `DispatchNotify.scala` 的「自动投递 Nebula」零残留，且改后真实措辞
 *     （无消息工具 / `AskUserQuestion` / `TaskBoard`）在位。
 *   - **T3** `PromptCatalog.scala` 的分发器提示词含两条新出口面 + 「链级回执由引擎送达」。
 *   - **T4** `ProjectActor.scala` 的「分发器必须 `Mail(address="Nebula")`」零残留。
 *   - **T5** 三处说辞同向（跨文件一致性；不是新语义，是那次对齐的聚合判据）。
 *
 * 纪律：**只断言能稳定命中的整句子串**（不断言整段、不锚行号）——文本块会随批合法
 * 重排，锚整段会让本 spec 变成「文本冻结尾行号」的假判据。
 */
class DispatcherTextTruthSpec extends CatsEffectSuite:

  private val repoRoot: os.Path = os.pwd

  private def readSource(relPath: String): String =
    os.read(repoRoot / os.RelPath(relPath))

  /**
   * 子串**字面**出现次数（`String.contains` 不计数，计数点 = 文本态漂移的可量化面）。
   *
   * 刻意用 `indexOf` 而非 `"…".r.findAllMatchIn`：本 spec 的针头里含 `**`（Markdown 粗体）
   * 与 `(` / `)`（`Mail(address="Nebula"` 调用式）——直接当正则编译会抛
   * `PatternSyntaxException: Dangling meta character`，而给它套 `Pattern.quote` 又会把
   * 「计数」变成「计数一个引号串」，两者都不是我们想量的事实。字面扫描最直白。
   */
  private def countOf(hay: String, needle: String): Int =
    require(needle.nonEmpty, "needle must not be empty (would loop forever)")
    var idx = hay.indexOf(needle)
    var n = 0
    while idx >= 0 do
      n += 1
      idx = hay.indexOf(needle, idx + needle.length)
    n

  private val dispatchNotifyPath = "src/main/scala/nebflow/core/project/DispatchNotify.scala"
  private val promptCatalogPath = "src/main/scala/nebflow/prompts/PromptCatalog.scala"
  private val projectActorPath = "src/main/scala/nebflow/core/project/ProjectActor.scala"

  // ── T1：裁决者 = 静态工具集 ────────────────────────────────────────────
  test("T1 DispatcherFixedTools is the arbiter: exactly 9 tools and no Mail") {
    assert(
      AgentCore.DispatcherFixedTools.size == 9,
      s"dispatcher fixed tool set must stay 9 (RCA §3.1 + AgentCore 注释「件数 9 → 9」), " +
        s"got ${AgentCore.DispatcherFixedTools.toList.sorted.mkString(", ")}"
    )
    assert(
      !AgentCore.DispatcherFixedTools.contains("Mail"),
      "dispatcher has NO Mail tool (agentflow 批 2026-10-02 −Mail +AskUserQuestion) — " +
        s"any text claiming it must Mail the root is structurally false, got ${AgentCore.DispatcherFixedTools}"
    )
    // 正面对齐：文本里点出的两条真实出口必须在集内，否则 P2 的改写就成了「指向不存在的工具」。
    assert(
      AgentCore.DispatcherFixedTools.contains("AskUserQuestion"),
      "AskUserQuestion must be the interactive escalation channel named in the notify text"
    )
    assert(
      AgentCore.DispatcherFixedTools.contains("TaskBoard"),
      "TaskBoard must be the durable-note channel named in the notify text"
    )
  }

  // ── T2：DispatchNotify 的通知文本 ──────────────────────────────────────
  test("T2 DispatchNotify carries no 'auto-delivered to Nebula' promise and names the real channels") {
    val src = readSource(dispatchNotifyPath)
    assertEquals(
      countOf(src, "自动投递 Nebula"),
      0,
      "DispatchNotify must carry ZERO '自动投递 Nebula' promises — the bridge stopped auto-delivering " +
        "dispatcher output at R7-b and the dispatcher has no Mail tool, so the promise was never true " +
        "(改前 5 处，全部改掉)"
    )
    assertEquals(
      countOf(src, "分发器**没有消息工具**"),
      5,
      "all five notify variants (failed / suppressed-failed / cancelled-user / cancelled / " +
        "failed-on-cancelled-upstream) must state the true fact '分发器**没有消息工具**' — the count is the " +
        "drift guard over the five action-list tails"
    )
    assertEquals(
      countOf(src, "`AskUserQuestion`"),
      5,
      "each of the five notify variants must name AskUserQuestion as the real escalation channel"
    )
    assertEquals(
      countOf(src, "`TaskBoard`"),
      5,
      "each of the five notify variants must name TaskBoard as the real durable-note channel"
    )
    // 「root 不会自动收到」这句是那次误判的直接纠正（RCA §3：分发器照旧句做了、话没出去）。
    assert(
      src.contains("root **不会自动收到**"),
      "the notify text must say outright that the root does NOT receive the final text automatically"
    )
    // 反向：不得再出现 Mail 到 Nebula 的调用式（注释里说「没有 Mail」是允许的，调用式不是）。
    assertEquals(
      countOf(src, "Mail(address=\"Nebula\""),
      0,
      "DispatchNotify must never instruct a Mail(address=\"Nebula\", ...) call — no such tool exists"
    )
  }

  // ── T3：PromptCatalog 的分发器提示词 ───────────────────────────────────
  test("T3 dispatcher prompt names TaskBoard + AskUserQuestion and engine-delivered chain receipts") {
    val src = readSource(promptCatalogPath)
    assert(
      src.contains("`NodeList` / `NodeEdit` / `NodeCancel` / `AskUserQuestion` / `TaskBoard` plus"),
      "the prompt's fixed-tool list must name TaskBoard (it is the 9th tool of DispatcherFixedTools and was missing)"
    )
    assert(
      src.contains("does **not** receive it automatically"),
      "the prompt must state that the root does not receive the final text automatically"
    )
    assert(
      src.contains("Chain-level terminal receipts"),
      "the prompt must name the engine-delivered chain-level terminal receipt (P1 landing surface)"
    )
    assert(
      src.contains("are engine-delivered, not written by you"),
      "the receipt must be attributed to the engine, not to the dispatcher's own writing — " +
        "this is exactly the claim that contradicts 'you must Mail(address=\"Nebula\")'"
    )
    assert(
      src.contains("ask with `AskUserQuestion`"),
      "the prompt must route 'I need a decision' to AskUserQuestion"
    )
    assertEquals(
      countOf(src, "Your final text is the dispatch summary the root reads"),
      0,
      "the old claim 'your final text is the dispatch summary the root reads' is false since R7-b — zero residue"
    )
  }

  // ── T4：ProjectActor 的 R7-b 注释 ──────────────────────────────────────
  test("T4 ProjectActor no longer claims the dispatcher must Mail(address=\"Nebula\")") {
    val src = readSource(projectActorPath)
    assertEquals(
      countOf(src, "Mail(address=\"Nebula\""),
      0,
      "ProjectActor must carry ZERO 'Mail(address=\"Nebula\", ...)' claims — that instruction became " +
        "unexecutable when Mail was removed from DispatcherFixedTools (改前 3 处)"
    )
    assert(
      src.contains("DispatcherFixedTools"),
      "the R7-b note must point the reader at the arbiter (AgentCore.DispatcherFixedTools) " +
        "instead of an unexecutable Mail instruction"
    )
    assert(
      src.contains("分发器**没有** Mail 工具"),
      "the bridge note must state that the dispatcher has no Mail tool"
    )
  }

  // ── T5：三处说辞同向（跨文件一致性聚合判据）────────────────────────────
  test("T5 the three former contradicting texts now say the same thing") {
    val notify = readSource(dispatchNotifyPath)
    val prompt = readSource(promptCatalogPath)
    val actor = readSource(projectActorPath)
    // 三处都必须承认「分发器没有消息工具」这一条事实（RCA §3.1 的最小对齐面）。
    assert(notify.contains("没有消息工具"), "DispatchNotify must admit the dispatcher has no messaging tool")
    assert(prompt.contains("you hold no messaging tool"), "the prompt must admit the same")
    assert(
      actor.contains("分发器**没有** Mail 工具") || actor.contains("分发器固定工具集里没有 Mail"),
      "ProjectActor must admit the same"
    )
    // 三处都必须指向「引擎级回执」作为真正的链级终态出口。
    assert(notify.contains("回执由**引擎**送达"), "DispatchNotify must attribute the chain receipt to the engine")
    assert(prompt.contains("engine-delivered"), "the prompt must attribute the receipt to the engine")
    assert(actor.contains("由引擎负责送达"), "ProjectActor must attribute the receipt to the engine")
  }
end DispatcherTextTruthSpec
