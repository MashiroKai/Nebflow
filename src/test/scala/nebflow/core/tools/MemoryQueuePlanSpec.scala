package nebflow.core.tools

import munit.FunSuite
import nebflow.service.MemoryBudget

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 引擎侧只读 dry-run 计划 spec（2026-09-13 缺失自愈批 / 方案 E-D1 + §5）。
 *
 * 覆盖：定位语义（首个含 match 的 `- ` 条目；section 限定；`## ` 前缀可选）、
 * 分桶（would-apply / would-obsolete / would-defer）、取代检测、**预算 fail-closed**
 * （超硬顶即停、剩余留 pending；授权集为空 ⇒ refusal）、以及**零写入**（计划不动盘）。
 *
 * 另含一条**只读实测**用例：直接读**本机真实队列**（`~/.nebflow/memory/queue.jsonl`）
 * 打印三段计划文本——dry-run 的实测输出面（队列不存在则 skip，不做环境依赖的硬断言）。
 * 该用例不碰 `PathUtil`（同批其它 spec 会重定向 dataRoot，进程内共享 ⇒ 本 spec 全程走
 * 显式绝对路径，互不干扰），且**只读**：不写队列、不写记忆文件、不写快照。
 */
class MemoryQueuePlanSpec extends FunSuite:

  private def note(
    id: String,
    atMs: Long,
    target: String,
    action: String,
    section: Option[String] = None,
    matchText: Option[String] = None,
    content: Option[String] = None
  ): MemoryQueue.Note =
    MemoryQueue.Note(
      id,
      atMs,
      "2026-09-12T00:00:00Z",
      target,
      action,
      section,
      matchText,
      content,
      Some("s"),
      MemoryQueue.TriggerManual
    )

  private def stateOf(
    notes: Vector[MemoryQueue.Note],
    outcomes: Vector[MemoryQueue.Outcome] = Vector.empty
  ): MemoryQueue.State =
    MemoryQueue.State(notes, outcomes, Set.empty, 0, 0)

  private def tf(path: String, content: String): MemoryQueue.TargetFile = MemoryQueue.TargetFile(path, content)
  private def tfMissing(path: String): MemoryQueue.TargetFile = MemoryQueue.TargetFile(path, "", exists = false)

  private val userFile = "/tmp/x/User.md"

  // ── 定位语义 + 分桶 ────────────────────────────────────────

  test("分桶：locate 命中 ⇒ would-apply；locate miss / section miss / target missing ⇒ would-retry（可重试族，非终态）"):
    val content = "# User\n\n## 工作风格\n\n- 早睡早起\n- 喜欢简洁\n"
    val notes = Vector(
      note("q-1", 1L, "user", "update", None, Some("早睡早起"), Some("- 晚睡晚起")),
      note("q-2", 2L, "user", "remove", None, Some("不存在的条目"), None),
      note("q-3", 3L, "user", "append", Some("不存在的节"), None, Some("- 新条目")),
      note("q-4", 4L, "project:ghost", "append", None, None, Some("- 无目标")),
      note("q-5", 5L, "user", "append", Some("工作风格"), None, Some("- 喜欢简洁"))
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, content)))
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 1, s"只有 q-1 可落: ${plan.items}")
    assertEquals(plan.authorized.sorted, Vector("q-1", "q-2", "q-3", "q-4", "q-5"), "可落条目 + 只回写裁决的条目都进授权集（后者零文件写）")
    assertEquals(plan.deferred, Vector.empty[String], "无预算截断")
    assert(plan.refusal.isEmpty, "有可落条目 ⇒ 闸不拒")
    val byRef = plan.items.map(i => i.ref -> (i.bucket, i.detail)).toMap
    assertEquals(byRef("q-2")._1, MemoryQueue.Bucket.WouldRetry, "定位不到条目 ⇒ 可重试族")
    assert(byRef("q-2")._2.contains("locate-miss"), s"${byRef("q-2")}")
    assertEquals(byRef("q-3")._1, MemoryQueue.Bucket.WouldRetry, "目标节不存在 ⇒ 可重试族")
    assert(byRef("q-3")._2.contains("section not found"), s"${byRef("q-3")}")
    assertEquals(byRef("q-4")._1, MemoryQueue.Bucket.WouldRetry, "目标文件不存在 ⇒ 可重试族")
    assert(byRef("q-4")._2.contains("target-missing"), s"${byRef("q-4")}")
    assertEquals(byRef("q-5")._1, MemoryQueue.Bucket.WouldObsolete, "逐字已在文件里 ⇒ 终态族（deduped）")
    assert(byRef("q-5")._2.contains("already-present"), s"q-5 已存在同条目 ⇒ 不重复落: ${byRef("q-5")}")
    // 可重试族不得打终态词：桶名 + detail 里都不许出现 obsolete
    Vector("q-2", "q-3", "q-4").foreach { r =>
      assert(!byRef(r)._2.toLowerCase.contains("obsolete"), s"$r 的可重试判词不得含终态词: ${byRef(r)}")
    }
    assertEquals(plan.retryable.sorted, Vector("q-2", "q-3", "q-4"), "可重试族清单（简报的响亮面）")
    assertEquals(plan.refusal, None, "有可落条目 ⇒ 闸不拒")

  test("①（A′）：目标文件**不存在** ≠ **空文件** ⇒ would-retry、零新建（投影 0 B、不进落笔面）"):
    // 同一个空内容：exists=true ⇒ append 走「空文件追加」（would-apply）；exists=false ⇒ 零新建
    val notes = Vector(note("q-1", 1L, "project:neblink-server", "append", None, None, Some("- [DECISION] 新条")))
    val ghost = "/tmp/x/neblink-server/.nebflow/memory.md"
    val absent = MemoryQueue.plan(stateOf(notes), Map("project:neblink-server" -> tfMissing(ghost)))
    assertEquals(absent.countOf(MemoryQueue.Bucket.WouldApply), 0, s"缺文件不得判可落（否则消费侧会新建）: ${absent.items}")
    assertEquals(absent.countOf(MemoryQueue.Bucket.WouldRetry), 1)
    assert(absent.items.head.detail.contains("does not exist"), s"${absent.items.head}")
    val pj = absent.projections.find(_.target == "project:neblink-server").get
    assertEquals(pj.beforeBytes, 0L)
    assertEquals(pj.projectedBytes, 0L, "零新建：投影恒 0（不许出现 0→N 的新建行）")
    assertEquals(pj.delta, 0L)
    // 对照：真·空文件（存在但 0 字节）照旧可落
    val empty = MemoryQueue.plan(stateOf(notes), Map("project:neblink-server" -> tf(ghost, "")))
    assertEquals(empty.countOf(MemoryQueue.Bucket.WouldApply), 1, s"存在且为空 ⇒ 追加到空文件（旧行为，未回退）: ${empty.items}")
    assertEquals(empty.projections.head.projectedBytes, "- [DECISION] 新条\n".getBytes(UTF_8).length.toLong)
    // 缺文件是**通则**（不止项目层）
    val userNotes = Vector(notes.head.copy(target = "user"))
    val noUser = MemoryQueue.plan(stateOf(userNotes), Map("user" -> tfMissing(userFile)))
    assertEquals(noUser.countOf(MemoryQueue.Bucket.WouldRetry), 1, "任一层文件缺失一律同办")

  test("`## ` 前缀在 section/match 参数里可选（与 MemoryNoteTool 归一化同规）"):
    val content = "# U\n\n## 工具环境\n\n- 主仓路径含空格不必转义\n"
    val notes = Vector(
      note("q-1", 1L, "user", "update", Some("## 工具环境"), Some("空格不必转义"), Some("- 主仓路径含空格（不转义）")),
      note("q-2", 2L, "user", "remove", Some("工具环境"), Some("不必转义"), None)
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, content)))
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 1, s"两条都定位到 ⇒ 只末条生效: ${plan.items}")
    assertEquals(plan.items.count(_.detail.contains("superseded-by-later")), 1, s"同一定位行 ⇒ 前条 superseded: ${plan.items}")

  test("取代检测：同 (target, line) 的多条 update/remove 只末条有效；append 被后续 remove 命中同样 superseded"):
    val content = "# U\n\n## 节\n\n- 甲条目\n- 乙条目\n"
    val notes = Vector(
      note("q-1", 1L, "user", "update", None, Some("甲条目"), Some("- 甲条目（改）")),
      note("q-2", 2L, "user", "update", None, Some("甲条目"), Some("- 甲条目（再改）")),
      note("q-3", 3L, "user", "append", None, None, Some("- 丙条目（本轮新记）")),
      note("q-4", 4L, "user", "remove", None, Some("丙条目（本轮新记）"), None)
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, content)))
    // q-1/q-2 定位到同一行（甲条目）⇒ q-1 superseded；q-3 被 q-4 的 match 命中 ⇒ superseded
    assertEquals(plan.items.filter(_.detail.startsWith("superseded")).map(_.ref).sorted, Vector("q-1", "q-3"))
    assertEquals(
      plan.countOf(MemoryQueue.Bucket.WouldApply),
      1,
      s"只有 q-2 可落（q-1 同线 superseded、q-3 被后续 remove 命中 superseded、q-4 静态定位不到 ⇒ 均 would-obsolete）: ${plan.items}"
    )

  test("②（真时序）：同一行上**时间更晚的 remove** 胜过**时间更早的 update**（相位序会判反）"):
    // 生产实测 8 条分歧的 4 条同形（最大早 16.3 h）。相位序 = remove(0) 先、update(1) 后
    // ⇒ 改动前 `dropRight(1)` 留下 update ⇒ 更晚的 remove 被更早的 update 吃掉。
    val content = "# U\n\n## 节\n\n- 甲条目\n"
    val early = note("q-early", 1000L, "user", "update", None, Some("甲条目"), Some("- 甲条目（改）"))
    val late = note("q-late", 9000L, "user", "remove", None, Some("甲条目"), None)
    val plan = MemoryQueue.plan(stateOf(Vector(early, late)), Map("user" -> tf(userFile, content)))
    val byRef = plan.items.map(i => i.ref -> (i.bucket, i.detail)).toMap
    assertEquals(byRef("q-late")._1, MemoryQueue.Bucket.WouldApply, s"更晚的意图赢: ${plan.items}")
    assertEquals(byRef("q-early")._1, MemoryQueue.Bucket.WouldObsolete, s"更早的 update 转被取代: ${plan.items}")
    assert(byRef("q-early")._2.startsWith("superseded-by-later"), s"${byRef("q-early")}")
    // 同毫秒 ⇒ 以 id 定序（与 after() 逐字同规）
    val a = note("q-a", 500L, "user", "update", None, Some("甲条目"), Some("- 甲条目（A）"))
    val b = note("q-b", 500L, "user", "update", None, Some("甲条目"), Some("- 甲条目（B）"))
    val tie = MemoryQueue.plan(stateOf(Vector(a, b)), Map("user" -> tf(userFile, content)))
    assertEquals(tie.items.find(_.ref == "q-a").get.bucket, MemoryQueue.Bucket.WouldObsolete, "同毫秒：id 大者胜")

  test("② 的反面：预算闸的**落地顺序仍是相位序**（收缩先落）——两层基准互不干扰"):
    // remove（phase 0）先于 append（phase 2）累计预算；若把时序当落地序 ⇒ 净减条目排到最后
    val base = "# U\n\n## 节\n\n- " + ("v" * 400) + "\n"
    val notes = Vector(
      note("q-late-rm", 9000L, "user", "remove", None, Some("v" * 20), None),
      note("q-early-ap", 1000L, "user", "append", None, None, Some("- 新条目"))
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, base)))
    assertEquals(plan.items.map(_.ref), Vector("q-late-rm", "q-early-ap"), "落地模拟按相位序（时序更晚的 remove 仍先落）")
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 2, s"收缩先落 ⇒ 两条都进落笔面: ${plan.items}")
    assert(plan.projections.head.delta < 0, s"净减（remove 先生效）: ${plan.projections.head}")

  test("顺序模拟（比静态近似更准）：前序 update 让后续 match 可定位 ⇒ 判 would-apply 而非 locate-miss"):
    val content = "# U\n\n## 节\n\n- 甲条目\n"
    val notes = Vector(
      note("q-1", 1L, "user", "update", None, Some("甲条目"), Some("- 甲条目（改）")),
      note("q-2", 2L, "user", "update", None, Some("甲条目（改）"), Some("- 甲条目（再改）"))
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, content)))
    val q2 = plan.items.find(_.ref == "q-2").get
    assertEquals(q2.bucket, MemoryQueue.Bucket.WouldApply, "q-2 的 match 依赖 q-1 的产物 ⇒ 顺序模拟能定位到")
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 2)

  test("只读：计划计算不落任何文件、不改队列、不写快照"):
    val home = os.Path(Files.createTempDirectory("nb-memq-plan"))
    try
      val queue = home / "memory" / "queue.jsonl"
      val user = home / "User.md"
      os.write.over(user, "# U\n\n## 节\n\n- 甲条目\n", createFolders = true)
      os.write.over(
        queue,
        """{"kind":"note","id":"q-1","atMs":1,"at":"t","target":"user","action":"append","content":"- 新","source":{"trigger":"manual"}}""" + "\n",
        createFolders = true
      )
      val beforeUser = os.read(user)
      val beforeQueue = os.read(queue)
      val beforeList = os.list(home).map(_.last).toList.sorted
      val st = MemoryQueue.parseState(os.read(queue).linesIterator.toVector)
      val plan = MemoryQueue.plan(st, Map("user" -> tf(user.toString, os.read(user))))
      assert(plan.items.nonEmpty)
      assertEquals(os.read(user), beforeUser, "记忆文件零写入")
      assertEquals(os.read(queue), beforeQueue, "队列零写入（无 outcome 追加）")
      assertEquals(os.list(home).map(_.last).toList.sorted, beforeList, "无新目录/文件（无快照副作用）")
    finally os.remove.all(home)
    end try

  // ── 预算 fail-closed（硬红线：超硬顶即停、剩余留 pending）──────

  test("预算 fail-closed：超硬顶即停 ⇒ 该条起全部 would-defer，authorized 只含停点之前"):
    // user 硬顶 51200：先塞一条接近硬顶的内容，末尾留 100B 余量，再排两条 append
    val head = "# U\n\n## 节\n\n" + ("- x" * ((MemoryBudget.UserHardBytes - 100 - 12 - 4 - 1).toInt / 3)) + "\n"
    val base = head + "- " + ("y" * 60) + "\n"
    val baseBytes = base.getBytes(UTF_8).length.toLong
    assert(baseBytes < MemoryBudget.UserHardBytes, s"前置：基线 $baseBytes 未超硬顶")
    val deficit = (MemoryBudget.UserHardBytes - baseBytes).toInt
    val filler = "- " + ("z" * (deficit + 4)) // 单条即超顶（含行尾换行）
    val notes = Vector(
      note("q-1", 1L, "user", "append", None, None, Some(filler)),
      note("q-2", 2L, "user", "append", None, None, Some("- 后续条目"))
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, base)))
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldDefer), 2, s"q-1 即超顶 ⇒ q-1/q-2 全 defer: ${plan.items}")
    assert(plan.items.head.detail.contains("budget:"), s"${plan.items.head}")
    assert(
      plan.items.head.detail.contains("staying here") || plan.items.head.detail.contains("stopping here"),
      s"${plan.items.head}"
    )
    assertEquals(plan.authorized, Vector.empty[String], "停点起无授权条目")
    val refusal = plan.refusal.getOrElse(fail("授权集为空 ⇒ 必须 fail-closed 拒绝本轮"))
    assert(refusal.contains("REFUSED"), refusal)
    val proj = plan.projections.find(_.target == "user").get
    assert(proj.projectedBytes <= MemoryBudget.UserHardBytes, s"投影不超硬顶（未落的部分不算）: ${proj.projectedBytes}")

  test("预算 fail-closed 的反面：收缩批先落（remove 净减字节）⇒ 后续 append 仍能进授权集"):
    val base = "# U\n\n## 节\n\n- " + ("v" * 400) + "\n- " + ("w" * 100) + "\n"
    val notes = Vector(
      note("q-1", 1L, "user", "remove", None, Some("v" * 20), None),
      note("q-2", 2L, "user", "append", None, None, Some("- 新条目"))
    )
    val plan = MemoryQueue.plan(stateOf(notes), Map("user" -> tf(userFile, base)))
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 2, s"收缩在先 ⇒ update/append 都能落: ${plan.items}")
    val proj = plan.projections.find(_.target == "user").get
    assert(proj.delta < 0, s"净减字节（remove 生效）: ${proj.delta}")
    assertEquals(plan.refusal, None)

  // ── 实测：本机真实队列（只读）────────────────────────────────

  test("实测（只读）：本机队列 dry-run ⇒ would-apply / would-obsolete / would-defer 三段 + 逐文件投影"):
    val root =
      sys.env.get("NEBFLOW_HOME").map(os.Path(_)).getOrElse(os.Path(System.getProperty("user.home")) / ".nebflow")
    val queue = root / "memory" / "queue.jsonl"
    assume(os.exists(queue), s"本机队列不存在（$queue）——环境依赖用例，skip")
    val lines = os.read(queue).linesIterator.toVector
    val raw = os.read(queue)
    val st = MemoryQueue.parseState(lines)
    assume(st.notes.nonEmpty, "队列无 note —— skip")
    val files = Map(
      "user" -> tf((root / "User.md").toString, if os.exists(root / "User.md") then os.read(root / "User.md") else ""),
      "agent" -> tf(
        (root / "agents" / "Nebula" / "memory.md").toString,
        if os.exists(root / "agents" / "Nebula" / "memory.md") then os.read(root / "agents" / "Nebula" / "memory.md")
        else ""
      )
    )
    val plan = MemoryQueue.plan(st, files)
    println("[memqueue-dry-run][BEGIN]")
    println(plan.render())
    println("[memqueue-dry-run][END]")
    // 内部一致性：每条 pending note 恰有一个桶；分桶计数之和 = pending 数
    assertEquals(plan.items.size, st.pending.size, "每条 pending 都有去向（禁静默丢）")
    assertEquals(
      plan.countOf(MemoryQueue.Bucket.WouldApply) + plan.countOf(MemoryQueue.Bucket.WouldObsolete) +
        plan.countOf(MemoryQueue.Bucket.WouldRetry) + plan.countOf(MemoryQueue.Bucket.WouldDefer),
      plan.items.size
    )
    assertEquals(
      plan.authorized.size,
      plan.countOf(MemoryQueue.Bucket.WouldApply) + plan.countOf(MemoryQueue.Bucket.WouldObsolete) +
        plan.countOf(MemoryQueue.Bucket.WouldRetry)
    )
    assertEquals(plan.retryable.size, plan.countOf(MemoryQueue.Bucket.WouldRetry), "retryable = would-retry 的 ref 集")
    // 🔴 可重试族（目标缺失族）一律不得落进终态桶——否则「文件还没建」被当成「内容已作废」
    assert(
      !plan.retryable.exists(r => plan.items.exists(i => i.ref == r && i.bucket == MemoryQueue.Bucket.WouldObsolete))
    )
    assertEquals(os.read(queue), raw, "实测全程只读：队列逐字未变")

end MemoryQueuePlanSpec
