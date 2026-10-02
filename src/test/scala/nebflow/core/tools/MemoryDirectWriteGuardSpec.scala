package nebflow.core.tools

import munit.FunSuite
import nebflow.shared.MemoryBudget // W1 shim: main had nebflow.service.MemoryBudget; the merge moved it to shared

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * MemoryDirectWriteGuard spec（govmemory 批 2026-09-25，作者裁定 (e)① +
 * (f) 直写新形态的机械可复现面）。
 *
 * 覆盖：
 *  - classify：三层预算面（user / agent / project）逐路径判定；非预算层 ⇒ None
 *    （detail 文件、其它 agent 的 memory.md 不在闸内——与旧记账工具目标域同界）。
 *  - preCheck（只读、零写盘）：超硬顶且净增 ⇒ Left（结构化拒绝文案带
 *    MEMORYEDIT_BUDGET 与 top 节）；预算内/软警区 ⇒ Right；真收缩豁免的判据
 *    单源 MemoryWriteGate.shrinkExempt（该判据自身由 MemoryWriteGateSpec 钉住）。
 *  - postWriteReminder：超 80% 软线 ⇒ `<system-reminder>`（软/硬顶两变体）；
 *    预算内/非预算层 ⇒ ""；24h 同键防骚扰（同文件窗口内第二次 ⇒ ""）；
 *    resetForTest 清账后可再提醒。
 *
 * 隔离纪律：**本 spec 零写 `~/.nebflow`**——user/agent 两层用真实 home 路径字符串
 * 只做 classify/preCheck（目标文件不存在，preSize=0 ⇒ 任何投影都是净增，恰好
 * 覆盖「首写即受闸」形态；并断言 preCheck 未在盘上创建任何文件）；需要盘上实件
 * 的提醒面走 project 层（临时 workspace 下的 `.nebflow/memory.md`，classify 的
 * 判定式对任何非全局 home 的 workspace 都给 "project"）。
 */
class MemoryDirectWriteGuardSpec extends FunSuite:

  override def beforeEach(context: munit.BeforeEach): Unit =
    MemoryDirectWriteGuard.resetForTest()
    super.beforeEach(context)

  /** 真实时钟量级（墙钟毫秒）；防骚扰窗的判定在 0 起点的假时钟上会误判「窗口内」。 */
  private val Epoch = 1_700_000_000_000L

  private val home = sys.props("user.home")
  private val userPath = s"$home/.nebflow/User.md"
  private val agentPath = s"$home/.nebflow/agents/Nebula/memory.md"

  // ── classify：三层预算面 ─────────────────────────────────

  test("classify: user / agent 两层按精确路径命中"):
    assertEquals(MemoryDirectWriteGuard.classify(userPath), Some("user"))
    assertEquals(MemoryDirectWriteGuard.classify(agentPath), Some("agent"))

  test("classify: project 层 = 任意 workspace 的 .nebflow/memory.md（全局 home 下除外）"):
    val ws = os.temp.dir(prefix = "nb-mdwg-spec") / "proj-x"
    val p = (ws / ".nebflow" / "memory.md").toString
    assertEquals(MemoryDirectWriteGuard.classify(p), Some("project"))
    // 全局 home 域内的同名相对形态不算 project 层（全局三层判定优先）
    assertEquals(MemoryDirectWriteGuard.classify(s"$home/.nebflow/memory.md"), None,
      "global-home memory.md is not a project layer (not a budgeted target)")

  test("classify: 非预算层 ⇒ None（detail 文件 / 其它 agent / 普通文件）"):
    assertEquals(MemoryDirectWriteGuard.classify(s"$home/.nebflow/memory/abc123.md"), None,
      "detail files stay outside the whitelist")
    assertEquals(MemoryDirectWriteGuard.classify(s"$home/.nebflow/agents/Coder/memory.md"), None,
      "non-Nebula agent memory stays outside")
    assertEquals(MemoryDirectWriteGuard.classify("/tmp/whatever/notes.md"), None)

  // ── preCheck：写前检查（只读、零写盘）─────────────────────

  test("preCheck: user 层投影超硬顶且净增 ⇒ Left 拒绝（文案带 MEMORYEDIT_BUDGET + top 节），且零写盘"):
    val before = os.Path(userPath)
    val existedBefore = os.exists(before)
    val projected = "## Bulk\n\n" + ("x" * (MemoryBudget.UserHardBytes.toInt + 100))
    val result = MemoryDirectWriteGuard.preCheck(userPath, projected)
    assert(result.isLeft, "over-hard-cap net growth must be refused")
    val msg = result.swap.toOption.get.message
    assert(msg.contains("MEMORYEDIT_BUDGET"), s"structured budget code: $msg")
    assert(msg.contains("## Bulk"), "refusal text names the largest sections")
    // 只读闸：拒绝路径不在盘上留下任何文件（目标原本不在 ⇒ 现在也不在）
    assertEquals(os.exists(before), existedBefore, "preCheck must not create or touch the target file")

  test("preCheck: agent 层预算内投影 ⇒ Right 放行"):
    val result = MemoryDirectWriteGuard.preCheck(agentPath, "- 一条正常条目\n")
    assert(result.isRight)

  test("preCheck: 非预算层不设闸（detail 文件任意大小都放行）"):
    val p = os.temp.dir() / "detail-xyz.md"
    val projected = "x" * (MemoryBudget.AgentHardBytes.toInt + 1000)
    assert(MemoryDirectWriteGuard.preCheck(p.toString, projected).isRight,
      "unclassified paths pass the guard (whitelist scope)")

  test("preCheck: 软警区（超软线未超硬顶）⇒ Right 放行（提醒面交给 postWriteReminder）"):
    val ws = os.temp.dir(prefix = "nb-mdwg-spec-soft") / "ws"
    os.makeDir.all(ws / ".nebflow")
    val p = ws / ".nebflow" / "memory.md"
    os.write.over(p, "x" * (MemoryBudget.ProjectSoftBytes.toInt + 10)) // 软警区实件
    val projected = "y" * (MemoryBudget.ProjectSoftBytes.toInt + 20)
    assert(MemoryDirectWriteGuard.preCheck(p.toString, projected).isRight,
      "warn zone passes the pre-check; only the hard cap refuses")

  // ── postWriteReminder：超限即时提醒 + 24h 防骚扰 ──────────

  test("postWriteReminder: 超软线未超硬顶 ⇒ 软变体 system-reminder（带字节数与软线读数）"):
    val ws = os.temp.dir(prefix = "nb-mdwg-remind") / "ws"
    os.makeDir.all(ws / ".nebflow")
    val p = ws / ".nebflow" / "memory.md"
    val bytes = MemoryBudget.ProjectSoftBytes + 100 // > 8KB 软线，< 10KB 硬顶
    os.write.over(p, "x" * bytes.toInt)
    val r = MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = Epoch + 1_000L)
    assert(r.startsWith("<system-reminder>"), s"engine system-reminder form: $r")
    assert(r.contains("Memory budget:"), s"soft variant copy: $r")
    assert(r.contains(s"$bytes bytes"), "carries the actual byte count")
    assert(r.contains(s"soft line ${MemoryBudget.ProjectSoftBytes}"), "carries the soft line")
    assert(!r.contains("HARD CAP"), "not the hard variant")

  test("postWriteReminder: 超硬顶 ⇒ 硬变体（拒绝预告 + 先收缩指引）"):
    val ws = os.temp.dir(prefix = "nb-mdwg-hard") / "ws"
    os.makeDir.all(ws / ".nebflow")
    val p = ws / ".nebflow" / "memory.md"
    val bytes = MemoryBudget.ProjectHardBytes + 500
    os.write.over(p, "x" * bytes.toInt)
    val r = MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = Epoch + 2_000L)
    assert(r.startsWith("<system-reminder>"))
    assert(r.contains("HARD CAP"), s"hard variant copy: $r")
    assert(r.contains("Shrink first"), "hard variant points at the self-rescue path")

  test("postWriteReminder: 24h 防骚扰——同键窗口内第二次为空；resetForTest 后可再提醒"):
    val ws = os.temp.dir(prefix = "nb-mdwg-dedup") / "ws"
    os.makeDir.all(ws / ".nebflow")
    val p = ws / ".nebflow" / "memory.md"
    os.write.over(p, "x" * (MemoryBudget.ProjectSoftBytes + 10).toInt)
    val t0 = Epoch
    assert(MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = t0).nonEmpty, "first reminder fires")
    assert(MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = t0 + 3600_000L).isEmpty,
      "same key within 24h is suppressed")
    assert(MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = t0 + 23L * 3600_000L).isEmpty,
      "still suppressed near the window edge")
    MemoryDirectWriteGuard.resetForTest()
    assert(MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = t0 + 3600_000L).nonEmpty,
      "ledger cleared ⇒ reminder fires again")

  test("postWriteReminder: 预算内 ⇒ 空；非预算层 ⇒ 空"):
    val ws = os.temp.dir(prefix = "nb-mdwg-within") / "ws"
    os.makeDir.all(ws / ".nebflow")
    val p = ws / ".nebflow" / "memory.md"
    os.write.over(p, "- tiny\n")
    assertEquals(MemoryDirectWriteGuard.postWriteReminder(p.toString, nowMs = 1L), "")
    val outside = os.temp.dir() / "plain.md"
    os.write.over(outside, "x" * 9000)
    assertEquals(MemoryDirectWriteGuard.postWriteReminder(outside.toString, nowMs = 1L), "")

  // 投影字节数判定与 MemoryBudget 同一 UTF-8 口径（中文条目的字节税同源）
  test("UTF-8 口径：中文投影按字节（非字符数）计"):
    val projected = "- 记忆条目\n" * 6000 // ~24B/行 × 6000 ≈ 144KB > 50KB 硬顶
    val projectedBytes = projected.getBytes(StandardCharsets.UTF_8).length.toLong
    assert(projectedBytes > MemoryBudget.UserHardBytes, "fixture must exceed the cap in bytes")
    assert(MemoryDirectWriteGuard.preCheck(userPath, projected).isLeft,
      "multi-byte content is budgeted by bytes, not chars")
end MemoryDirectWriteGuardSpec
