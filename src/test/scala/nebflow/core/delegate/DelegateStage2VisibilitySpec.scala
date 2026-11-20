package nebflow.core.delegate

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.SubAgentTaskStore
import nebflow.core.SessionStore
import nebflow.core.project.ChainsReadFace
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * delegate-lifecycle-reform **阶段② 红锚**（状态派生可见性替换存储式归档）：
 * 四条原先吃归档旗的行为腿 + 两窗可见性的**改前必红**读数。全部断言只落在
 * **基线已存在的公开面**上（编译零新增 API —— 红是**行为红**，不是编译红）。
 *
 * 逐条对应作者令（文件尾注「怎么读本 spec」）：
 *  - **R1 崩溃恢复跳过**：身份由**状态**收口（轮次已结算、归档旗从未写过）⇒ boot 对账
 *    的 fingerprint 回溯腿**零触碰**（改前：无旗 ⇒ 照打「重启打断」旗）。
 *  - **R2 复活拒绝**：同一收口身份 ⇒ 复活资格拒绝（改前：无旗 ⇒ 判可复活 = 诈尸）。
 *  - **R3 唤醒候选**：冻结记录即便带**存量归档旗**也必须是唤醒候选（改前：旗在场 ⇒
 *    被静默排除在唤醒清单外）。
 *  - **R4 七天 TTL**：仍带在飞子项、或收口未满七天的身份**绝不清理**（改前：拿派发刻
 *    `createdAt` 冒充终态刻 ⇒ 清理还活着的身份）。
 *  - **R5 可见性**：带存档旗但**未收口**（仍有在飞子项）的行必须留在**活动窗**
 *    （改前：只看旗 ⇒ 未完工作在历史窗里消失）。
 *
 * 怎么读本 spec：`sbt -batch "testOnly nebflow.core.delegate.DelegateStage2VisibilitySpec"`
 * 在**实现前**必须红（断言与上面逐条相反），在**实现后**必须全绿；两轮日志见
 * `.stand/tmp/delegate-lifecycle-reform/evidence/`。
 */
class DelegateStage2VisibilitySpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-stage2-visibility"
  private val originalRoot = PathUtil.dataRoot

  private val now: Long = System.currentTimeMillis()
  private val Day: Long = 24L * 3600L * 1000L

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")
    os.remove.all(tempRoot / "sessions")
    os.makeDir.all(tempRoot)
    ()

  // ── 夹具 ────────────────────────────────────────────────────────────────

  private def putRecord(
    id: String,
    parent: String = "root",
    createdAt: Long = now - 120_000L,
    archivedAt: Option[Long] = None,
    taskId: Option[String] = None
  ): Unit =
    os.makeDir.all(tempRoot / "general") // 座位存在（复活资格闸的 seat 判据）
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = (tempRoot / "general").toString,
        project = None,
        taskId = taskId,
        parentSessionId = parent,
        title = s"stage2-$id",
        createdAt = createdAt,
        archivedAt = archivedAt
      )
    )

  end putRecord

  /** 轮次台账直接落盘（append-only JSONL 的盘上形态；read face = DelegateRoundLedger）。 */
  private def seedRound(id: String, roundNo: Int, settledAt: Option[Long]): Unit =
    val dir = tempRoot / "delegates"
    os.makeDir.all(dir)
    val row = io.circe.Json.obj(
      "kind" -> "round".asJson,
      "roundNo" -> roundNo.asJson,
      "roundId" -> s"r$roundNo".asJson,
      "startedAt" -> (now - 10L * Day).asJson,
      "cause" -> "spawn".asJson,
      "settledAt" -> settledAt.asJson
    )
    os.write.append(dir / s"$id.rounds.jsonl", row.noSpaces + "\n", createFolders = true)

  /** 子项档直接落盘（`<dataRoot>/subagent-tasks/<scope>.json` 的盘上形态）。 */
  private def seedChild(scope: String, childId: String, status: String): Unit =
    val dir = tempRoot / "subagent-tasks"
    os.makeDir.all(dir)
    val rows = io.circe.Json.arr(
      io.circe.Json.obj(
        "taskId" -> childId.asJson,
        "parentSessionId" -> scope.asJson,
        "agentName" -> "nebflow".asJson,
        "prompt" -> "child work".asJson,
        "description" -> s"child-$childId".asJson,
        "status" -> status.asJson,
        "retryCount" -> 0.asJson,
        "spawnedAt" -> (now - 60_000L).asJson,
        "source" -> "subagent".asJson
      )
    )
    os.write.over(dir / s"$scope.json", rows.noSpaces)

  end seedChild

  private def mkTask(id: String, parent: String, status: String, lastError: Option[String] = None): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"stage2-$id",
      status = status,
      retryCount = 0,
      spawnedAt = now - 60_000L,
      completedAt = Some(now - 30_000L),
      lastError = lastError,
      source = "delegate"
    )

  private def recordOf(id: String): DelegateRegistry.Record =
    DelegateRegistry.find(id).getOrElse(sys.error(s"record '$id' missing"))

  // ── R1 崩溃恢复跳过（boot 对账指纹回溯腿）────────────────────────────────

  test(
    "R1 red: a STATE-settled identity (settled round, no archive flag ever written) is skipped by the boot fingerprint backfill"
  ) {
    val id = "delegate-nebflow-s2r1skip01"
    val fp = nebflow.agent.SubAgentStartupRecovery.RestartLossFingerprint
    val store = new SubAgentTaskStore(tempRoot / "subagent-tasks")
    os.makeDir.all(tempRoot / "sessions")
    val sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

    putRecord(id, parent = "p-r1")
    seedRound(id, roundNo = 1, settledAt = Some(now - 2L * Day)) // ← 状态派生的收口证据
    store
      .saveTasks("p-r1", List(mkTask(id, "p-r1", status = "failed", lastError = Some(s"$fp — salvaged"))))
      .unsafeRunSync()

    nebflow.agent.SubAgentStartupRecovery.recoverOrphans(store, sessionStore).unsafeRunSync()

    val rec = recordOf(id)
    assertEquals(
      rec.restartInterruptedAt,
      None,
      "a settled identity (settled round) must be skipped by the restart-interrupted backfill " +
        "(the retired archive flag used to be the evidence)"
    )
    assertEquals(rec.archivedAt, None, "no storage archive flag may be written (state-derived visibility only)")
    assert(os.exists(tempRoot / "delegates" / s"$id.json"), "zero deletion: the record file stays on disk")
  }

  test("R1 control: an identity with NO settlement evidence is still flagged (the skip is not a blanket no-op)") {
    val id = "delegate-nebflow-s2r1skip02"
    val fp = nebflow.agent.SubAgentStartupRecovery.RestartLossFingerprint
    val store = new SubAgentTaskStore(tempRoot / "subagent-tasks")
    os.makeDir.all(tempRoot / "sessions")
    val sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

    putRecord(id, parent = "p-r1b")
    store
      .saveTasks("p-r1b", List(mkTask(id, "p-r1b", status = "failed", lastError = Some(s"$fp — salvaged"))))
      .unsafeRunSync()

    nebflow.agent.SubAgentStartupRecovery.recoverOrphans(store, sessionStore).unsafeRunSync()

    assert(recordOf(id).restartInterruptedAt.isDefined, "open identity (no settlement evidence) still gets flagged")
  }

  // ── R2 复活拒绝（资格闸）──────────────────────────────────────────────────

  test("R2 red: a STATE-settled identity (settled round, no archive flag) is refused by the revival eligibility gate") {
    sys.props.remove("nebflow.delegate.revival.enabled")
    val id = "delegate-nebflow-s2r2revive1"
    val parent = "p-r2"
    os.makeDir.all(tempRoot / "sessions")
    val sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

    putRecord(id, parent = parent)
    seedRound(id, roundNo = 1, settledAt = Some(now - 2L * Day)) // ← 状态派生的收口证据
    // 父会话记录在场（SessionLiveness 记录面：索引 ∪ 盘上会话记录）。
    os.write(tempRoot / "sessions" / s"$parent.json", "{}")

    val task = mkTask(id, parent, status = "failed")
    val verdict = nebflow.agent.DelegateRevival.eligibility(sessionStore, task).unsafeRunSync()

    assert(
      verdict.exists(_.contains("settled")),
      s"a state-settled identity must be refused revival (no unfinished business); got $verdict"
    )
  }

  test("R2 control: an identity with no settlement evidence stays revivable (the gate is not a blanket refusal)") {
    sys.props.remove("nebflow.delegate.revival.enabled")
    val id = "delegate-nebflow-s2r2revive2"
    val parent = "p-r2b"
    os.makeDir.all(tempRoot / "sessions")
    val sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks")

    putRecord(id, parent = parent)
    os.write(tempRoot / "sessions" / s"$parent.json", "{}")

    val task = mkTask(id, parent, status = "failed")
    val verdict = nebflow.agent.DelegateRevival.eligibility(sessionStore, task).unsafeRunSync()
    assertEquals(verdict, None, s"an open identity is still revivable; got $verdict")
  }

  // ── R3 唤醒候选（冻结清单）────────────────────────────────────────────────

  test("R3 red: a frozen record carrying the legacy archive flag is STILL a wake candidate") {
    val frozenSince = now - 3600_000L
    val rec = DelegateRegistry.Record(
      id = "delegate-nebflow-s2r3wake01",
      executor = "nebflow",
      cwd = (tempRoot / "general").toString,
      project = None,
      taskId = None,
      parentSessionId = "root",
      title = "stage2-wake",
      createdAt = now - 3L * 3600_000L,
      frozenSince = Some(frozenSince),
      frozenReason = Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)),
      archivedAt = Some(frozenSince) // ← 存量归档旗（旧世界的「已归档」）
    )
    val items = FreezeNotice.itemsOf(List(rec), now, windowMs = 0L)
    assertEquals(
      items.map(_.delegateId),
      List(rec.id),
      "the retired archive flag must not hide a frozen delegate from the wake face (state-derived visibility)"
    )
  }

  test("R3 control: a frozen record past the retention window is not a live wake candidate") {
    val rec = DelegateRegistry.Record(
      id = "delegate-nebflow-s2r3wake02",
      executor = "nebflow",
      cwd = (tempRoot / "general").toString,
      project = None,
      taskId = None,
      parentSessionId = "root",
      title = "stage2-wake-old",
      createdAt = now - 60L * Day,
      frozenSince = Some(now - (DelegateFreeze.retentionMs + 3600_000L)),
      frozenReason = Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure))
    )
    assertEquals(FreezeNotice.itemsOf(List(rec), now, windowMs = 0L), Nil, "past retention = no wake candidate")
  }

  // ── R4 七天 TTL（清理面）──────────────────────────────────────────────────

  test("R4 red: an ancient record with a LIVE CHILD ITEM is never TTL-expired") {
    val id = "delegate-nebflow-s2r4ttl001"
    putRecord(id, createdAt = now - 9L * Day)
    seedChild(id, "sub-1", status = "running") // ← 状态派生的「上还有活」
    val rec = recordOf(id)
    val verdict = DelegateTerminalTtl.verdict(rec, None, now)
    assertEquals(
      verdict,
      DelegateTerminalTtl.InFlight: DelegateTerminalTtl.Verdict,
      "a record whose own child item is still in flight must never be cleaned (author's 'no child items' clause)"
    )
  }

  test("R4 red: the TTL anchor is the state-derived settlement instant, not the spawn time") {
    val id = "delegate-nebflow-s2r4ttl002"
    putRecord(id, createdAt = now - 40L * Day) // 远古派发刻（旧锚）
    seedRound(id, roundNo = 1, settledAt = Some(now - 3L * Day)) // 三天前才收口（新锚）
    val rec = recordOf(id)
    val verdict = DelegateTerminalTtl.verdict(rec, None, now)
    assertEquals(
      verdict,
      DelegateTerminalTtl.WithinTtl(now - 3L * Day, "settled-state"): DelegateTerminalTtl.Verdict,
      "the cleanup clock starts at the settled-state instant, not at the spawn timestamp"
    )
  }

  test("R4 control: a record settled 9 days ago (no open work) still expires — the cleanup purpose survives") {
    val id = "delegate-nebflow-s2r4ttl003"
    putRecord(id, createdAt = now - 40L * Day)
    seedRound(id, roundNo = 1, settledAt = Some(now - 9L * Day))
    val rec = recordOf(id)
    val verdict = DelegateTerminalTtl.verdict(rec, None, now)
    assert(verdict.isExpired, s"settled 9d ago ⇒ expired; got $verdict")
  }

  // ── R5 可见性（两窗）──────────────────────────────────────────────────────

  test("R5 red: a legacy-archived row with a LIVE child item stays in the ACTIVE window (open work is never hidden)") {
    val id = "delegate-nebflow-s2r5vis001"
    putRecord(id, archivedAt = Some(now - 3600_000L))
    seedChild(id, "sub-2", status = "running")

    val active = ChainsReadFace
      .rowsJson(Map.empty, archived = false, taskStatuses = Map.empty, taskRows = Nil)
      .unsafeRunSync()
    val history = ChainsReadFace
      .rowsJson(Map.empty, archived = true, taskStatuses = Map.empty, taskRows = Nil)
      .unsafeRunSync()

    val activeIds = idsOf(active)
    val historyIds = idsOf(history)
    assert(
      activeIds.contains(id),
      s"unfinished work must stay visible in the active window; active=$activeIds history=$historyIds"
    )
    assert(!historyIds.contains(id), s"a not-settled identity is not a history row; got $historyIds")
  }

  test("R5 control: a legacy-archived SETTLED row stays in the history window (zero panel churn for on-disk records)") {
    val id = "delegate-nebflow-s2r5vis002"
    putRecord(id, archivedAt = Some(now - 3600_000L))
    seedRound(id, roundNo = 1, settledAt = Some(now - 2L * Day))
    val active = ChainsReadFace
      .rowsJson(Map.empty, archived = false, taskStatuses = Map.empty, taskRows = Nil)
      .unsafeRunSync()
    val history = ChainsReadFace
      .rowsJson(Map.empty, archived = true, taskStatuses = Map.empty, taskRows = Nil)
      .unsafeRunSync()
    assert(!idsOf(active).contains(id), s"settled + legacy-archived ⇒ history window; active=${idsOf(active)}")
    assert(idsOf(history).contains(id), s"settled + legacy-archived ⇒ history window; history=${idsOf(history)}")
  }

  private def idsOf(rows: io.circe.Json): List[String] =
    rows.hcursor
      .downField("rows")
      .as[List[io.circe.Json]]
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("id").toOption)

end DelegateStage2VisibilitySpec
