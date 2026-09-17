package nebflow.agent

import cats.effect.{IO, Ref}
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

  /** 最小 SharedResources 夹具（本 spec 只走到「前置闸拒绝」路径 ⇒ 全程不触资源位；
    * 形态沿用 ToolPhaseStuckAxisSpec 的同款 null 夹具）。 */
  private def mkResources(): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, nebflow.llm.ThinkingConfig](nebflow.llm.ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 0,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null.asInstanceOf[nebflow.llm.ProviderHealthMonitor],
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false),
      agentRegistry = Ref.unsafe[IO, Map[String, nebflow.agent.AgentRecord]](Map.empty)
    )

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

  // ===== 前置闸（fail-closed，2026-09-13 缺失自愈批）=====

  test("run 级 fail-closed：计划超硬顶（授权集空）⇒ Status.Refused + 零 spawn / 零结局写 / 零快照 / 条目留 pending + 告警"):
    reset()
    // 造一份「再 append 一条必超硬顶」的 User.md，并排一条 append
    val hard = MemoryBudget.UserHardBytes
    os.write.over(MemoryStore.userMemoryPath, "# U\n\n## 节\n\n- " + ("y" * (hard - 40).toInt) + "\n", createFolders = true)
    val before = os.read(MemoryStore.userMemoryPath)
    enqueue("- " + ("z" * 30))
    val queueBefore = os.read(MemoryQueue.queuePath)
    val backupDirsBefore = if os.exists(home / "memory-backups") then os.list(home / "memory-backups").size else 0

    val r = MemoryTrack.run(mkResources(), parentSessionId = Some("s"), parentDepth = 0).unsafeRunSync()

    assertEquals(r.status, MemoryTrack.Status.Refused, s"超预算 ⇒ 拒绝落地: $r")
    assert(r.detail.contains("REFUSED"), s"拒绝原因必须写明: ${r.detail}")
    assert(r.alert.isDefined, "必须带告警（前端 + 生命周期日志）")
    assert(r.alert.exists(_.contains("NOT being consumed")), s"告警文案: ${r.alert}")
    assertEquals(r.outcomesWritten, 0, "零结局写（infra 失败不得记 rejected）")
    assertEquals(os.read(MemoryQueue.queuePath), queueBefore, "队列逐字未变")
    assertEquals(os.read(MemoryStore.userMemoryPath), before, "记忆文件零写入")
    assertEquals(MemoryQueue.pendingCount(), 1, "条目保持 pending（超硬顶即停、剩余留 pending）")
    val backupDirsAfter = if os.exists(home / "memory-backups") then os.list(home / "memory-backups").size else 0
    assertEquals(backupDirsAfter, backupDirsBefore, "预算闸先于快照闸 ⇒ 拒绝时零快照副作用")
    assert(MemoryTrackSignal.peek().isDefined, "重试引线置位（下轮压缩重试）")

  test("run 级：dryRun 模式与阈值 prop 化同规（-Dnebflow.memory.track.dryRun）"):
    val key = "nebflow.memory.track.dryRun"
    try
      assertEquals(MemoryTrack.dryRunMode, false)
      sys.props.update(key, "true")
      assertEquals(MemoryTrack.dryRunMode, true)
      sys.props.update(key, "1")
      assertEquals(MemoryTrack.dryRunMode, true)
    finally sys.props.remove(key)
    assertEquals(MemoryTrack.dryRunMode, false)

  // ===== 降级（失败 / 超时同一路径）=====

  test("降级（infra 失败）：写 outcome(notrun) 而非 rejected，条目保持 pending，重试引线置位 + 注入行 ALERT"):
    reset()
    val ids = List(enqueue("- 条目一"), enqueue("- 条目二"))
    assertEquals(MemoryQueue.pendingCount(), 2)
    val written = MemoryTrack.degradeOutcomes(isTimeout = false, detail = "boom").unsafeRunSync()
    assertEquals(written, 2)
    val st = MemoryQueue.readState()
    assertEquals(st.outcomes.map(_.result).distinct, Vector(MemoryQueue.ResultNotRun), "infra 失败写 notrun")
    assert(!st.outcomes.exists(_.result == MemoryQueue.ResultRejected), "**零 rejected**（作者令：rejected 只许消费者写）")
    assertEquals(st.outcomes.map(_.by).distinct, Vector(MemoryTrack.AgentName))
    assertEquals(st.pendingCount, 2, "条目仍是 pending（notrun 可重试 ⇒ 重试引线真的活）")
    assertEquals(st.notes.size, 2, "note 行保留（append-only：队列条目不被改写/删除）")
    assertEquals(ids, st.notes.map(_.id).toList)
    assert(MemoryTrackSignal.peek().isDefined, "重试引线置位（否则失败一次即永久搁置）")
    assertEquals(MemoryHistory.stats().consumed, 2, "降级也落变更史 consume 行")
    val line = MemoryQueue.summaryLine()
    assert(line.contains("ALERT:"), s"注入行必须响亮（消费链没跑）: $line")
    assert(line.contains("never ran"), s"ALERT 说清「消费者没跑」而不是「判定不可落」: $line")
    assert(line.contains("oldest "), s"注入行报最老 pending 年龄: $line")

  test("降级（infra 上限）：达 MaxInfraOutcomesPerRef 后只补一条 blocked 然后闭嘴（条目仍 pending）"):
    reset()
    val id = enqueue("- 条目一")
    val results = (1 to MemoryQueue.MaxInfraOutcomesPerRef + 2).map { _ =>
      MemoryTrack.degradeOutcomes(isTimeout = false, detail = "boom").unsafeRunSync()
      MemoryQueue.readState().outcomes.last.result
    }.toList
    assertEquals(
      results,
      List.fill(MemoryQueue.MaxInfraOutcomesPerRef)(MemoryQueue.ResultNotRun) :+ MemoryQueue.ResultBlocked :+ MemoryQueue.ResultBlocked,
      "前 N 轮 notrun，随后 blocked（写一次后不再追加）")
    val st = MemoryQueue.readState()
    val infraWrites = st.outcomes.count(o => o.ref == id && MemoryQueue.EngineInfraResults.contains(o.result))
    assertEquals(infraWrites, MemoryQueue.MaxInfraOutcomesPerRef + 1, "infra 结局行数有界（防队列膨胀）")
    assertEquals(st.pendingCount, 1, "blocked 不影响重试性：条目仍 pending")
    assert(MemoryQueue.summaryLine().contains("blocked×1"), s"注入行报 blocked 计数: ${MemoryQueue.summaryLine()}")

  test("降级（超时）：outcome(timeout) + 条目仍 pending；已有终态结局的条目不动"):
    reset()
    val id1 = enqueue("- 条目一")
    val id2 = enqueue("- 条目二")
    assert(MemoryQueue.recordOutcome(id2, MemoryQueue.ResultApplied, "memory-consolidator", "已写入").isRight)
    val written = MemoryTrack.degradeOutcomes(isTimeout = true, detail = "hard timeout").unsafeRunSync()
    assertEquals(written, 1, "只对未闭合条目补写")
    val st = MemoryQueue.readState()
    assertEquals(st.outcomes.find(_.ref == id2).map(_.result), Some(MemoryQueue.ResultApplied), "已落地结局未被覆盖")
    assertEquals(st.outcomes.find(_.ref == id1).map(_.result), Some(MemoryQueue.ResultTimeout))
    assertEquals(st.pending.map(_.id), Vector(id1), "timeout 可重试 ⇒ 该条仍 pending（终态条目不在列）")
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

  test("NebulaMemoryHook：facts 入队（trigger=dream）且无落点节，User.md 零直写"):
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
    assert(st.notes.forall(_.section.isEmpty), "无具名节（`## Dream Extract` 已随 DreamMode 停用退役 ⇒ 恒文件尾追加）")
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
