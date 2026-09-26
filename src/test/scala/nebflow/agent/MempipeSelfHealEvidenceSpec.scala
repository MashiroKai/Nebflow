package nebflow.agent

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.tools.{MemoryHistory, MemoryQueue}
import nebflow.shared.{MemoryBudget, MemoryStore, PathUtil}

import java.nio.file.Files

/**
 * 缺失自愈批**证据生成器**（2026-09-13 / 方案 C-D-E + 同批必修）。
 *
 * 存在的理由：验收要求「逐条给证据、禁口头结论」，而断言通过只说明**我们期望的
 * 性质成立**，不把**原始产物文本**留档。本 spec 不引入新的生产断言面，只把本批
 * 四条实证的**原始输出**打到 stdout，供证据文件 `tee` 落档、供独立复核逐字比对：
 *
 *   ③ C 自愈      → [[nebflow.core.seed.SeedAgentSelfHealSpec]] 已打原始日志行（本 spec 不重复）
 *   ④ D 响亮失败  → 注入一次 infra 失败，打 queue.jsonl 尾行 + 注入行全文（证明零 `rejected`）
 *   ⑤ dry-run 闸  → 打 plan.render() 全文 + refusal 全文（超预算 fail-closed）
 *   ⑥ 可重试恢复  → 同一 note 在「旧谓词 / 新谓词」下的 pending 判定对照（引线是否真的活）
 *
 * **与其它 spec 的关系**：本 spec 走 `PathUtil.setDataRoot` 重定向（同
 * [[MemoryTrackSpec]] 手法）。`Test / parallelExecution := false`（build.sbt:120）
 * ⇒ 同 JVM 内 suite 串行，重定向不会与邻座 spec 互踩。
 * 全程只碰临时 home，**不读写 `~/.nebflow`**。
 */
class MempipeSelfHealEvidenceSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-mempipe-evidence"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def reset(): Unit =
    os.remove.all(home / "memory")
    os.remove.all(home / "User.md")
    MemoryStore.invalidateUserCache()
    MemoryStore.invalidateAgentCache("Nebula")
    MemoryTrackSignal.resetForTest()

  private def enqueue(content: String): String =
    MemoryQueue
      .enqueue("user", "append", None, None, Some(content), Some("s"), MemoryQueue.TriggerManual, "Nebula")
      .toOption
      .get
      .id

  private def banner(id: String, what: String): Unit =
    println(s"[evidence-$id][BEGIN] $what")
  private def end(id: String): Unit = println(s"[evidence-$id][END]")

  // ── ④ D 实证：infra 失败不写 rejected，且告警可见 ────────────────

  test("证据④：infra 失败 ⇒ 零 rejected + 告警可见（原始输出）"):
    reset()
    enqueue("- 证据条目一")
    enqueue("- 证据条目二")
    val before = os.read(MemoryQueue.queuePath).linesIterator.toVector
    val written =
      MemoryTrack.degradeOutcomes(isTimeout = false, detail = "injected infra failure (evidence)").unsafeRunSync()
    val after = os.read(MemoryQueue.queuePath).linesIterator.toVector
    val st = MemoryQueue.readState()
    val injected = MemoryQueue.summaryLine()

    banner("D", "degradeOutcomes(isTimeout=false) 后的队列原始产物")
    println(s"[evidence-D] written=$written  queueLines ${before.size} -> ${after.size}")
    println("[evidence-D] appended raw lines:")
    after.drop(before.size).foreach(l => println(s"[evidence-D]   $l"))
    println(s"[evidence-D] results seen = ${st.outcomes.map(_.result).distinct.mkString(", ")}")
    println(s"[evidence-D] rejected count = ${st.outcomes.count(_.result == MemoryQueue.ResultRejected)}")
    println(s"[evidence-D] pendingCount after infra failure = ${st.pendingCount}")
    println(s"[evidence-D] retry signal (MemoryTrackSignal.peek) = ${MemoryTrackSignal.peek()}")
    println("[evidence-D] injected summary line (verbatim):")
    println(injected)
    end("D")

    assertEquals(st.outcomes.count(_.result == MemoryQueue.ResultRejected), 0, "infra 失败零 rejected")
    assertEquals(st.pendingCount, 2, "条目仍 pending（可重试）")
    assert(injected.contains("ALERT:"), injected)
    assert(injected.contains("never ran"), injected)

  // ── ⑤ dry-run 实测 + fail-closed ───────────────────────────────

  /** 造一份「再 append 一条必超硬顶」的 User.md ⇒ 计划必须拒绝本轮落地。 */
  test("证据⑤：dry-run 计划 + 预算 fail-closed（原始输出，超顶即停、剩余留 pending）"):
    reset()
    val hard = MemoryBudget.UserHardBytes
    os.write.over(
      MemoryStore.userMemoryPath,
      "# U\n\n## 节\n\n- " + ("y" * (hard - 40).toInt) + "\n",
      createFolders = true
    )
    // 单条即超顶（剩余空间 < 43B）⇒ 停点即首条
    val q1 = enqueue("- " + ("z" * 40))
    val q2 = enqueue("- " + ("z" * 41))
    val st = MemoryQueue.readState()
    val plan = MemoryQueue.plan(
      st,
      Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, os.read(MemoryStore.userMemoryPath)))
    )

    banner("E", "超预算场景 plan.render() 全文")
    println(plan.render())
    println(s"[evidence-E] authorized=${plan.authorized.mkString(",")}  deferred=${plan.deferred.mkString(",")}")
    println(s"[evidence-E] refusal = ${plan.refusal.getOrElse("(none)")}")
    println(s"[evidence-E] pending still = ${st.pending.map(_.id).mkString(",")} (all of them)")
    end("E")

    assert(plan.authorized.isEmpty, s"超顶 ⇒ 授权集必须空: ${plan.authorized}")
    assertEquals(plan.deferred.toSet, Set(q1, q2), "两条都 defer（停点起全停）")
    assert(plan.refusal.exists(_.contains("REFUSED")), plan.refusal)
    assertEquals(st.pending.map(_.id).toSet, Set(q1, q2), "剩余留 pending（硬红线：不一次性落地）")

  // ── ⑥ 可重试恢复：旧谓词 vs 新谓词 ─────────────────────────────

  test("证据⑥：打了 outcome 的 note —— 旧谓词判「已闭合/永不再 pending」，新谓词恢复可重试"):
    reset()
    val id = enqueue("- 条目一")
    assert(
      MemoryQueue.recordOutcome(id, MemoryQueue.ResultRejected, "memory-consolidator", "旧口径：infra 失败写 rejected").isRight
    )
    val st = MemoryQueue.readState()

    // 旧谓词（main 基线原文，MemoryQueue.scala:112-113）：任何 outcome 即闭合。
    val oldPending = st.notes.filterNot(n => st.outcomes.exists(_.ref == n.id) || st.droppedRefs.contains(n.id))
    // 新谓词：只认终态集合。
    val newPending = st.pending

    banner("retry", "同一 note + rejected outcome 下的旧/新 pending 判定")
    println(s"[evidence-retry] outcomes = ${st.outcomes.map(o => s"${o.ref}:${o.result}").mkString(", ")}")
    println(s"[evidence-retry] TerminalResults   = ${MemoryQueue.TerminalResults.toList.sorted.mkString(",")}")
    println(s"[evidence-retry] RetryableResults  = ${MemoryQueue.RetryableResults.toList.sorted.mkString(",")}")
    println(
      s"[evidence-retry] OLD predicate (main baseline) pending = ${oldPending.map(_.id).mkString(",")} (size ${oldPending.size})"
    )
    println(
      s"[evidence-retry] NEW predicate (this batch)    pending = ${newPending.map(_.id).mkString(",")} (size ${newPending.size})"
    )
    println(s"[evidence-retry] retryable? ${MemoryQueue.RetryableResults.contains(MemoryQueue.ResultRejected)}")
    end("retry")

    assertEquals(oldPending.size, 0, "旧谓词：条目被误判为已闭合 ⇒ 永不再 pending ⇒ 重试引线是死的")
    assertEquals(newPending.map(_.id).toVector, Vector(id), "新谓词：rejected 可重试 ⇒ 条目回到 pending ⇒ 引线复活")

  // ── 变更史 consume 行（降级也留痕）────────────────────────────

  test("证据（补）：降级也落变更史 consume 行 —— 失败不算「没发生过」"):
    reset()
    enqueue("- 条目一")
    MemoryTrack.degradeOutcomes(isTimeout = true, detail = "injected hard timeout (evidence)").unsafeRunSync()
    val stats = MemoryHistory.stats()
    banner("hist", "infra 失败后的变更史统计")
    println(s"[evidence-hist] consumed=${stats.consumed}")
    end("hist")
    assertEquals(stats.consumed, 1)
end MempipeSelfHealEvidenceSpec
