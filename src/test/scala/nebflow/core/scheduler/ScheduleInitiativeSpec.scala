package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.SessionStorePort
import nebflow.core.tools.ScheduleTool
import nebflow.shared.{PathUtil, SessionMeta, UiMessage}

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/**
 * 主动性增强批（2026-10-24 作者令「看看老定时任务有没有什么可优化的地方 来帮助
 * 增加你的主动性」），本批落地三项，spec 逐条钉死：
 *
 *  ① 条件触发（事件驱动）—— condition 探针退出码 0 才注入；
 *     「条件为真才注入」断言 + 未满足静默顺延断言 + 观察窗到期 PAUSED 断言。
 *  ② 错过补偿（missed fire）—— 重启/宕机错过的重复任务**补射恰一次**（带补跑
 *     标记）+ next 快进未来槽位（消灭逐周期连环补发风暴）；正常晚点零变化断言。
 *  ④ 自然语言解析增强 —— "7pm"/"noon"/星期（中英）/「明天下午3点」解析断言。
 *
 * 未落地的 ③自检降频 ⑤触发链上下文 ⑥同窗合并：需要 actor 输出关联/注入腿改造，
 * 本批不做（报告里说明）。
 */
class ScheduleInitiativeSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-schedule-initiative"
  PathUtil.setDataRoot(tempRoot)

  private val taskStore: ScheduledTaskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot / "scheduled-tasks") }

  private val past = System.currentTimeMillis() - 60_000L
  private val future = System.currentTimeMillis() + 3_600_000L

  private val routed = ListBuffer.empty[(String, AgentCommand.ExternalEvent)]
  private val appendedUi = ListBuffer.empty[(String, List[UiMessage])]

  private def fakeSessionStore(metaIds: Set[String], activeId: String): SessionStorePort =
    new SessionStorePort:
      def getSessionMeta(id: String): IO[Option[SessionMeta]] =
        IO.pure(if metaIds.contains(id) then Some(SessionMeta(id, id, 0L, 0L, false)) else None)
      def getActiveId: IO[String] = IO.pure(activeId)
      def appendUiMessages(sessionId: String, msgs: List[UiMessage]): IO[Unit] =
        IO.delay { appendedUi += ((sessionId, msgs)) }.void
      def getFolderName(folderId: String): Option[String] = None
      def flushPendingUiWrites: IO[Unit] = IO.unit
      def flushPendingMessages: IO[Unit] = IO.unit

  private def mkService(metaIds: Set[String] = Set("s1"), activeId: String = "s1"): ScheduledTaskService =
    routed.clear(); appendedUi.clear()
    new ScheduledTaskService(
      dispatcher = null,
      taskStore = taskStore,
      routeToAgent = (sid, event) => IO.delay { routed += ((sid, event)) }.void,
      broadcast = _ => IO.unit,
      sessionStore = fakeSessionStore(metaIds, activeId)
    )

  private def events: List[AgentCommand.ExternalEvent] = routed.map(_._2).toList

  // ==================================================================
  // ① 条件触发：条件为真才注入（作者 spec 断言）
  // ==================================================================

  test("condition FALSE -> nothing injected, one-shot silently re-armed for re-check"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "CI 红了才叫醒", past, None, None, None, None, Some("exit 1")))
      _ <- taskStore.addTask(t)
      _ <- mkService().fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events, Nil, "condition unmet must NOT inject (idle-quiet red line)")
      assert(appendedUi.isEmpty, "no user message either")
      assertEquals(stored.map(_.id), List(t.id), "one-shot condition watcher must survive (not deleted)")
      val after = stored.head
      assert(after.triggerAt > System.currentTimeMillis(), "must be re-armed for the next re-check")
      assert(after.enabled, "still active within the 24h watch window")

  test("condition FALSE on a repeating task -> skips the whole cycle to the next slot"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "有新文件才执行", past, None, Some("daily"), Some("dir-watch"), None, Some("exit 1")))
      _ <- taskStore.addTask(t)
      _ <- mkService().fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events, Nil)
      val after = stored.head
      assert(after.triggerAt > System.currentTimeMillis(), "skipped forward, not spinning on 10s polls")
      assertEquals(
        after.triggerAt - t.triggerAt,
        86_400_000L,
        "next slot = next daily cycle (snap-forward semantics)"
      )

  test("condition TRUE -> fires with the condition-met note"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "CI 红了才叫醒", past, None, None, None, None, Some("exit 0")))
      _ <- taskStore.addTask(t)
      _ <- mkService().fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events.map(_.source), List("scheduled-task"))
      assert(events.head.payload.contains("CI 红了才叫醒"), events.head.payload)
      assert(events.head.payload.contains("条件满足后触发"), "must tell the executor why it fired")
      assertEquals(stored, Nil, "one-shot consumed after firing")

  test("condition watcher unmet beyond 24h -> auto-PAUSED (visible in list, never fires silently)"):
    for
      _ <- reset()
      t0 <- IO(ScheduledTask.create("s1", "过期观察哨", past, None, None, None, None, Some("exit 1")))
      t = t0.copy(createdAt = System.currentTimeMillis() - 25 * 3_600_000L) // 模拟挂了 25h 的一次性观察哨
      _ <- taskStore.addTask(t)
      _ <- mkService().fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events, Nil, "no placeholder message even on expiry")
      assertEquals(stored.map(_.enabled), List(false), "auto-PAUSED after the 24h watch window")
      assertEquals(stored.map(_.triggerAt), List(t.triggerAt), "not re-armed any further")

  test("blank/absent condition behaves as the legacy unconditional path"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "无条件任务", past, None, None, None, None, Some("   ")))
      _ <- taskStore.addTask(t)
      _ <- mkService().fireDueTasks
    yield
      assertEquals(events.size, 1, "whitespace condition = no condition")

  // ==================================================================
  // ② 错过补偿：重启后补射恰一次（作者 spec 断言）
  // ==================================================================

  /** 与升级前格式一致的存量重复任务（宕机场景：triggerAt 已过期 3 个周期）。 */
  private def staleRecurringJson(id: String, triggerAt: Long): Json =
    Json.obj(
      "id" -> id.asJson,
      "sessionId" -> "s1".asJson,
      "content" -> "每日例行：晨检".asJson,
      "triggerAt" -> triggerAt.asJson,
      "createdAt" -> (triggerAt - 7 * 86_400_000L).asJson,
      "triggered" -> false.asJson,
      "triggeredAt" -> Json.Null,
      "referencePath" -> Json.Null,
      "repeat" -> "daily".asJson,
      "enabled" -> true.asJson,
      "name" -> "morning-check".asJson
    )

  test("missed recurring fires EXACTLY ONCE after recovery, marked 补跑, next snapped to the future"):
    val threeDaysAgo = System.currentTimeMillis() - 3 * 86_400_000L
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(tempRoot / "scheduled-tasks" / "s1.json", Json.arr(staleRecurringJson("missed01", threeDaysAgo)).noSpaces)
      }
      svc = mkService()
      _ <- svc.fireDueTasks
      countAfterFirst = events.size
      stored <- taskStore.loadTasks("s1")
      _ <- svc.fireDueTasks // 恢复后立刻再轮询：不得连环补发
      countAfterSecond = events.size
    yield
      assertEquals(countAfterFirst, 1, "recovery fires the missed routine exactly once (not per missed period)")
      val e = events.head
      assert(e.payload.contains("补跑"), s"make-up marker required: ${e.payload}")
      assert(e.payload.contains("3 个完整周期"), e.payload)
      val next = stored.head.triggerAt
      assert(next > System.currentTimeMillis(), "next slot must be in the future (storm eliminated)")
      assertEquals(
        (next - threeDaysAgo) % 86_400_000L,
        0L,
        "snap stays on the original daily anchor (no drift)"
      )
      assertEquals(countAfterSecond, 1, "second poll must NOT fire again")

  test("regression: normally-late recurring (< 1 period) keeps byte-identical behavior — no 补跑 note, next = +1 period"):
    val fiveMinAgo = System.currentTimeMillis() - 5 * 60_000L
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(tempRoot / "scheduled-tasks" / "s1.json", Json.arr(staleRecurringJson("ontime01", fiveMinAgo)).noSpaces)
      }
      _ <- mkService().fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events.size, 1)
      assert(!events.head.payload.contains("补跑"), "slightly-late fire is NOT a make-up run")
      assert(events.head.payload.contains("（实际触发"), "legacy payload suffix intact")
      assertEquals(stored.head.triggerAt, fiveMinAgo + 86_400_000L, "next = previous triggerAt + 24h (unchanged)")

  // ==================================================================
  // ④ 自然语言解析增强
  // ==================================================================

  private val zone = ZoneId.of("Asia/Shanghai")
  // 2026-10-24 = Saturday (verified: 2026-01-01 Thu, day 297)
  private val sat1000 = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, zone).toInstant.toEpochMilli
  private val sat2000 = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, zone).toInstant.toEpochMilli

  private def parse(s: String, now: Long = sat1000): Either[String, Long] =
    ScheduleTool.parseTriggerAt(Some(Json.fromString(s)), now, zone)

  private def expectAt(s: String, y: Int, mo: Int, d: Int, h: Int, mi: Int, now: Long = sat1000): Unit =
    val got = parse(s, now)
    val want = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant.toEpochMilli
    assertEquals(got, Right(want), s"\"$s\"")

  test("am/pm forms: 7pm rolls today/tomorrow by the clock; noon; midnight"):
    expectAt("7pm", 2026, 10, 24, 19, 0) // Sat 10:00 -> today 19:00
    expectAt("7pm", 2026, 10, 25, 19, 0, now = sat2000) // Sat 20:00 -> tomorrow
    expectAt("7:30 pm", 2026, 10, 24, 19, 30)
    expectAt("noon", 2026, 10, 24, 12, 0)
    expectAt("12am", 2026, 10, 25, 0, 0) // 12am = midnight -> today 00:00 is past -> tomorrow
    expectAt("midnight", 2026, 10, 25, 0, 0)

  test("weekday forms pick the next strictly-future occurrence (default 09:00), en + zh"):
    expectAt("monday", 2026, 10, 26, 9, 0)
    expectAt("next monday", 2026, 10, 26, 9, 0)
    expectAt("sunday", 2026, 10, 25, 9, 0)
    expectAt("saturday", 2026, 10, 31, 9, 0) // today IS saturday -> strictly future = +7d
    expectAt("friday", 2026, 10, 30, 9, 0)
    expectAt("周一", 2026, 10, 26, 9, 0)
    expectAt("周日", 2026, 10, 25, 9, 0)
    expectAt("星期三", 2026, 10, 28, 9, 0)

  test("weekday + time combos: 'friday 21:00', 'monday 7pm', '周一 21:30'"):
    expectAt("friday 21:00", 2026, 10, 30, 21, 0)
    expectAt("monday 7pm", 2026, 10, 26, 19, 0)
    expectAt("周一 21:30", 2026, 10, 26, 21, 30)
    expectAt("sunday at 9:30", 2026, 10, 25, 9, 30)

  test("Chinese date+period forms: 明天下午3点 / 后天上午9点 / 今天9点 rolls / 晚上11点半 / 大后天"):
    expectAt("明天下午3点", 2026, 10, 25, 15, 0)
    expectAt("后天上午9点", 2026, 10, 26, 9, 0)
    expectAt("今天 9点", 2026, 10, 25, 9, 0) // 已过 10:00 -> 顺延一天
    expectAt("今天 9点", 2026, 10, 24, 9, 0, now = ZonedDateTime.of(2026, 10, 24, 8, 0, 0, 0, zone).toInstant.toEpochMilli)
    expectAt("晚上11点半", 2026, 10, 24, 23, 30)
    expectAt("明天 15点", 2026, 10, 25, 15, 0)
    expectAt("大后天晚上12点", 2026, 10, 28, 0, 0) // 晚上12点 = 次日零点

  test("legacy accepted forms keep parsing (regression)"):
    expectAt("21:30", 2026, 10, 24, 21, 30)
    expectAt("at 9:05", 2026, 10, 25, 9, 5) // 10:00 已过 -> 明天
    expectAt("tomorrow", 2026, 10, 25, 9, 0)
    expectAt("tomorrow at 21:00", 2026, 10, 25, 21, 0)
    expectAt("in 30 minutes", 2026, 10, 24, 10, 30)
    assert(parse("2026-12-25T08:00:00+08:00").isRight)
    assert(parse("明年").isLeft, "unsupported shape still errors with guidance")
    assert(parse("明年").left.toOption.get.contains("明天下午3点"), "error message lists the new shapes")

  // ==================================================================
  // 工具面：condition 字段落库 + list 展示
  // ==================================================================

  private def mkCtx(sessionId: Option[String] = Some("s-tool")): nebflow.core.tools.ToolContext =
    import nebflow.agent.SharedResources
    import nebflow.core.compact.HistoryArchiver
    import nebflow.core.task.FileTaskStore
    import nebflow.llm.ModelCandidate
    import nebflow.shared.ThinkingConfig
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "schedule-initiative-spec-archives"),
      fileLockManager = nebflow.core.tools.FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      scheduledTaskStore = taskStore,
      scheduledTaskService = None
    )
    nebflow.core.tools.ToolContext(
      projectRoot = "/tmp",
      sessionId = sessionId,
      sharedResources = Some(sr),
      wsSend = None
    )

  test("tool create with condition stores the probe; list marks it"):
    for
      _ <- reset()
      res <- IO {
        ScheduleTool
          .call(
            Json
              .obj(
                "content" -> "CI 红了才汇报".asJson,
                "triggerAt" -> "in 30 minutes".asJson,
                "repeat" -> "hourly".asJson,
                "condition" -> "exit 1".asJson,
                "name" -> "ci-watch".asJson
              )
              .asObject
              .get,
            mkCtx()
          )
          .unsafeRunSync()
      }
      stored <- taskStore.loadTasks("s-tool")
      _ <- IO {
        assert(res.isRight, res.toString)
        assertEquals(stored.head.condition, Some("exit 1"))
        assertEquals(stored.head.repeat, Some("hourly"))
      }
      listedRes <- IO {
        ScheduleTool
          .call(Json.obj("action" -> "list".asJson).asObject.get, mkCtx())
          .unsafeRunSync()
      }
    yield
      val text = listedRes.toOption.get
      assert(text.contains("cond: exit 1"), text)

end ScheduleInitiativeSpec
