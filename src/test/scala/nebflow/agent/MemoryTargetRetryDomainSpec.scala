package nebflow.agent

import munit.FunSuite
import nebflow.core.tools.MemoryQueue
import nebflow.core.tools.MemoryQueue.Bucket

import cats.effect.unsafe.implicits.global

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/**
 * 目标缺失族的**值域口径三处一致** spec（2026-09-13 r3 批 / 缺陷① A′ 硬要求 1+2）。
 *
 * 口径 = **「目标缺失」（目标文件不存在 / 目标节不存在 / 该节内定位不到条目 / apply-miss）
 * 属可重试族，不得打终态词**。三处必须逐处自证：
 *   1. **引擎**（`MemoryQueue.planWith`）：该族落 `would-retry`（非 `would-obsolete`），
 *      `Plan.retryable` 非空，`Plan.render` 的判词明写 retryable + 「不得新建目标文件」。
 *   2. **`system.md`**（`seed/agents/memory-consolidator/system.md`）：该族 ⇒ `rejected`
 *      （可重试），**禁 `obsolete`**，**禁新建目标文件**。
 *   3. **简报指引**（`MemoryTrack.brief`）：整族一并写明，口径与上两处同。
 *
 * **判红**：三处任一被改回终态口径 ⇒ 本 spec 对应断言红（变异臂读数见交付件 §5）。
 * 断言面用**域词**（`would-retry` / `rejected` / `retryable` / 不得新建）而非整句措辞 ⇒
 * 改错口径必红、措辞微调不红。
 */
class MemoryTargetRetryDomainSpec extends FunSuite:

  // ── 夹具 ───────────────────────────────────────────────────────

  private def note(id: String, atMs: Long, target: String, action: String,
                   section: Option[String] = None, matchText: Option[String] = None,
                   content: Option[String] = None): MemoryQueue.Note =
    MemoryQueue.Note(id, atMs, "2026-09-13T00:00:00Z", target, action, section, matchText, content, Some("s"), MemoryQueue.TriggerManual)

  private def stateOf(notes: Vector[MemoryQueue.Note]): MemoryQueue.State =
    MemoryQueue.State(notes, Vector.empty, Set.empty, 0, 0)

  private val userFile    = "/tmp/x/User.md"
  private val liveContent = "# U\n\n## 节\n\n- 甲条目\n"

  /** 整族夹具：缺文件 / 缺节 / 节内定位不到 各一条。 */
  private val familyNotes = Vector(
    note("q-filemiss", 1L, "project:ghost", "append", None, None, Some("- 无目标")),
    note("q-secmiss", 2L, "user", "append", Some("不存在的节"), None, Some("- 新条目")),
    note("q-locmiss", 3L, "user", "remove", None, Some("不存在的条目"), None)
  )

  private def familyPlan: MemoryQueue.Plan =
    MemoryQueue.plan(
      stateOf(familyNotes),
      Map(
        "user"          -> MemoryQueue.TargetFile(userFile, liveContent),
        "project:ghost" -> MemoryQueue.TargetFile("/tmp/x/ghost/.nebflow/memory.md", "", exists = false)
      )
    )

  private val workerRoot = "/tmp/nb-memtrack-workroot"

  private def seedSystemMd: String =
    val in = Option(getClass.getClassLoader.getResourceAsStream("seed/agents/memory-consolidator/system.md"))
      .getOrElse(fail("seed system.md 不在 classpath"))
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  // ── ① 引擎面 ───────────────────────────────────────────────────

  test("① 引擎面：目标缺失族落 would-retry（非终态），Plan.retryable 逐条列出，且仍进裁决面"):
    val plan = familyPlan
    assertEquals(plan.countOf(Bucket.WouldRetry), 3, s"整族三条都应可重试: ${plan.items}")
    assertEquals(plan.countOf(Bucket.WouldObsolete), 0, "整族不得落终态桶")
    assertEquals(plan.retryable.sorted, familyNotes.map(_.id).sorted, "retryable 清单与桶逐条一致")
    assertEquals(plan.authorized.sorted, familyNotes.map(_.id).sorted,
      "可重试族仍进裁决面（不授权的后果是消费者永远看不到 + 条目永不闭合 = 活锁）")
    assertEquals(Bucket.label(Bucket.WouldRetry), "would-retry", "桶名不得含终态词")
    val rendered = plan.render()
    assert(rendered.contains("would-retry"), s"render 必须列出第四段桶:\n$rendered")
    assert(rendered.contains("RETRYABLE"), s"render 的判词必须明写可重试:\n$rendered")
    assert(rendered.contains("must NOT be created"), "render 必须写明不得新建目标文件")

  test("① 引擎面（A′ 零新建）：缺文件条目投影恒 0 B、不进落笔面"):
    val plan = familyPlan
    val pj   = plan.projections.find(_.target == "project:ghost").get
    assertEquals(pj.beforeBytes, 0L)
    assertEquals(pj.projectedBytes, 0L, "零新建：不许出现 0→N 的新建投影")
    assertEquals(pj.delta, 0L)
    assertEquals(plan.countOf(Bucket.WouldApply), 0, "整族无可落条目（零文件写）")

  test("① 引擎面（通则）：缺文件 ≠ 空文件——同内容在 exists=true 下照旧可落"):
    val notes  = Vector(note("q-1", 1L, "project:g", "append", None, None, Some("- 新条")))
    val ghostP = "/tmp/x/g/.nebflow/memory.md"
    val absent = MemoryQueue.plan(stateOf(notes), Map("project:g" -> MemoryQueue.TargetFile(ghostP, "", exists = false)))
    val empty  = MemoryQueue.plan(stateOf(notes), Map("project:g" -> MemoryQueue.TargetFile(ghostP, "")))
    assertEquals(absent.countOf(Bucket.WouldRetry), 1)
    assertEquals(absent.projections.head.projectedBytes, 0L)
    assertEquals(empty.countOf(Bucket.WouldApply), 1, "文件存在且为空 ⇒ 追加到空文件（旧行为未回退）")
    assertEquals(empty.projections.head.projectedBytes, "- 新条\n".getBytes(UTF_8).length.toLong)

  // ── ② system.md 面 ─────────────────────────────────────────────

  test("② system.md 面：目标缺失族 ⇒ rejected（可重试）+ 禁 obsolete + 禁新建目标文件"):
    val md  = seedSystemMd
    val idx = md.indexOf("The target does not exist")
    assertEquals(idx >= 0, true, s"缺「目标不存在」整族的硬规则:\n$md")
    val seg = md.substring(idx, math.min(md.length, idx + 900))
    assert(seg.contains("`result=\"rejected\"`"), s"该族结局必须是 rejected（可重试）:\n$seg")
    assert(seg.contains("never `obsolete`"), s"必须明文禁终态词:\n$seg")
    assert(seg.contains("never create the target file"), s"必须明文禁新建目标文件:\n$seg")
    assert(seg.contains("section"), s"该族必须覆盖「目标节不存在」:\n$seg")
    // 反面：不得残留「缺目标 ⇒ obsolete」的旧口径
    assert(!md.contains("层不存在 ⇒ 记 `obsolete`"), "不得残留旧的终态口径")
    assert(!md.contains("missing ⇒ `obsolete`"), "不得残留旧的终态口径")
    // 路径纪律段同口径（项目层文件不存在 ⇒ 不动文件、不新建）
    val pIdx = md.indexOf("That path not existing")
    assertEquals(pIdx >= 0, true, "路径纪律段必须写明「目标路径不存在 ⇒ 不新建」")
    assert(md.substring(pIdx, math.min(md.length, pIdx + 400)).contains("do NOT create it"), s"路径纪律段缺禁新建")

  // ── ③ 简报指引面 ───────────────────────────────────────────────

  test("③ 简报指引面：整族一并写明，口径 = 可重试 + 禁 obsolete + 禁新建"):
    val text = MemoryTrack.brief(workerRoot, MemoryQueue.TriggerManual, familyNotes, familyPlan)
    assert(text.contains("目标缺失族"), s"简报必须给出整族指引:\n$text")
    assert(text.contains("禁止新建该文件"), "缺文件子族必须明写「禁止新建」")
    assert(text.contains("目标节不存在"), "必须覆盖「目标节不存在」")
    assert(text.contains("定位不到条目"), "必须覆盖「节内定位不到条目」")
    assert(text.contains("可重试"), "必须明写可重试口径")
    assert(text.contains("result=\"rejected\""), s"该族的**规定结局值**必须是 rejected:\n$text")
    assert(!text.contains("result=\"obsolete\""), s"该族不得被规定写 obsolete（终态词）:\n$text")
    familyNotes.foreach(n => assert(text.contains(n.id), s"${n.id} 未进简报（消费者看不到）"))
    assert(!text.contains("记 `obsolete` 并附一行原因"), "不得残留旧的终态指引")

  test("③ 简报指引面：无可重试族 ⇒ 该段整体消失（不留常驻噪声）"):
    val notes = Vector(note("q-ok", 1L, "user", "append", None, None, Some("- 新条目")))
    val plan  = MemoryQueue.plan(stateOf(notes), Map("user" -> MemoryQueue.TargetFile(userFile, liveContent)))
    val text  = MemoryTrack.brief(workerRoot, MemoryQueue.TriggerManual, notes, plan)
    assert(!text.contains("目标缺失族"), s"无该族 ⇒ 无该段:\n$text")

  // ── ④ 三处一致 ─────────────────────────────────────────────────

  test("④ 三处一致：引擎词 / system.md 词 / 简报词同指「可重试族」，且与折叠谓词同族"):
    val rendered = familyPlan.render()
    val md       = seedSystemMd
    val text     = MemoryTrack.brief(workerRoot, MemoryQueue.TriggerManual, familyNotes, familyPlan)
    assert(rendered.toLowerCase.contains("retryable"), "引擎 render 缺 retryable 域词")
    assert(md.contains("retryable"), "system.md 缺 retryable 域词")
    assert(text.contains("可重试"), "简报缺「可重试」域词")
    assert(!rendered.contains("-- would-obsolete (3)"), "引擎不把该族算进终态桶")
    assert(!md.contains("missing ⇒ `obsolete`"), "system.md 不给该族终态词")
    assert(!text.contains("记 `obsolete` 并附一行原因"), "简报不给该族终态词")
    // 口径必须与引擎折叠谓词同族：rejected ∈ 可重试集 ⇒ 条目保持 pending（重试引线是活的）
    assert(MemoryQueue.RetryableResults.contains(MemoryQueue.ResultRejected), "rejected 必须仍在可重试集")
    assert(!MemoryQueue.TerminalResults.contains(MemoryQueue.ResultRejected), "rejected 不许进终态集")
    // 对照：终态族（已被取代 / 逐字已存在）**仍然**打终态词——没有把整条分桶推平
    assert(MemoryQueue.TerminalResults.contains(MemoryQueue.ResultObsolete), "obsolete 必须仍是终态词")

  // ── ⑤ 实测（只读）：真实队列 + **真实输入面**（memoryFilesOf → readAll → planInput）──
  // 走引擎真码的输入装配路径（不是手搭 files 面）⇒ 引擎实测 projected bytes 覆盖
  // user / agent / 项目层（含缺陷①的 `project:neblink-server`）。**零写入**：队列 sha 前后断言。

  test("⑤ 实测（只读）：真实队列 + 真实输入面 ⇒ 引擎 projected bytes（含项目层）"):
    val queuePath = MemoryQueue.queuePath
    assume(os.exists(queuePath), s"本机队列不存在（$queuePath）—— 环境依赖用例，skip")
    val raw0 = os.read(queuePath)
    val sha0 = MessageDigest.getInstance("SHA-256").digest(raw0.getBytes(UTF_8)).map("%02x".format(_)).mkString
    val st   = MemoryQueue.readState()
    assume(st.pending.nonEmpty, "队列无 pending —— skip")
    val plan = (for
      files <- MemoryTrack.memoryFilesOf(st.pending)
      before <- MemoryTrack.readAll(files)
      input <- MemoryTrack.planInput(before)
    yield MemoryQueue.plan(st, input)).unsafeRunSync()
    println("[memtrack-live-plan][BEGIN]")
    println(plan.render())
    println(s"-- retryable (missing target family): ${plan.retryable.size} — ${plan.retryable.take(20).mkString(", ")}")
    println("[memtrack-live-plan][END]")
    // 只读断言
    val raw1 = os.read(queuePath)
    val sha1 = MessageDigest.getInstance("SHA-256").digest(raw1.getBytes(UTF_8)).map("%02x".format(_)).mkString
    assertEquals(sha1, sha0, "实测全程只读：队列逐字未变")
    // 结构不变量
    assertEquals(plan.items.size, st.pending.size, "每条 pending 都有去向")
    assertEquals(
      plan.countOf(Bucket.WouldApply) + plan.countOf(Bucket.WouldObsolete) +
        plan.countOf(Bucket.WouldRetry) + plan.countOf(Bucket.WouldDefer),
      plan.items.size)
    // 缺陷① 的现场：项目层文件**存在** ⇒ 今天不该出现 target-missing（A′ 今天零代价）
    val missingFile = plan.items.filter(i => i.bucket == Bucket.WouldRetry && i.detail.startsWith("target-missing"))
    println(s"-- would-retry/target-missing today: ${missingFile.size} (${missingFile.map(_.ref).take(10).mkString(", ")})")

end MemoryTargetRetryDomainSpec
