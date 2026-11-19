package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.SessionStorePort
import nebflow.core.tools.{ScheduleTool, ToolContext}
import nebflow.shared.{PathUtil, SessionMeta, UiMessage}

import java.time.ZoneId
import scala.collection.mutable.ListBuffer

/**
 * #822 复活批（2026-10-29 作者令「判断现状和计划的差距，然后派发实施落实」）——
 * 本 spec 钉 WIP 残稿未覆盖的本批新增四面：
 *
 *  ❶ 调度丢失 bug（f2ef6ff1，2026-10-29 11:50 同毫秒双 create 同会话文件互相
 *     覆盖）——store 全库互斥后的并发语义：同会话并发 create 全存活、并发 upsert
 *     同名收敛单份、创建后立即可见、重启（fresh 实例）后仍在。
 *  ❷ 一次性错过补偿——停机跨过预定触发点的一次性任务恢复后补跑恰一次，注入
 *     带「错过后补跑」标注；运行中的正常晚点不打标注（回归）。
 *  ❸ 条件触发评估器接口——前缀分发（git-tip / task-terminal / sh -c 兜底）+
 *     git-tip 有状态哨兵端到端（建基线→不触发；tip 变化→触发+基线推进）+
 *     task-terminal 流程图终态哨兵端到端（端到端 spec，作者令钉）。
 *  ❹ 例程自检降频——连续空转达阈值自动降频挂「待审查」（list 可见）；有活动
 *     清零；无证据不奖不罚；「待审查」粘性不自动摘。
 */
class ScheduleRevivalSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-schedule-revival"
  PathUtil.setDataRoot(tempRoot)

  private val taskStore: ScheduledTaskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def reset(): IO[Unit] =
    SessionToolActivity.resetForSpec() *>
      IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot / "scheduled-tasks") }

  private val past = System.currentTimeMillis() - 60_000L
  private val future = System.currentTimeMillis() + 3_600_000L

  private val routed = ListBuffer.empty[(String, AgentCommand.ExternalEvent)]
  private val appendedUi = ListBuffer.empty[(String, List[UiMessage])]

  private def fakeSessionStore(
    metaIds: Set[String],
    activeId: String,
    uiMessages: List[UiMessage] = Nil
  ): SessionStorePort =
    new SessionStorePort:
      def getSessionMeta(id: String): IO[Option[SessionMeta]] =
        IO.pure(if metaIds.contains(id) then Some(SessionMeta(id, id, 0L, 0L, false)) else None)
      def getActiveId: IO[String] = IO.pure(activeId)
      def appendUiMessages(sessionId: String, msgs: List[UiMessage]): IO[Unit] =
        IO.delay { appendedUi += ((sessionId, msgs)) }.void
      def getFolderName(folderId: String): Option[String] = None
      def flushPendingUiWrites: IO[Unit] = IO.unit
      def flushPendingMessages: IO[Unit] = IO.unit
      def getUiMessages(sessionId: String, offset: Int, limit: Int): IO[(List[UiMessage], Int)] =
        IO.pure((uiMessages, uiMessages.size))

  private def mkService(
    metaIds: Set[String] = Set("s1"),
    activeId: String = "s1",
    bootAtMs: Long = System.currentTimeMillis() - 3_600_000L, // 默认：引擎已跑 1 小时
    uiMessages: List[UiMessage] = Nil
  ): ScheduledTaskService =
    routed.clear(); appendedUi.clear()
    new ScheduledTaskService(
      dispatcher = null,
      taskStore = taskStore,
      routeToAgent = (sid, event) => IO.delay { routed += ((sid, event)) }.void,
      broadcast = _ => IO.unit,
      sessionStore = fakeSessionStore(metaIds, activeId, uiMessages),
      bootAtMs = bootAtMs
    )

  private def events: List[AgentCommand.ExternalEvent] = routed.map(_._2).toList

  // ==================================================================
  // ❶ 调度丢失 bug（f2ef6ff1）：并发语义 + 创建后可见 + 重启后仍在
  // ==================================================================

  test("CONCURRENT creates on one session file all survive (f2ef6ff1 race — the exact incident shape)"):
    val incidentTasks = (1 to 24).map { i =>
      ScheduledTask.create("s-race", s"并发创建 $i", future, None, None, Some(s"race-$i"))
    }.toList
    for
      _ <- reset()
      // 事故形态：同一毫秒、两个线程各发 create 落同一会话文件（11:50:21.073
      // term-batch3-gate / term-batch2-gate）。此处 24 路并发挤压同一文件。
      fibers <- incidentTasks.map(t => taskStore.addTask(t).start).sequence
      _ <- fibers.traverse_(_.join)
      stored <- taskStore.loadTasks("s-race")
      // 创建后可见：addTask 返回即 list 面（getAllPendingTasks）必须含全部
      pending <- taskStore.getAllPendingTasks
    yield
      assertEquals(stored.map(_.id).sorted, incidentTasks.map(_.id).sorted,
        "no create may be silently clobbered by a concurrent write")
      assertEquals(pending.map(_.id).toSet, stored.map(_.id).toSet, "created => immediately visible in list")

  test("concurrent create + markTriggered + reschedule interleaving keeps every task row"):
    for
      _ <- reset()
      victim = ScheduledTask.create("s-race2", "别把我挤掉", future, None, None, None)
      _ <- taskStore.addTask(victim)
      later = (1 to 16).map(i => ScheduledTask.create("s-race2", s"后到者 $i", future)).toList
      fibers <- later.map(t => taskStore.addTask(t).start).sequence
      touch <- (1 to 8).toList.map(i => taskStore.markTriggered("s-race2", s"no-such-$i").start).sequence
      resched <- (1 to 4).toList.map(i => taskStore.rescheduleTask("s-race2", victim.id, future + i).start).sequence
      _ <- (fibers ++ touch ++ resched).traverse_(_.join)
      stored <- taskStore.loadTasks("s-race2")
    yield
      assert(stored.exists(_.id == victim.id), "pre-existing task must survive concurrent writes")
      assertEquals(stored.size, 17, "16 concurrent creates + 1 pre-existing, zero loss")

  test("concurrent same-name upserts converge to exactly ONE row (upsert is atomic)"):
    for
      _ <- reset()
      mk = (v: String) => ScheduledTask.create("s-up", s"内容 $v", future, None, None, Some("dup-key"))
      fibers <- List("a", "b", "c", "d").map(v => taskStore.upsertTaskByName(mk(v)).start).sequence
      _ <- fibers.traverse_(_.join)
      stored <- taskStore.loadTasks("s-up").map(_.filter(_.name.contains("dup-key")))
    yield assertEquals(stored.size, 1, "same-name upserts must not stack or erase each other")

  test("created task survives restart equivalence: fresh store instance re-reads it (spec pin)"):
    for
      _ <- reset()
      t = ScheduledTask.create("s-boot", "重启后仍在", future, None, None, Some("restart-pin"))
      _ <- taskStore.addTask(t)
      fresh = new ScheduledTaskStore(tempRoot / "scheduled-tasks")
      seen <- fresh.getAllPendingTasks
    yield assertEquals(seen.map(_.id), List(t.id), "创建后可见、重启后仍在")

  test("legacy file WITHOUT the new fields decodes fine (idleStreak/reviewFlag 缺键不炸——本批实修)"):
    // 裸 JSON：无 conditionState/idleStreak/reviewFlag/lastFiredAt 键（升级前
    // encoder 形态）。ConfiguredCodec+withDefaults 下缺键回默认；此前
    // deriveCodec 对非 Option 缺键硬失败 ⇒ 整文件解码 Nil ⇒ 存量任务静默消失。
    val legacy = Json.obj(
      "id" -> "legacy01".asJson,
      "sessionId" -> "s-legacy".asJson,
      "content" -> "升级前任务".asJson,
      "triggerAt" -> past.asJson,
      "createdAt" -> (past - 86_400_000L).asJson,
      "triggered" -> false.asJson,
      "triggeredAt" -> Json.Null,
      "referencePath" -> Json.Null,
      "repeat" -> "daily".asJson,
      "enabled" -> true.asJson,
      "name" -> "legacy-routine".asJson
    )
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(tempRoot / "scheduled-tasks" / "s-legacy.json", Json.arr(legacy).noSpaces)
      }
      stored <- taskStore.loadTasks("s-legacy")
      due <- taskStore.getAllDueTasks
    yield
      assertEquals(stored.map(_.id), List("legacy01"), "legacy row must decode, not vanish")
      assertEquals(stored.head.idleStreak, 0)
      assertEquals(stored.head.reviewFlag, false)
      assertEquals(stored.head.lastFiredAt, None)
      assertEquals(stored.head.conditionState, None)
      assertEquals(due.map(_.id), List("legacy01"), "legacy task still enters the due path")

  // ==================================================================
  // ❷ 一次性错过补偿：停机跨过预定点 ⇒ 补跑 + 标注；运行中晚点 ⇒ 不标注
  // ==================================================================

  test("one-shot whose trigger point was crossed by downtime fires once with 错过后补跑 marker"):
    for
      _ <- reset()
      // 引擎 2 分钟前才启动；预定触发点在 10 分钟前 ⇒ 必然被停机跨过
      t = ScheduledTask.create("s1", "停机跨过的任务", System.currentTimeMillis() - 10 * 60_000L)
      _ <- taskStore.addTask(t)
      svc = mkService(bootAtMs = System.currentTimeMillis() - 2 * 60_000L)
      _ <- svc.fireDueTasks
      stored <- taskStore.loadTasks("s1")
      _ <- svc.fireDueTasks // 第二拍不得补第二次
    yield
      assertEquals(events.size, 1, "missed one-shot fires exactly once")
      assert(events.head.payload.contains("错过后补跑"), s"marker required: ${events.head.payload}")
      assertEquals(stored, Nil, "consumed after the single make-up fire")

  test("regression: one-shot fired late while the engine was RUNNING gets NO missed marker"):
    for
      _ <- reset()
      t = ScheduledTask.create("s1", "正常运行中的晚点任务", System.currentTimeMillis() - 30_000L)
      _ <- taskStore.addTask(t)
      // 引擎 1 小时前启动，预定点在启动之后 ⇒ 只是 10s 轮询粒度的正常晚点
      _ <- mkService(bootAtMs = System.currentTimeMillis() - 3_600_000L).fireDueTasks
    yield assert(!events.head.payload.contains("补跑"), s"normal lateness must stay silent: ${events.head.payload}")

  // ==================================================================
  // ❸ 条件评估器：分发面 + git-tip 哨兵端到端 + task-terminal 哨兵端到端
  // ==================================================================

  test("dispatch: prefix routes to the right evaluator; bare command falls back to shell probe"):
    assertEquals(TaskConditionEvaluator.dispatch("git-tip:/repo#main"), GitTipChangeEvaluator)
    assertEquals(TaskConditionEvaluator.dispatch("task-terminal:p1:nodeA"), TaskTerminalEvaluator)
    assertEquals(TaskConditionEvaluator.dispatch("test -f /tmp/x && exit 0 || exit 1"), ShellProbeEvaluator)

  test("git-tip sentinel E2E: baseline first, fires ONLY on tip change, baseline advances"):
    val repo = os.temp.dir() / "sentinel-repo"
    val repoPath = repo.toString
    def git(args: String*): String =
      os.proc(Seq("git", "-C", repoPath) ++ args).call(check = false).out.text().trim
    for
      _ <- reset()
      _ <- IO.blocking {
        os.makeDir.all(repo)
        os.proc("git", "init", "-q", repoPath).call()
        os.write(repo / "a.txt", "v1")
        val commitArgs = Seq("-C", repoPath, "-c", "user.name=t", "-c", "user.email=t@t.io")
        os.proc(Seq("git") ++ commitArgs ++ Seq("add", ".")).call()
        os.proc(Seq("git") ++ commitArgs ++ Seq("commit", "-q", "-m", "v1")).call()
      }
      tip1 <- IO.blocking(git("rev-parse", "HEAD"))
      cond = s"git-tip:$repoPath"
      t = ScheduledTask.create("s1", "分支有新提交才叫", past, None, None, Some("git-tip-watch"), None, Some(cond))
      _ <- taskStore.addTask(t)
      // 第一拍：建基线——不注入，conditionState 落库
      _ <- mkService().fireDueTasks
      baseline <- taskStore.loadTasks("s1").map(_.head)
      baselineEvents = routed.map(_._2).toList // 快照：基线轮注入数
      // tip 变化后第二拍（把复查时间拉回到期）——触发 + 基线推进
      _ <- IO.blocking {
        os.write.over(repo / "a.txt", "v2")
        val commitArgs = Seq("-C", repoPath, "-c", "user.name=t", "-c", "user.email=t@t.io")
        os.proc(Seq("git") ++ commitArgs ++ Seq("add", ".")).call()
        os.proc(Seq("git") ++ commitArgs ++ Seq("commit", "-q", "-m", "v2")).call()
      }
      _ <- taskStore.updateTask("s1", t.id)(_.copy(triggerAt = past)) // 模拟 30min 复查期到达
      _ <- mkService().fireDueTasks
      after <- taskStore.loadTasks("s1")
    yield
      assert(baseline.conditionState.isDefined, "first evaluation must record a baseline (not fire)")
      assertEquals(baseline.conditionState, Some(tip1))
      assertEquals(baselineEvents, Nil, "baseline round must NOT inject (sentinel: change since arming, not state)")
      val evs = routed.map(_._2).toList
      assertEquals(evs.size, 1, "fires exactly once on the change")
      assert(evs.head.payload.contains("条件满足后触发"), evs.head.payload)
      assertEquals(after, Nil, "one-shot watcher consumed after firing")

  test("task-terminal sentinel E2E: running node waits; terminal status fires"):
    val project = "revival-proj"
    val mapDir = tempRoot / "projects" / project / ".nebflow"
    def writeMap(status: String): Unit = {
      os.makeDir.all(mapDir)
      os.write.over(
        mapDir / "flow-map.json",
        Json.obj(
          "v" -> 1.asJson,
          "project" -> project.asJson,
          "nodes" -> Json.obj(
            "nodeA" -> Json.obj("id" -> "nodeA".asJson, "name" -> "批次A".asJson, "status" -> status.asJson),
            "nodeB" -> Json.obj("id" -> "nodeB".asJson, "name" -> "批次B".asJson, "status" -> "running".asJson)
          )
        ).noSpaces
      )
    }
    for
      _ <- reset()
      _ <- IO.blocking(writeMap("running"))
      cond = s"task-terminal:$project:nodeA"
      t = ScheduledTask.create("s1", "批次A收尾了才叫", past, None, None, Some("terminal-watch"), None, Some(cond))
      _ <- taskStore.addTask(t)
      _ <- mkService().fireDueTasks
      stillWaiting <- taskStore.loadTasks("s1")
      _ <- IO.blocking(writeMap("completed"))
      _ <- taskStore.updateTask("s1", t.id)(_.copy(triggerAt = past))
      _ <- mkService().fireDueTasks
    yield
      assertEquals(routed.size, 1, "fires when the node reaches a terminal state")
      assert(routed.head._2.payload.contains("条件满足后触发"), routed.head._2.payload)
      assertEquals(stillWaiting.size, 1, "running status keeps waiting silently")

  // ==================================================================
  // ❹ 例程自检降频：空转达阈值 → 降频 + 待审查；活动清零；无证据保持；粘性
  // ==================================================================

  private def mkIdleTask(
    sessionId: String,
    streak: Int,
    flagged: Boolean,
    lastFiredAgoMs: Long,
    repeat: String = "hourly"
  ): IO[ScheduledTask] =
    val now = System.currentTimeMillis()
    IO(
      ScheduledTask
        .create(sessionId, "每小时例行检查", now - 10 * 60_000L, None, Some(repeat), Some(s"idle-$sessionId"))
        .copy(idleStreak = streak, reviewFlag = flagged, lastFiredAt = Some(now - lastFiredAgoMs))
    )

  test("3 consecutive idle fires -> auto-downfreq hourly→daily + 待审查 flag + payload notice"):
    for
      _ <- reset()
      t <- mkIdleTask("s-idle", streak = 2, flagged = false, lastFiredAgoMs = 2 * 3_600_000L)
      _ <- taskStore.addTask(t)
      // 最后一次会话活动在 3h 前——早于上次触发（2h 前）⇒ 本窗口零动作 = 空转第 3 次
      _ <- SessionToolActivity.recordAt("s-idle", System.currentTimeMillis() - 3 * 3_600_000L)
      _ <- mkService().fireDueTasks
      after <- taskStore.loadTasks("s-idle").map(_.head)
    yield
      assertEquals(events.size, 1, "the third idle fire still fires (downfreq, not deletion)")
      assertEquals(after.repeat, Some("daily"), "hourly must step down to daily")
      assertEquals(after.reviewFlag, true, "待审查 flag must be set at threshold")
      assertEquals(after.idleStreak, 3)
      assert(events.head.payload.contains("例程自检"), s"payload must inform: ${events.head.payload}")
      assert(events.head.payload.contains("待审查"), events.head.payload)
      assert(after.triggerAt > System.currentTimeMillis(), "next slot in the future")

  test("tool list shows the 待审查 marker so the author can restore or write off"):
    for
      _ <- reset()
      t <- mkIdleTask("s-list", streak = 3, flagged = true, lastFiredAgoMs = 2 * 3_600_000L)
      _ <- taskStore.addTask(t)
      res <- IO {
        ScheduleTool.call(Json.obj("action" -> "list".asJson).asObject.get, mkCtx()).unsafeRunSync()
      }
    yield
      val text = res.toOption.get
      assert(text.contains("待审查"), s"list must surface the review flag: $text")
      assert(text.contains("re-arm"), s"list must carry the restore guidance: $text")

  test("active window resets the streak; a flagged routine stays flagged (sticky, no self-clear)"):
    for
      _ <- reset()
      // 已挂标例程，本轮窗口内有活动（1h 前的活动 > 2h 前的上次触发）
      t <- mkIdleTask("s-live", streak = 3, flagged = true, lastFiredAgoMs = 2 * 3_600_000L)
      _ <- taskStore.addTask(t)
      _ <- SessionToolActivity.recordAt("s-live", System.currentTimeMillis() - 1 * 3_600_000L)
      _ <- mkService().fireDueTasks
      after <- taskStore.loadTasks("s-live").map(_.head)
    yield
      assertEquals(after.idleStreak, 0, "activity in the window clears the streak")
      assertEquals(after.repeat, Some("hourly"), "no further downfreq")
      assertEquals(after.reviewFlag, true, "flag is sticky — the author clears it, not the engine")

  test("no evidence (fresh boot, zero activity records) holds the streak — no reward, no punishment"):
    for
      _ <- reset()
      t <- mkIdleTask("s-cold", streak = 2, flagged = false, lastFiredAgoMs = 2 * 3_600_000L)
      _ <- taskStore.addTask(t)
      // SessionToolActivity 已 reset：本进程从未记录该会话活动 ⇒ 无证据
      _ <- mkService().fireDueTasks
      after <- taskStore.loadTasks("s-cold").map(_.head)
    yield
      assertEquals(after.idleStreak, 2, "streak must be held, not reset (restart loophole closed)")
      assertEquals(after.reviewFlag, false)
      assertEquals(after.repeat, Some("hourly"))

  test("weekly routine already at the ladder floor: flag set, no further downfreq"):
    for
      _ <- reset()
      t <- mkIdleTask("s-floor", streak = 2, flagged = false, lastFiredAgoMs = 9 * 86_400_000L / 2, repeat = "weekly")
      _ <- taskStore.addTask(t)
      _ <- SessionToolActivity.recordAt("s-floor", System.currentTimeMillis() - 9 * 86_400_000L)
      _ <- mkService().fireDueTasks
      after <- taskStore.loadTasks("s-floor").map(_.head)
    yield
      assertEquals(after.repeat, Some("weekly"), "weekly is the floor")
      assertEquals(after.reviewFlag, true, "still flagged at threshold")

  // ==================================================================
  // ❺ 触发链上下文：例行任务再次触发自动附带上一次执行结果摘要
  // ==================================================================

  test("recurring fire attaches the previous run's result summary (⑤原文：执行者不用从零考古)"):
    val lastFire = System.currentTimeMillis() - 3_600_000L
    val aiReply = UiMessage.Ai(
      text = "已完成：三件事都已归档，周报已发作者邮箱。\n细节略。",
      timestamp = lastFire + 60_000L
    )
    for
      _ <- reset()
      t = ScheduledTask
        .create("s1", "每日晨检", past, None, Some("daily"), Some("morning-check"))
        .copy(lastFiredAt = Some(lastFire))
      _ <- taskStore.addTask(t)
      _ <- mkService(uiMessages = List(aiReply)).fireDueTasks
    yield
      assertEquals(events.size, 1)
      assert(events.head.payload.contains("上一轮结果摘要"), events.head.payload)
      assert(events.head.payload.contains("周报已发作者邮箱"), "summary carries the previous run's substance")

  test("prevResultSummary: first Ai row in window, newlines flattened, capped at 160 chars; none if no reply"):
    val t0 = 1_000_000L
    val long = "x" * 300
    val s1 = ScheduledTaskService.prevResultSummary(
      List(
        UiMessage.Ai("早于窗口", timestamp = t0 - 10),
        UiMessage.User("用户插话不算", timestamp = t0 + 5),
        UiMessage.Ai(s"第一行\n第二行 $long", timestamp = t0 + 10),
        UiMessage.Ai("更晚的不取", timestamp = t0 + 20)
      ),
      prevFireAt = t0
    )
    assertEquals(s1.map(_.take(6)), Some("第一行 第二"))
    assertEquals(s1.map(_.length), Some(161), "160 chars + ellipsis")
    assertEquals(
      ScheduledTaskService.prevResultSummary(List(UiMessage.User("只有用户行", timestamp = t0 + 1)), t0),
      None,
      "no Ai row => no summary line (zero noise)"
    )

  // ==================================================================
  // ❻ 同窗合并注入：同窗口同会话多触发合并为一次 ExternalEvent
  // ==================================================================

  test("two due tasks in one window and session merge into ONE ExternalEvent (⑥原文)"):
    for
      _ <- reset()
      a = ScheduledTask.create("s1", "任务A：检查词条批", past - 2_000)
      b = ScheduledTask.create("s1", "任务B：检查 askmode 批", past)
      _ <- taskStore.addTask(a)
      _ <- taskStore.addTask(b)
      _ <- mkService().fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events.size, 1, s"one window = one injection, got ${events.size}")
      assertEquals(events.head.eventType, "trigger-batch")
      assert(events.head.payload.contains("任务A"), events.head.payload)
      assert(events.head.payload.contains("任务B"), events.head.payload)
      assert(events.head.payload.contains("同窗合并"), events.head.payload)
      assertEquals(stored, Nil, "both one-shots still individually consumed")

  test("tasks in DIFFERENT sessions stay separate injections (merge is per-session)"):
    for
      _ <- reset()
      a = ScheduledTask.create("sA", "A 会话任务", past)
      b = ScheduledTask.create("sB", "B 会话任务", past)
      _ <- taskStore.addTask(a)
      _ <- taskStore.addTask(b)
      _ <- mkService(metaIds = Set("sA", "sB"), activeId = "sA").fireDueTasks
    yield
      assertEquals(events.size, 2, "different sessions => separate ExternalEvents")
      assertEquals(events.map(_.eventType).sorted, List("trigger", "trigger"))

  // ==================================================================
  // helpers
  // ==================================================================

  private def mkCtx(sessionId: Option[String] = Some("s-list")): ToolContext =
    import nebflow.agent.SharedResources
    import nebflow.core.compact.HistoryArchiver
    import nebflow.core.task.FileTaskStore
    import nebflow.llm.ModelCandidate
    import nebflow.shared.{ThinkingConfig}
    val sr = SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "schedule-revival-spec-archives"),
      fileLockManager = nebflow.core.tools.FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      scheduledTaskStore = taskStore,
      scheduledTaskService = None
    )
    ToolContext(
      projectRoot = "/tmp",
      sessionId = sessionId,
      sharedResources = Some(sr),
      wsSend = None
    )

end ScheduleRevivalSpec
