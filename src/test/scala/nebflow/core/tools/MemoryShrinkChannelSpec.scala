package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.service.{MemoryBudget, MemoryStore, MemoryWriteGate}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 收缩通道批（memshrinkgate，作者 2026-09-15 裁定 A）spec：
 * **「满格时放行删除/替换类条目落盘，`append` 类仍拒至回到预算内」**。
 *
 * 定性（裁定逐字）= **不是放权新语义**，而是把闸对齐其**已文档化的设计初衷**
 * （`src/main/resources/seed/agents/memory-consolidator/system.md:68`：over the cap ⇒
 * consolidate first（`remove` / `replace_section`）and then write—never land over-cap）。
 *
 * ## 扣发点（本批改前机械复现，红锚）
 *
 * 目标文件一旦**超硬顶**，同一轮里**全部**条目被扣发——**包括唯一能把文件拉回硬顶的收缩
 * 条目** ⇒ `authorized` 为空 ⇒ `Plan.refusal` 非空 ⇒ `MemoryTrack` 闸 2 fail-closed 拒绝本轮
 * ⇒ 零 spawn ⇒ 文件永远超顶、该目标条目永远 pending（自救路径被自己的前置闸掐死）。
 * 另一条同族路径：停点闩 `stopped` 只按目标置位、**不看方向** ⇒ 同目标前序**净增**条目触发
 * 停点后，后序**真收缩**条目也被一并扣发（改前读数：文件 51,173 B **未超顶**、收缩条目
 * Δ-63 B 却被判 `would-defer`）。
 *
 * ## 判据（**单源**，与落盘闸同值）
 *
 * `exempt = [[MemoryWriteGate.shrinkExempt]](PRE, POST, shrinkChannel = true)` —— 即闸的
 * `exempt = shrinkChannel && bytes <= preSizeBytes(path)`。🔴 **判据是字节比，不是动作名**：
 * 适格 + 净增照旧被截断（`append`、净增的 `replace_section` 都在此列）。
 *
 * ## 断言（任务书必给项 ④ 三条 + 两条本批补充）
 *
 *   - **a（净缩 ⇒ 放行）**：超硬顶 + `remove` 真收缩（收缩后**仍超顶**）⇒ 计划面 `would-apply`、
 *     `authorized` 非空、`refusal` 为空；落盘闸 + 实际写入 ⇒ **落盘成功**且文件变小。
 *   - **b（净增 ⇒ 照拒）**：同场景 `append` / 净增 `replace_section` ⇒ `would-defer`；
 *     落盘闸 `shrinkChannel = true` + 净增 ⇒ **照拒**（禁因豁免放宽而放走净增）。
 *   - **c（`append` 拒至回到预算内）**：顶格时 append 被拒；收缩把文件拉回硬顶内后 ⇒ 放行。
 *   - **d（停点闩不吞真收缩）**：同目标净增条目触发停点后，后序真收缩条目仍 `would-apply`。
 *   - **e（整文件覆盖不享豁免）**：WS `saveMemory` 的同一条代码路径（`MemoryStore.saveUserMemory`）
 *     + 真收缩 + 仍超顶 ⇒ **照拒**（`MemoryWriteGate:45-46`：整文件覆盖不是收缩通道；
 *     超限文件的自救路径必须是 `replace_section`）。本批**未**改动该路径。
 *
 * ## 变异臂（判红期望值）
 *
 *   - **M-1** 删计划面停点里的 `!exempt`（回到「按方向不分的硬顶停点」）⇒ **a / d / e-fix 应红**。
 *   - **M-2** 删停点闩里的 `&& !shrinksFile(...)` ⇒ **d 应红**。
 *   - **M-3** 把豁免判据换成「按动作名」（`n.action == "replace_section"`）⇒ **b 的净增
 *     `replace_section` 腿应红**（夹带净增被放行）。
 *   - **M-4** 让豁免无条件（删 `shrinkChannel &&`）⇒ **e 应红**（非适格通道也享豁免）。
 *   - **M-5** 给 `MemoryStore.saveFile` 传 `shrinkChannel = true` ⇒ **e 应红**（正是任务书
 *     必给项 ② 明禁的「整文件覆盖路径传 true」）。
 */
class MemoryShrinkChannelSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memshrink"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  // ── 夹具 ────────────────────────────────────────────────────────

  private def byteLen(s: String): Long = s.getBytes(UTF_8).length.toLong

  /**
   * 精确到字节的内容：`# U` / `## 节` / 两条可定位 marker 条目 / 填充条目，尾补齐到 n 字节。
   * 两条 marker 落在**不同行**（停点闩用例需要两条互不取代的定位行）。
   */
  private def contentOfBytes(n: Int): String =
    val head = "# U\n\n## 节\n\n"
    val marker = MarkerA + "\n" + MarkerB + "\n"
    val line = "- " + ("y" * 100) + "\n"
    val fixed = byteLen(head) + byteLen(marker)
    val lines = math.max(0, ((n - fixed) / byteLen(line)).toInt)
    val sofar = head + marker + line * lines
    val pad = n - byteLen(sofar)
    if pad <= 0 then head + marker
    else sofar + ("z" * pad.toInt) + "\n"

  private val MarkerA = "- MARKER-A 待删条目 " + ("m" * 40)
  private val MarkerB = "- MARKER-B 第二条 " + ("n" * 40)

  private def note(
    id: String,
    atMs: Long,
    action: String,
    section: Option[String] = None,
    matchText: Option[String] = None,
    content: Option[String] = None,
    target: String = "user"
  ): MemoryQueue.Note =
    MemoryQueue.Note(
      id,
      atMs,
      "2026-09-15T00:00:00Z",
      target,
      action,
      section,
      matchText,
      content,
      Some("s"),
      MemoryQueue.TriggerManual
    )

  private def planOf(notes: MemoryQueue.Note*)(base: String): MemoryQueue.Plan =
    MemoryQueue.plan(
      MemoryQueue.State(notes.toVector, Vector.empty, Set.empty, 0, 0),
      Map("user" -> MemoryQueue.TargetFile(userFile, base))
    )

  private val userFile = "/tmp/x/User.md"

  /**
   * 与 [[MemoryQueue]] 的 `applyOp` 同规的行删除（`split("\n", -1)` 保行尾空元素 ⇒
   * `mkString("\n")` 结果与 `lines.patch(ln-1, Nil, 1).mkString("\n")` 逐字同值）。
   */
  private def removeLine(s: String, marker: String): String =
    s.split("\n", -1).filterNot(l => l.startsWith("- ") && l.contains(marker)).mkString("\n")

  private def hard = MemoryBudget.UserHardBytes
  private def overCapBase = contentOfBytes(hard.toInt + 300)
  private def atCapBase = contentOfBytes(hard.toInt - 28)

  private def bucketOf(plan: MemoryQueue.Plan, ref: String): MemoryQueue.Bucket =
    plan.items.find(_.ref == ref).map(_.bucket).getOrElse(fail(s"$ref 不在计划里：${plan.items}"))

  private def projection(plan: MemoryQueue.Plan) =
    plan.projections.find(_.target == "user").getOrElse(fail("缺 user 投影"))

  // ── 断言 a：超硬顶 + 真收缩（仍超顶）⇒ 放行 + 落盘 ─────────────

  test("断言 a：超硬顶 + remove 真收缩（收缩后仍超顶）⇒ 计划面放行、refusal 为空、落盘成功") {
    val base = overCapBase
    assert(byteLen(base) > hard, s"前置：夹具须超硬顶（实际 ${byteLen(base)}）")
    val plan = planOf(note("q-rm", 1L, "remove", matchText = Some("MARKER-A")))(base)

    assertEquals(bucketOf(plan, "q-rm"), MemoryQueue.Bucket.WouldApply, s"真收缩不得被硬顶停点扣发：${plan.items}")
    assertEquals(plan.deferred, Vector.empty[String], "净缩条目不进 deferred")
    assertEquals(plan.authorized, Vector("q-rm"))
    assertEquals(plan.refusal, None, "有可落条目 ⇒ 不得 fail-closed 拒绝本轮")

    val p = projection(plan)
    assert(p.delta < 0, s"投影须为净减：$p")
    assert(p.projectedBytes > hard, s"要害：收缩后**仍超顶**（${p.projectedBytes} > $hard）—— 改前此条即被扣发")

    // 落盘腿：闸的字节比独立裁决 + 实际写入（`guard *> os.write.over`，同 MemoryStore.saveFile 形）
    val shrunk = removeLine(base, "MARKER-A")
    assertEquals(byteLen(shrunk), p.projectedBytes, "夹具复算的收缩结果须与计划投影逐字同值")
    val memPath = home / "User.md"
    os.write.over(memPath, base, createFolders = true)
    assert(MemoryWriteGate.shrinkExempt(byteLen(base), byteLen(shrunk), shrinkChannel = true), "适格身份 + 字节比 ⇒ 豁免")
    MemoryWriteGate.guard("user", memPath, shrunk, shrinkChannel = true).unsafeRunSync() // 不抛 = 豁免放行
    os.write.over(memPath, shrunk)

    assertEquals(os.read(memPath), shrunk, "落盘成功（文件变小）")
    assert(byteLen(os.read(memPath)) < byteLen(base), "收缩真的发生（这正是超限文件的自救路径）")
  }

  test("断言 a（第二腿）：超硬顶 + replace_section 真收缩（仍超顶）⇒ 不触停点、不置停点闩") {
    val base = overCapBase
    // 节体压到仍超顶的长度（净缩但未回到硬顶内）——最容易被「按方向不分」的停点误杀
    val body = "- " + ("p" * (hard.toInt + 100))
    val plan = planOf(
      note("q-rs", 1L, "replace_section", section = Some("节"), content = Some(body)),
      note("q-ap", 2L, "append", content = Some("- 后续条目"))
    )(base)
    assertEquals(bucketOf(plan, "q-rs"), MemoryQueue.Bucket.WouldApply, s"${plan.items}")
    assert(plan.items.find(_.ref == "q-rs").get.deltaBytes < 0, "replace_section 净缩")
    // 净增条目（append）照旧被截断 —— 豁免只对净缩成立
    assertEquals(bucketOf(plan, "q-ap"), MemoryQueue.Bucket.WouldDefer, s"${plan.items}")
  }

  // ── 断言 b：同场景净增 ⇒ 照拒 ──────────────────────────────────

  test("断言 b：超硬顶 + 净增（append / 净增 replace_section）⇒ 照拒；闸 shrinkChannel=true 也照拒") {
    val base = overCapBase
    val plan = planOf(
      note("q-ap", 1L, "append", content = Some("- 新条目")),
      note("q-rs", 2L, "replace_section", section = Some("节"), content = Some("- " + ("q" * (hard.toInt + 500)))),
      note("q-ap2", 3L, "append", content = Some("- 再一条"))
    )(base)
    assertEquals(bucketOf(plan, "q-ap"), MemoryQueue.Bucket.WouldDefer, s"${plan.items}")
    assertEquals(bucketOf(plan, "q-rs"), MemoryQueue.Bucket.WouldDefer, "净增的 replace_section 不享豁免（判据是字节比，不是动作名）")
    assertEquals(bucketOf(plan, "q-ap2"), MemoryQueue.Bucket.WouldDefer, "停点闩：同目标后续净增一律截断")
    assertEquals(plan.authorized, Vector.empty[String])
    assert(plan.refusal.isDefined, "无净缩条目 ⇒ 授权集为空 ⇒ fail-closed 拒绝本轮")

    // 落盘闸同判据：适格身份 + 净增 ⇒ 照拒
    val memPath = home / "User.md"
    val grown = base + ("g" * 100)
    os.write.over(memPath, base, createFolders = true)
    assert(!MemoryWriteGate.shrinkExempt(byteLen(base), byteLen(grown), shrinkChannel = true), "净增不豁免")
    val err = intercept[MemoryWriteGate.Rejected](
      MemoryWriteGate.guard("user", memPath, grown, shrinkChannel = true).unsafeRunSync()
    )
    assertEquals(err.code, MemoryWriteGate.Code.Budget)
    assertEquals(os.read(memPath), base, "被拒 ⇒ 零写入")
  }

  // ── 断言 c：append 拒至回到预算内 ──────────────────────────────

  test("断言 c：顶格时 append 被拒；净缩把文件拉回硬顶内后 ⇒ append 放行") {
    val atCap = atCapBase
    assert(byteLen(atCap) <= hard && byteLen(atCap) > MemoryBudget.UserSoftBytes, s"前置：顶格（${byteLen(atCap)}）")
    val tooBig = "- " + ("w" * 150) // 单条即超顶（顶格余量仅 ~27 B，净缩 65 B 也装不下它）
    val stuck = planOf(note("q-ap", 1L, "append", content = Some(tooBig)))(atCap)
    assertEquals(bucketOf(stuck, "q-ap"), MemoryQueue.Bucket.WouldDefer, "顶格 append ⇒ 拒")
    assert(stuck.refusal.isDefined, "无可落条目 ⇒ refusal")

    // 净缩把文件拉回硬顶内 ⇒ 同一条 append 放行（「拒**至**回到预算内」的正腿）
    val shrunkBase = contentOfBytes(hard.toInt - 4000)
    val freed = planOf(note("q-ap", 1L, "append", content = Some(tooBig)))(shrunkBase)
    assertEquals(bucketOf(freed, "q-ap"), MemoryQueue.Bucket.WouldApply, s"${freed.items}")
    assertEquals(freed.refusal, None)

    // 同轮内：净缩在前 + append 在后 ⇒ append 也能落（相位序 = 收缩先落）
    val sameRound = planOf(
      note("q-rm", 1L, "remove", matchText = Some("MARKER-A")),
      note("q-ap", 2L, "append", content = Some(tooBig))
    )(atCap)
    assertEquals(bucketOf(sameRound, "q-rm"), MemoryQueue.Bucket.WouldApply)
    assertEquals(
      bucketOf(sameRound, "q-ap"),
      MemoryQueue.Bucket.WouldDefer,
      "单条净缩（65 B）不足以装下 153 B 的 append ⇒ 仍拒（回到预算内才放）"
    )
  }

  // ── 断言 d：停点闩不再吞真收缩 ─────────────────────────────────

  test("断言 d：同目标净增条目触发停点后，另一行上的真收缩条目仍应放行（改前被闩吞掉）") {
    val atCap = atCapBase
    val grow = "- " + ("G" * 300)
    val plan = planOf(
      note("q-up-grow", 10L, "update", matchText = Some("MARKER-A"), content = Some(grow)),
      note("q-up-shrink", 20L, "update", matchText = Some("MARKER-B"), content = Some("- short B"))
    )(atCap)

    assertEquals(bucketOf(plan, "q-up-grow"), MemoryQueue.Bucket.WouldDefer, s"净增照旧被截断：${plan.items}")
    assertEquals(
      bucketOf(plan, "q-up-shrink"),
      MemoryQueue.Bucket.WouldApply,
      s"真收缩不得被「停点闩」吞掉（改前此条 = would-defer: stopped earlier）：${plan.items}"
    )
    assert(plan.items.find(_.ref == "q-up-shrink").get.deltaBytes < 0)
    assertEquals(plan.authorized, Vector("q-up-shrink"), "授权集非空 ⇒ 本轮可落地（不再零出队）")
    assertEquals(plan.refusal, None)
  }

  // ── 判据单源（防两面漂移）+ 分桶不变量 ─────────────────────────

  test("判据单源：计划面与落盘闸共用 shrinkExempt（字节比，非动作名）；超顶轮里 WouldApply 一律净缩") {
    // 真值表（闸侧）
    assert(MemoryWriteGate.shrinkExempt(100L, 90L, shrinkChannel = true), "适格 + 净缩 ⇒ 豁免")
    assert(MemoryWriteGate.shrinkExempt(100L, 100L, shrinkChannel = true), "适格 + 等长（非净增）⇒ 豁免")
    assert(!MemoryWriteGate.shrinkExempt(100L, 101L, shrinkChannel = true), "适格 + 净增 ⇒ 不豁免")
    assert(!MemoryWriteGate.shrinkExempt(100L, 90L, shrinkChannel = false), "不适格 ⇒ 永不豁免")

    // 计划面与闸同判据：超顶夹具上逐条比对（**动作名不参与** —— 同动作面出现净增时照拒）
    val base = overCapBase
    val bigBody = "- " + ("q" * (hard.toInt + 500)) // 净增的 replace_section 节体
    val notes = Vector(
      note("q-rm", 1L, "remove", matchText = Some("MARKER-A")), // 净缩（仍超顶）⇒ 放行
      note("q-rs", 2L, "replace_section", Some("节"), None, Some(bigBody)), // 净增 ⇒ 照拒
      note("q-up", 3L, "update", matchText = Some("MARKER-B"), content = Some("- short B")), // 净缩 ⇒ 放行
      note("q-ap", 4L, "append", content = Some("- 新条目")) // 净增 ⇒ 照拒
    )
    val plan = planOf(notes*)(base)
    val p = projection(plan)
    assertEquals(bucketOf(plan, "q-rm"), MemoryQueue.Bucket.WouldApply, s"${plan.items}")
    assertEquals(bucketOf(plan, "q-up"), MemoryQueue.Bucket.WouldApply, s"${plan.items}")
    assertEquals(bucketOf(plan, "q-rs"), MemoryQueue.Bucket.WouldDefer, "净增的 replace_section 照拒 ⇒ 裁决依据是字节比，不是动作名")
    assertEquals(bucketOf(plan, "q-ap"), MemoryQueue.Bucket.WouldDefer)
    assertEquals(plan.authorized, Vector("q-rm", "q-up"))
    assertEquals(plan.refusal, None)
    // 分桶不变量：超顶轮里 WouldApply 一律净缩（禁把净增放进来）
    assert(
      p.projectedBytes <= hard || plan.items
        .filter(_.bucket == MemoryQueue.Bucket.WouldApply)
        .forall(_.deltaBytes <= 0),
      s"超顶轮里 WouldApply 必须净缩：${plan.items}"
    )
    assertEquals(
      plan.countOf(MemoryQueue.Bucket.WouldDefer) + plan.countOf(MemoryQueue.Bucket.WouldApply) +
        plan.countOf(MemoryQueue.Bucket.WouldRetry) + plan.countOf(MemoryQueue.Bucket.WouldObsolete),
      notes.size
    )

    // 与闸**逐字同值**的两条腿（可精确复算落笔结果）：
    //   remove（净缩）⇒ 计划面 would-apply ∧ 闸 shrinkExempt = true
    val rmNext = removeLine(base, "MARKER-A")
    assert(byteLen(rmNext) > hard, "腿 1 夹具：净缩后仍超顶（最容易误杀的一档）")
    assert(MemoryWriteGate.shrinkExempt(byteLen(base), byteLen(rmNext), shrinkChannel = true), "闸：适格 + 净缩 ⇒ 豁免")
    assertEquals(
      bucketOf(planOf(note("q-rm", 1L, "remove", matchText = Some("MARKER-A")))(base), "q-rm"),
      MemoryQueue.Bucket.WouldApply,
      "计划面：同一对字节 ⇒ 同一裁决"
    )
    //   append（净增）⇒ 计划面 would-defer ∧ 闸 shrinkExempt = false
    val apNext = base + "- 新条目" + "\n"
    assert(byteLen(apNext) > hard, "腿 2 夹具：净增后仍超顶")
    assert(!MemoryWriteGate.shrinkExempt(byteLen(base), byteLen(apNext), shrinkChannel = true), "闸：适格 + 净增 ⇒ 不豁免")
    assertEquals(
      bucketOf(planOf(note("q-ap", 4L, "append", content = Some("- 新条目")))(base), "q-ap"),
      MemoryQueue.Bucket.WouldDefer,
      "计划面：同一对字节 ⇒ 同一裁决"
    )
  }

  // ── 断言 e：整文件覆盖路径不享豁免（任务书必给项 ②）──────────

  test("断言 e：WS saveMemory 同路径（MemoryStore.saveUserMemory）+ 真收缩 + 仍超顶 ⇒ 照拒（本批未改该路径）") {
    val memPath = home / "User.md"
    val pre = contentOfBytes(hard.toInt + 1000)
    val shrunk = contentOfBytes(hard.toInt + 500) // 真收缩，但仍超顶
    assert(byteLen(shrunk) < byteLen(pre), "夹具须为真收缩")
    assert(byteLen(shrunk) > hard, "夹具须仍超硬顶")
    os.write.over(memPath, pre, createFolders = true)

    val err = intercept[MemoryWriteGate.Rejected](MemoryStore.saveUserMemory(shrunk).unsafeRunSync())
    assertEquals(err.code, MemoryWriteGate.Code.Budget, "整文件覆盖**不是**收缩通道 ⇒ 不享豁免（超限文件的自救路径必须是 replace_section）")
    assertEquals(os.read(memPath), pre, "被拒 ⇒ 零写入")
  }

  // ── 端到端（夹具层面）：超顶轮不再整批扣发 ─────────────────────

  test("端到端（夹具）：超顶 + 净缩 + 一串 append ⇒ 授权集非空（改前 4/4 defer + REFUSED）") {
    val base = overCapBase
    val notes = Vector(
      note("q-rm", 1L, "remove", matchText = Some("MARKER-A")),
      note("q-ap1", 2L, "append", content = Some("- 新条目一")),
      note("q-ap2", 3L, "append", content = Some("- 新条目二")),
      note("q-ap3", 4L, "append", content = Some("- 新条目三"))
    )
    val plan = planOf(notes*)(base)
    assertEquals(plan.authorized, Vector("q-rm"), s"收缩条目必须拿到：${plan.items}")
    assertEquals(plan.refusal, None, "改前：authorized 空 ⇒ REFUSED ⇒ 零 spawn（死锁）")
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldDefer), 3, "净增条目照旧保持 pending")

    // 落地一步（收缩真的发生）后重算：文件回到硬顶内 ⇒ 后续 append 逐条放行（队列可出队）
    val shrunk = removeLine(base, "MARKER-A")
    val after = contentOfBytes(hard.toInt - 1000)
    val second = planOf(note("q-ap1", 2L, "append", content = Some("- 新条目一")))(after)
    assertEquals(bucketOf(second, "q-ap1"), MemoryQueue.Bucket.WouldApply);
    assert(byteLen(after) < byteLen(base) && byteLen(after) <= hard, s"收缩 ${byteLen(base)} → ${byteLen(after)}B（回到硬顶内）")
    assert(byteLen(shrunk) < byteLen(base), "收缩腿：remove 结果确实更小")
  }

end MemoryShrinkChannelSpec
