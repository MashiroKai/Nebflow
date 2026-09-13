package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.tools.{MemoryHistory, MemoryQueue}
import nebflow.service.MemoryStore

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * C 批（2026-09-13）三条修法的**走真路径** spec：④ 超时对账 + ① 写前台账 + ⑥ 简报补桶。
 *
 * 判据真源：`~/.nebflow/docs/Nebflow/20260913_185524_memory-track-timeout-forensics-plan__chain-n-95e0aa0d.md`
 * §4（④/①/⑥ 的改动面与判红）+ §3.2（重复行反例）。
 *
 * **为什么能走整条 `run`**：`MemoryTrack.run` 的 Timeout 分支此前只能靠「真起 agent + 硬顶到点」
 * 触达（600s × 真 LLM），故既有 spec 只覆盖到 Refused 闸。本 spec 用
 * [[HangLibrary]]（`AgentLibrary.get` = `IO.never`）把**被测的挂点**（`attemptRun` 的定时区）
 * 变成确定性挂起，配合 `-Dnebflow.memory.track.hardTimeoutMs=<小值>` ⇒ **确定性 Timeout**，
 * 且不需要 LLM / actor system / spawn。于是 ④①⑥ 三条都在**生产的 `run` 路径**上被断言
 * （变异验红时改的就是生产代码本身，不是缝合面）。
 *
 * dataRoot 经 `PathUtil.setDataRoot` 重定向（MemoryTrackSpec 先例）；全程只碰临时 home。
 */
class MemoryTrackReconcileSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memtrack-reconcile"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def reset(): Unit =
    os.remove.all(home / "memory")
    os.remove.all(home / "User.md")
    os.remove.all(home / "memory-backups")
    MemoryStore.invalidateUserCache()
    MemoryStore.invalidateAgentCache("Nebula")
    MemoryTrackSignal.resetForTest()

  private val existingLine = "- 已存在的条目"

  private def writeUserFile(body: String): String =
    os.write.over(MemoryStore.userMemoryPath, body, createFolders = true)
    os.read(MemoryStore.userMemoryPath)

  private def enqueueAppend(content: String): String =
    MemoryQueue.enqueue("user", "append", None, None, Some(content), Some("s"), MemoryQueue.TriggerManual, "Nebula").toOption.get.id

  private def enqueueRemove(matchText: String): String =
    MemoryQueue.enqueue("user", "remove", None, Some(matchText), None, Some("s"), MemoryQueue.TriggerManual, "Nebula").toOption.get.id

  /** 消费链「挂住」夹具：`AgentLibrary.get` 永不完成 ⇒ `IO.timeoutTo` 到点 ⇒ 确定性 Timeout。
    * 只覆盖被测挂点，不碰 LLM / actor system / spawn（那三者与本批判据无关）。 */
  private class HangLibrary extends AgentLibrary(os.Path("/nonexistent-agents-for-reconcile-spec")):
    override def get(name: String): IO[Option[AgentDef]] = IO.never

  /** 最小 SharedResources 夹具（形态沿用 ToolPhaseStuckAxisSpec / MemoryTrackSpec 的 null 夹具；
    * 只有 `agentLibrary` 被本 spec 替换成 [[HangLibrary]]）。 */
  private def mkResources(agentLibrary: AgentLibrary): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, nebflow.llm.ThinkingConfig](nebflow.llm.ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 0,
      agentLibrary = agentLibrary,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null.asInstanceOf[nebflow.llm.ProviderHealthMonitor],
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      agentRegistry = cats.effect.Ref.unsafe[IO, Map[String, nebflow.agent.AgentRecord]](Map.empty)
    )

  /** 硬顶 prop 临时压小（`def` 读 ⇒ 当场生效），跑完还原。 */
  private def withHardTimeout[A](ms: Long)(body: => A): A =
    val key  = "nebflow.memory.track.hardTimeoutMs"
    val prev = sys.props.get(key)
    try
      sys.props.update(key, ms.toString)
      body
    finally
      prev match
        case Some(v) => sys.props.update(key, v)
        case None    => sys.props.remove(key)

  private def sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)).map("%02x".format(_)).mkString

  private val refRe = "q-\\d+-\\d+".r

  // ===== ④ 超时对账（核心）=====

  test("④ 超时先对账再降级（真 run 路径 ⇒ 确定性 Timeout）：3 条夹具跑出 deduped|applied-by-reconcile|timeout 齐全、pending 恰剩 1 条"):
    reset()
    val before = writeUserFile(s"# User\n\n## 节\n\n$existingLine\n")
    val idDup     = enqueueAppend(existingLine)        // 已落 append（文件里已有同一行）
    val idPending = enqueueAppend("- 全新条目")         // 未落 append ⇒ 留 pending
    val idGone    = enqueueRemove("- 已不存在的键")      // 已落 remove（定位键已消失）
    assertEquals(MemoryQueue.pendingCount(), 3)

    withHardTimeout(50) {
      val r = MemoryTrack.run(mkResources(new HangLibrary), parentSessionId = Some("s"), parentDepth = 0).unsafeRunSync()
      assertEquals(r.status, MemoryTrack.Status.Timeout, s"必须走到 Timeout 分支: $r")
      assertEquals(r.pendingAtStart, 3, "起跑 3 条")

      val st = MemoryQueue.readState()
      assertEquals(
        st.outcomes.map(_.result).toSet,
        Set(MemoryQueue.ResultDeduped, MemoryQueue.ResultAppliedByReconcile, MemoryQueue.ResultTimeout),
        s"三支齐全（对账终态两支 + 照旧 timeout）: ${st.outcomes.map(o => o.ref -> o.result)}")
      assertEquals(st.outcomes.find(_.ref == idDup).map(_.result), Some(MemoryQueue.ResultDeduped), "已落 append ⇒ deduped")
      assertEquals(st.outcomes.find(_.ref == idGone).map(_.result), Some(MemoryQueue.ResultAppliedByReconcile), "已落 remove ⇒ applied-by-reconcile")
      assertEquals(st.outcomes.find(_.ref == idPending).map(_.result), Some(MemoryQueue.ResultTimeout), "未落 append ⇒ 照旧 timeout（留 pending 重试）")
      assertEquals(st.pending.map(_.id), Vector(idPending), "pending 恰剩 1 条且就是未落地那条")
      assertEquals(r.reconciled, 2, "Result 报出对账闭合条数（生命周期事件可读）")
      assertEquals(r.outcomesWritten, 3, "对账终态 2 + timeout 1")
      assert(!st.outcomes.exists(_.result == MemoryQueue.ResultRejected), "infra 路径零 rejected（作者令不变）")
      // 作者纪律（写前固化 + 写后只对账）：生产路径上的显式断言 —— run_set == expected
      assertEquals(r.reconcileDrift, 0, "写前固化集 == 写后实际写入集（漂移恒 0；非 0 会响亮 WARN 并上报 Result）")

      // U4 裁定落地：引擎侧对账闭合**必须**落 history:consume 行（与 outcome 一一对账）
      val consumeRefs = MemoryHistory.ofKind(MemoryHistory.KindConsume).flatMap(_.ref).toSet
      assertEquals(consumeRefs, st.outcomes.map(_.ref).toSet, "每条 outcome 都有对应 consume 行（含对账终态）")
      assertEquals(
        MemoryHistory.discrepancies(st.notes.map(_.id).toList, st.outcomes.map(_.ref).toList),
        Nil,
        "队列 note / outcome / history:consume 三者零缺口（含 reconcile 闭合）")
      val byReconcileRows = MemoryHistory
        .ofKind(MemoryHistory.KindConsume)
        .filter(_.result.contains(MemoryQueue.ResultAppliedByReconcile))
      assertEquals(byReconcileRows.map(_.ref.getOrElse("")).sorted, Vector(idGone), "对账终态在变更史里可对账")
      assert(byReconcileRows.forall(_.detail.getOrElse("").startsWith(MemoryQueue.ReconcileDetailPrefix)), "变更史 detail 同源前缀")
      assert(byReconcileRows.forall(e => e.actor == MemoryTrack.AgentName && e.by.contains(MemoryTrack.AgentName)), "变更史 actor/by 同源")

      // 作者硬约束 (i)：审计一眼分清「谁判的」——独立字样 + detail 前缀 + by
      val engineJudged = st.outcomes.filter(o => o.result == MemoryQueue.ResultDeduped || o.result == MemoryQueue.ResultAppliedByReconcile)
      assertEquals(engineJudged.map(_.ref).sorted, Vector(idDup, idGone).sorted)
      engineJudged.foreach { o =>
        assertEquals(o.by, MemoryTrack.AgentName)
        assert(o.detail.startsWith(MemoryQueue.ReconcileDetailPrefix), s"detail 前缀是审计锚: ${o.detail}")
      }
      // 记忆文件零改动（消费链挂住 ⇒ 没人动过文件；对账是只读判定）
      assertEquals(os.read(MemoryStore.userMemoryPath), before)
      // 重试引线仍置位（还有 1 条 pending）
      assert(MemoryTrackSignal.peek().isDefined, "剩余 pending ⇒ 置位重试引线")
      assertEquals(MemoryQueue.summaryLine().contains("Memory queue: 1 pending note(s)"), true)
    }

  test("④ 对账只在超时路径生效（失败分支收到报告也不标终态）+ 全部闭合后不再置位重试引线"):
    reset()
    val before = writeUserFile(s"# User\n\n## 节\n\n$existingLine\n")
    val id     = enqueueAppend(existingLine)
    val st0    = MemoryQueue.readState()
    val report = MemoryQueue.reconcile(st0, Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, before)))
    assertEquals(report.count, 1, "夹具：该条的效果已在盘上")

    val written = MemoryTrack.degradeOutcomes(isTimeout = false, detail = "boom", reconciled = report).unsafeRunSync()
    assertEquals(written, 1)
    assertEquals(
      MemoryQueue.readState().lastOutcomeByRef.get(id).map(_.result),
      Some(MemoryQueue.ResultNotRun),
      "失败分支（本轮没动过文件）不得替消费者下裁决 ⇒ 仍写 notrun")

    reset()
    writeUserFile(s"# User\n\n## 节\n\n$existingLine\n")
    val id2     = enqueueAppend(existingLine)
    val report2 = MemoryQueue.reconcile(
      MemoryQueue.readState(),
      Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, os.read(MemoryStore.userMemoryPath))))
    assertEquals(MemoryTrack.degradeOutcomes(isTimeout = true, detail = "hard timeout", reconciled = report2).unsafeRunSync(), 1)
    assertEquals(MemoryQueue.readState().lastOutcomeByRef.get(id2).map(_.result), Some(MemoryQueue.ResultDeduped))
    assertEquals(MemoryQueue.pendingCount(), 0, "该闭合的闭合完 ⇒ pending 清零")
    assertEquals(MemoryTrackSignal.peek(), None, "无剩余 pending ⇒ 不置位（不制造空转重试）")

  // ===== ① 写前台账 =====

  test("① 写前台账：spawn 之前已落**一条聚合行** —— 含全部 authorized ref + 每目标起始 sha256/bytes（与 gate-3 快照档案逐字一致）"):
    reset()
    val before = writeUserFile(s"# User\n\n## 节\n\n$existingLine\n")
    enqueueAppend(existingLine)
    enqueueAppend("- 全新条目")

    // 跑前基准：spec 侧独立复算计划（与引擎同源同输入）
    val preState = MemoryQueue.readState()
    val plan = MemoryQueue.plan(
      preState,
      Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, before)))

    withHardTimeout(50) {
      val r = MemoryTrack.run(mkResources(new HangLibrary), parentSessionId = Some("s"), parentDepth = 0).unsafeRunSync()
      assertEquals(r.status, MemoryTrack.Status.Timeout, s"$r")

      val ledgerLines = os.read(MemoryHistory.historyPath).linesIterator.toVector.filter(_.contains("\"preflight\""))
      assertEquals(ledgerLines.size, 1, s"整轮恰一条聚合行（禁逐条 273 行）: ${ledgerLines.size}")
      val j = parse(ledgerLines.head).toOption.get
      val hc = j.hcursor
      assertEquals(hc.get[String]("kind").toOption, Some(MemoryHistory.KindPreflight))
      assertEquals(hc.get[String]("actor").toOption, Some(MemoryTrack.AgentName))

      val refs = hc.get[List[String]]("refs").toOption.get
      assertEquals(refs.toSet, plan.authorized.toSet, "台账含**全部** authorized ref")
      assertEquals(hc.get[Int]("authorized").toOption, Some(plan.authorized.size))
      assertEquals(hc.get[Int]("pending").toOption, Some(preState.pendingCount))

      val targets = hc.downField("targets").as[List[Json]].toOption.get
      val userRow = targets.find(_.hcursor.get[String]("label").toOption.contains("user")).get
      val ledgerSha   = userRow.hcursor.get[String]("sha256").toOption.get
      val ledgerBytes = userRow.hcursor.get[Long]("bytes").toOption.get
      assertEquals(userRow.hcursor.get[String]("path").toOption, Some(MemoryStore.userMemoryPath.toString))
      assertEquals(ledgerBytes, before.getBytes(UTF_8).length.toLong, "起始 bytes = 起跑线字节")
      assertEquals(ledgerSha, sha256Hex(before), "起始 sha 独立复算一致")

      // 与 gate-3 快照档案（独立来源：MemorySnapshot 读的是盘上字节）逐字一致
      val snapshotDir = os.list(home / "memory-backups").filter(d => os.exists(d / "SNAPSHOT-SHA256.txt")).last
      val table       = os.read(snapshotDir / "SNAPSHOT-SHA256.txt")
      val snapRow     = table.linesIterator.find(_.endsWith(MemoryStore.userMemoryPath.toString)).get
      val parts       = snapRow.split("\\s+")
      assertEquals(ledgerSha, parts(0), "台账起始 sha 与快照档案 sha 一致")
      assertEquals(ledgerBytes.toString, parts(1), "台账起始 bytes 与快照档案一致")
      assertEquals(hc.downField("snapshot").get[String]("dir").toOption, Some(snapshotDir.toString), "台账与快照目录互指")
      assertEquals(hc.downField("snapshot").get[String]("label").toOption, Some("pre-consolidation-track"))

      // 顺序：台账早于本轮 outcome（「超时后先对账再降级」的记账基准已在手）
      val firstOutcomeAt = MemoryQueue.readState().outcomes.map(_.atMs).min
      assert(hc.get[Long]("atMs").toOption.get <= firstOutcomeAt, "台账 atMs ≤ 首条 outcome atMs")
    }

  // ===== ⑥ 简报补桶 =====

  test("⑥ 简报补 already-present 桶：一行 ref 清单，ref 集合 == plan 的 already-present 桶；无该桶则不出现（零常驻噪声）"):
    reset()
    writeUserFile(s"# User\n\n## 节\n\n$existingLine\n")
    val idDup = enqueueAppend(existingLine)
    val idNew = enqueueAppend("- 全新条目")
    val st    = MemoryQueue.readState()
    val plan = MemoryQueue.plan(
      st,
      Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, os.read(MemoryStore.userMemoryPath))))

    val bucket = plan.items
      .filter(i => i.bucket == MemoryQueue.Bucket.WouldObsolete && i.detail.startsWith("already-present"))
      .map(_.ref)
    assertEquals(bucket, Vector(idDup), s"plan 侧 already-present 桶: ${plan.items.map(i => i.ref -> i.detail)}")

    val text = MemoryTrack.brief("/tmp/wr-reconcile", MemoryQueue.TriggerManual, st.pending, plan)
    assert(text.contains("already-present"), s"简报必须带该行: $text")
    val line = text.linesIterator.find(_.contains("already-present")).get
    assertEquals(refRe.findAllIn(line).toSet, bucket.toSet, s"该行的 ref 集合 == plan 桶: $line")
    assert(line.contains("deduped"), s"且给出消费者该写的裁决: $line")

    // 无该桶 ⇒ 不出行（简报不引入常驻噪声）
    val cleanPlan = MemoryQueue.plan(
      st,
      Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, "# User\n")))
    assert(cleanPlan.items.forall(i => !i.detail.startsWith("already-present")))
    val cleanText = MemoryTrack.brief("/tmp/wr-reconcile", MemoryQueue.TriggerManual, st.pending, cleanPlan)
    assert(!cleanText.contains("already-present"), s"无重复桶则无该行: $cleanText")
    assert(cleanText.contains(idDup), "但授权 ref 清单照旧带全部条目")

  // ===== 作者纪律（2026-09-13 立册）：禁从写后状态反推写入集 =====

  test("纪律落地点：判据集写前固化、写后只对账 —— 冻结集里的条目若在写时已闭合 ⇒ drift 非空并响亮上报（不静默重推）"):
    // 纯函数面：两个方向都报
    assertEquals(MemoryTrack.writeSetDrift(Set("q-a", "q-b"), Set("q-b")).size, 1, "冻结却没写成 ⇒ 报")
    assertEquals(MemoryTrack.writeSetDrift(Set("q-b"), Set("q-a", "q-b")).size, 1, "写成但不在冻结集 ⇒ 报（按构造恒空，仍显式报出）")
    assertEquals(MemoryTrack.writeSetDrift(Set("q-a"), Set("q-a")), Nil, "相等 ⇒ 空（run_set == expected）")

    reset()
    val body = s"# User\n\n## 节\n\n$existingLine\n- 第二条已落\n"
    writeUserFile(body)
    val idA = enqueueAppend(existingLine)
    val idB = enqueueAppend("- 第二条已落")
    val frozen = MemoryQueue.reconcile(
      MemoryQueue.readState(),
      Map("user" -> MemoryQueue.TargetFile(MemoryStore.userMemoryPath.toString, body)))
    assertEquals(frozen.refs, Set(idA, idB), "**写前**固化判据集 = 2 条（判定那一刻的读数）")

    // 模拟「判定之后、写入之前」被消费者抢先闭合一条 ⇒ 这是 frozen != written 的唯一来源
    assert(MemoryQueue.recordOutcome(idA, MemoryQueue.ResultApplied, "memory-consolidator", "consumer won the race").isRight)
    val rep = MemoryTrack.degradeOutcomesReport(isTimeout = true, detail = "hard timeout", reconciled = frozen).unsafeRunSync()

    assertEquals(rep.judgedWritten, List(idB), "只写写时仍 pending 的那条（**不从写后状态反推**写入集）")
    assertEquals(rep.written, 1, "本轮实际写入 1 条")
    assertEquals(rep.drift.size, 1, "差集被上报（frozen 2 vs written 1）")
    assert(rep.drift.head.startsWith(idA), s"差集点名为抢先闭合的那条: ${rep.drift}")
    assertEquals(
      MemoryQueue.readState().lastOutcomeByRef.get(idA).map(_.result),
      Some(MemoryQueue.ResultApplied),
      "抢先闭合者不被动过（引擎不覆盖既有终态）")

end MemoryTrackReconcileSpec
