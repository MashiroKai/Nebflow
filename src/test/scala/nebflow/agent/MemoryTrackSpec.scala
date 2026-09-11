package nebflow.agent

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.compact.NebulaMemoryHook
import nebflow.core.tools.{MemoryHistory, MemoryQueue}
import nebflow.service.{MemoryBudget, MemoryStore}

import java.nio.file.Files

/**
 * 记忆轨 spec（2026-09-12 记忆改造批 / spec §5 R3 + R4 + R5/R6）。
 *
 * 覆盖：触发谓词（R9 VC3：三支全否 ⇒ 零请求）、硬/软超时 prop、降级路径（逐条写
 * outcome + 置位重试引线）、变更史 change 行、整理 agent 身份与工具面（恰七件、
 * 零 MemoryEdit）、`NebulaMemoryHook` 入队（W2 直写关闭）、队列摘要行注入。
 *
 * dataRoot 经 PathUtil.setDataRoot 重定向（DeviceIdentitySpec 先例）。
 */
class MemoryTrackSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memq-track"))
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
    MemoryQueue.enqueue("user", "append", None, None, Some(content), Some("s"), MemoryQueue.TriggerManual, "Nebula").toOption.get.id

  // ===== 触发谓词（spec §5 R4 / R9 VC3）=====

  test("谓词三支全否 ⇒ false（空队列 + 未超软线 + 无信号 = 零记忆轨 LLM 请求）"):
    assertEquals(
      MemoryTrack.shouldRun(
        userBytes = 1000L,
        agentBytes = 1000L,
        pendingCount = 0,
        unconsumedSignal = false),
      false)

  test("谓词三支任一为真 ⇒ true（字节 ∨ pending 计数 ∨ 未消费信号 的或）"):
    val under = 1000L
    assert(!MemoryTrack.shouldRun(under, under, 0, false), "三支全否")
    assert(MemoryTrack.shouldRun(MemoryBudget.UserSoftBytes + 1, under, 0, false), "user 字节超软线")
    assert(MemoryTrack.shouldRun(under, MemoryBudget.AgentSoftBytes + 1, 0, false), "agent 字节超软线")
    assert(MemoryTrack.shouldRun(under, under, 1, false), "队列非空（纯字节谓词今天恒空 ⇒ 必须叠这支）")
    assert(MemoryTrack.shouldRun(under, under, 0, true), "未消费信号（降级重试引线）")

  test("阈值 prop 化：-Dnebflow.memory.track.{soft,hard}TimeoutMs 覆盖生效，默认 480s/600s"):
    val softKey = "nebflow.memory.track.softTimeoutMs"
    val hardKey = "nebflow.memory.track.hardTimeoutMs"
    try
      assertEquals(MemoryTrack.softTimeoutMs, 480_000L)
      assertEquals(MemoryTrack.hardTimeoutMs, 600_000L)
      sys.props.update(softKey, "1000")
      sys.props.update(hardKey, "2000")
      assertEquals(MemoryTrack.softTimeoutMs, 1000L)
      assertEquals(MemoryTrack.hardTimeoutMs, 2000L)
    finally
      sys.props.remove(softKey)
      sys.props.remove(hardKey)
    assert(MemoryTrack.hardTimeoutMs >= MemoryTrack.softTimeoutMs, "硬顶必须 ≥ 软线")

  // ===== 降级（失败 / 超时同一路径）=====

  test("降级（失败）：本轮待办逐条写 outcome(rejected)，队列条目保留，重试引线置位"):
    reset()
    val ids = List(enqueue("- 条目一"), enqueue("- 条目二"))
    assertEquals(MemoryQueue.pendingCount(), 2)
    val written = MemoryTrack.degradeOutcomes(isTimeout = false, detail = "boom").unsafeRunSync()
    assertEquals(written, 2)
    val st = MemoryQueue.readState()
    assertEquals(st.pendingCount, 0, "每条 note 都有结局（不静默丢）")
    assertEquals(st.outcomes.map(_.result).distinct, Vector(MemoryQueue.ResultRejected))
    assertEquals(st.outcomes.map(_.by).distinct, Vector(MemoryTrack.AgentName))
    assertEquals(st.notes.size, 2, "note 行保留（append-only：队列条目不被改写/删除）")
    assertEquals(ids, st.notes.map(_.id).toList)
    assert(MemoryTrackSignal.peek().isDefined, "重试引线置位（否则失败一次即永久搁置）")
    assertEquals(MemoryHistory.stats().consumed, 2, "降级也落变更史 consume 行")

  test("降级（超时）：outcome(timeout)；已有 outcome 的条目不动（不覆盖已落地结局）"):
    reset()
    val id1 = enqueue("- 条目一")
    val id2 = enqueue("- 条目二")
    assert(MemoryQueue.recordOutcome(id2, MemoryQueue.ResultApplied, "memory-consolidator", "已写入").isRight)
    val written = MemoryTrack.degradeOutcomes(isTimeout = true, detail = "hard timeout").unsafeRunSync()
    assertEquals(written, 1, "只对仍无结局的条目补写")
    val st = MemoryQueue.readState()
    assertEquals(st.outcomes.find(_.ref == id2).map(_.result), Some(MemoryQueue.ResultApplied), "已落地结局未被覆盖")
    assertEquals(st.outcomes.find(_.ref == id1).map(_.result), Some(MemoryQueue.ResultTimeout))
    assert(MemoryTrackSignal.take().isDefined, "取走即清零（一次性语义）")
    assertEquals(MemoryTrackSignal.peek(), None)

  test("降级（空队列）：零写入、零置位（不制造空转重试）"):
    reset()
    val written = MemoryTrack.degradeOutcomes(isTimeout = true, detail = "no notes").unsafeRunSync()
    assertEquals(written, 0)
    assertEquals(MemoryTrackSignal.peek(), None)

  test("memoryBytes：文件缺失记 0，存在则报真实字节"):
    reset()
    val (u0, a0) = MemoryTrack.memoryBytes()
    assertEquals((u0, a0), (0L, 0L))
    os.write.over(MemoryStore.userMemoryPath, "x" * 1234, createFolders = true)
    val (u1, _) = MemoryTrack.memoryBytes()
    assertEquals(u1, 1234L)

  // ===== 身份与工具面（R5）=====

  test("整理 agent 身份：收敛名 ⇒ 工具面恰七件 = KernelFixedTools，零 MemoryEdit / 零编排件"):
    val defn = AgentDef(name = MemoryTrack.AgentName, description = "", category = "standalone")
    val tools = AgentCore.fixedToolsFor(defn)
    assertEquals(tools, AgentCore.KernelFixedTools)
    assertEquals(tools.size, 7, "恰七件（作者第④条：与 Delegate 内核相同的工具面）")
    assertEquals(tools, Set("Read", "Write", "Edit", "Glob", "Grep", "Bash", "AskUserQuestion"))
    assert(!tools.contains("MemoryEdit"), "队列化后它不需要 MemoryEdit")
    assert(AgentCore.ConvergedAgentNames.contains(MemoryTrack.AgentName), "收敛名 ⇒ tools/mcp 声明整体失效")
    assertEquals(
      AgentCore.fixedToolsFor(defn.copy(category = "team")),
      tools,
      "category 短路：team/flow 推不出 legacy 面")

  // ===== W2 直写关闭（IMPL-4 / R7(4) O-A）=====

  test("NebulaMemoryHook：facts 入队（trigger=dream），User.md 零直写"):
    reset()
    os.write.over(MemoryStore.userMemoryPath, "# User\n\n- 既有条目\n", createFolders = true)
    val before = os.read(MemoryStore.userMemoryPath)
    NebulaMemoryHook
      .enqueueFacts(List("FACT 1: [PATTERN] 新事实甲", "FACT 2: [DECISION] 新裁定乙", "不是 FACT 格式"), Some("sess-1"))
      .unsafeRunSync()
    assertEquals(os.read(MemoryStore.userMemoryPath), before, "关闭 User.md 直写（W2 绕过通道）")
    val st = MemoryQueue.readState()
    assertEquals(st.notes.size, 2, "两条可解析 fact 各入队一条（不可解析行丢弃）")
    assertEquals(st.notes.map(_.trigger).distinct, Vector(MemoryQueue.TriggerDream))
    assertEquals(st.notes.map(_.target).distinct, Vector("user"))
    assertEquals(st.notes.map(_.action).distinct, Vector("append"))
    assert(st.notes.forall(_.section.contains("## Dream Extract")), "落在稳定节（T3 区）")
    assert(st.notes.map(_.content.getOrElse("")).exists(_.contains("[PATTERN]")), "类别 in-band 保留")
    assertEquals(MemoryHistory.ofKind(MemoryHistory.KindQueue).head.actor, NebulaMemoryHook.Actor, "actor 引擎代写")

  // ===== 注入（IMPL-1）=====

  test("pending 计数一行注入：有 pending ⇒ 记忆块带一行；空队列 ⇒ 无该行"):
    reset()
    val empty = ContextRefresher.renderMemoryBlock(Some("- 记忆内容"), None, (false, false))
    assert(!empty.contains("Memory queue:"), "空队列不注入噪声行")
    enqueue("- 条目一")
    assertEquals(MemoryQueue.summaryLine().contains("Memory queue: 1 pending note(s)"), true)
    val withLine = ContextRefresher.renderMemoryBlock(
      Some("- 记忆内容"), None, (false, false), openTasksLine = "", memoryQueueLine = MemoryQueue.summaryLine())
    assert(withLine.contains("Memory queue: 1 pending note(s)"), s"注入行在场: $withLine")
    assert(withLine.contains("applied at the next compaction"), "口径：写入推迟到压缩")

end MemoryTrackSpec
