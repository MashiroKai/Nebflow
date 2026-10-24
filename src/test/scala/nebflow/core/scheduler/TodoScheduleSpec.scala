package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.SessionStorePort
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ScheduleTool, ToolContext, ToolError}
import nebflow.actor.AgentCommand
import nebflow.llm.ModelCandidate
import nebflow.shared.{PathUtil, SessionMeta, ThinkingConfig, UiMessage}

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/**
 * Schedule 定时工具双面化（2026-10-24 作者令）：定时任务（现状零变化）+ 待办
 * （kind=todo，无固定触发时间，引擎侧同等持久，每日 09:00 汇总浮出直至
 * done 销账 / cancel 取消）。
 *
 * 作者 spec 四断言，本文件逐条钉死：
 *  1. 待办跨「压缩等价操作」（会话重开/重启）存活 —— fresh store 实例重读
 *  2. 未完成待办按周期重新注入 —— 到期 ⇒ 注入 digest + 顺延明晨；再到期 ⇒ 再注入
 *  3. 销账后不再注入 —— delete 后 fireDueTasks 零注入
 *  4. 定时任务回归不变 —— 存量无 kind 键照常解码/照常到点触发（逐字旧路径）
 */
class TodoScheduleSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-todo-schedule"
  PathUtil.setDataRoot(tempRoot)

  private val taskStore: ScheduledTaskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot / "scheduled-tasks") }

  private val future = System.currentTimeMillis() + 3_600_000L
  private val past = System.currentTimeMillis() - 60_000L

  /** 与升级前 encoder 逐字段一致的裸 JSON（无 kind 键）——存量文件。 */
  private def legacyJson(id: String, sessionId: String, triggerAt: Long): Json =
    Json.obj(
      "id" -> id.asJson,
      "sessionId" -> sessionId.asJson,
      "content" -> "梦境日审：回顾昨日梦境记录".asJson,
      "triggerAt" -> triggerAt.asJson,
      "createdAt" -> (triggerAt - 86_400_000L).asJson,
      "triggered" -> false.asJson,
      "triggeredAt" -> Json.Null,
      "referencePath" -> Json.Null,
      "repeat" -> Json.Null,
      "enabled" -> true.asJson,
      "name" -> Json.Null
    )

  // ==================================================================
  // 断言 1：待办跨「压缩等价操作」（会话重开/重启）存活
  // ==================================================================

  test("todo persists across restart-equivalent ops: fresh store instance re-reads it from disk"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "把周报模板整理进 memory", past, None, None, Some("weekly-template"), Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(t)
      // 压缩/重启等价操作：全新实例从同一盘上目录重读（无任何进程内状态）
      fresh = new ScheduledTaskStore(tempRoot / "scheduled-tasks")
      todos <- fresh.getAllTodos
      pending <- fresh.getAllPendingTasks
    yield
      assertEquals(todos.map(_.id), List(t.id), "todo must survive store-instance recreation")
      assertEquals(todos.head.isTodo, true)
      assertEquals(pending.map(_.id), List(t.id))

  test("legacy task file (no kind key) still decodes as scheduled-task face; kind=todo decodes as todo"):
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(
          tempRoot / "scheduled-tasks" / "s-mixed.json",
          Json.arr(
            legacyJson("16820015", "s-mixed", future),
            legacyJson("a1b2c3d4", "s-mixed", future).deepMerge(Json.obj("kind" -> "todo".asJson))
          ).noSpaces
        )
      }
      tasks <- taskStore.loadTasks("s-mixed")
    yield
      assertEquals(tasks.size, 2)
      assertEquals(tasks.find(_.id == "16820015").map(_.isTodo), Some(false), "no kind key = scheduled task")
      assertEquals(tasks.find(_.id == "a1b2c3d4").map(_.isTodo), Some(true), "kind=todo = todo face")

  // ==================================================================
  // 断言 4（store 面）：定时任务回归不变——待办不进定时触发路径
  // ==================================================================

  test("due todo never enters the scheduled-task due path; due scheduled task never enters the todo path"):
    for
      _ <- reset()
      todo <- IO(ScheduledTask.create("s1", "overdue todo", past, None, None, None, Some(ScheduledTask.KindTodo)))
      task <- IO(ScheduledTask.create("s1", "overdue task", past - 1_000))
      _ <- taskStore.addTask(todo)
      _ <- taskStore.addTask(task)
      dueTasks <- taskStore.getAllDueTasks
      dueTodos <- taskStore.getAllDueTodos
      allTodos <- taskStore.getAllTodos
    yield
      assertEquals(dueTasks.map(_.id), List(task.id), "todo must NOT fire as a scheduled task")
      assertEquals(dueTodos.map(_.id), List(todo.id), "scheduled task must NOT surface as a todo")
      assertEquals(allTodos.map(_.id), List(todo.id))

  test("nextTodoSurfaceAt: before 09:00 -> today 09:00; at/after 09:00 -> tomorrow 09:00"):
    val zone = ZoneId.of("Asia/Shanghai")
    val before = ZonedDateTime.of(2026, 10, 24, 8, 30, 0, 0, zone).toInstant.toEpochMilli
    val at = ZonedDateTime.of(2026, 10, 24, 9, 0, 0, 0, zone).toInstant.toEpochMilli
    val after = ZonedDateTime.of(2026, 10, 24, 23, 59, 0, 0, zone).toInstant.toEpochMilli
    val sameDay = ZonedDateTime.of(2026, 10, 24, 9, 0, 0, 0, zone).toInstant.toEpochMilli
    val nextDay = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone).toInstant.toEpochMilli
    assertEquals(ScheduledTask.nextTodoSurfaceAt(before, zone), sameDay)
    assertEquals(ScheduledTask.nextTodoSurfaceAt(at, zone), nextDay, "exactly 09:00 is already past -> roll to tomorrow")
    assertEquals(ScheduledTask.nextTodoSurfaceAt(after, zone), nextDay)

  test("upsert is kind-scoped: a named todo never clobbers a same-name scheduled task"):
    for
      _ <- reset()
      task <- IO(ScheduledTask.create("s1", "morning routine", future, None, Some("daily"), Some("review")))
      todo1 <- IO(ScheduledTask.create("s1", "todo v1", future, None, None, Some("review"), Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(task)
      _ <- taskStore.upsertTaskByName(todo1)
      todo2 <- IO(ScheduledTask.create("s2", "todo v2", future + 1, None, None, Some("review"), Some(ScheduledTask.KindTodo)))
      removed <- taskStore.upsertTaskByName(todo2)
      pending <- taskStore.getAllPendingTasks
      // 反向同理：定时任务同名 upsert 不吃待办
      task3 <- IO(ScheduledTask.create("s3", "task v3", future + 2, None, None, Some("review")))
      removed2 <- taskStore.upsertTaskByName(task3)
      finalPending <- taskStore.getAllPendingTasks
    yield
      assertEquals(removed.map(_.id), List(todo1.id), "todo upsert replaces only the same-kind same-name todo")
      assertEquals(pending.map(_.id).sorted, List(task.id, todo2.id).sorted, "the scheduled task must survive untouched")
      assertEquals(removed2.map(_.id), List(task.id))
      assertEquals(finalPending.map(_.id).sorted, List(task3.id, todo2.id).sorted)

  // ==================================================================
  // 断言 2 + 3 + 4（service 面）：digest 浮出 / 销账 / 定时任务回归
  // ==================================================================

  private val routed = ListBuffer.empty[(String, AgentCommand.ExternalEvent)]
  private val broadcasted = ListBuffer.empty[Json]
  private val appendedUi = ListBuffer.empty[(String, List[UiMessage])]

  private def fakeSessionStore(metaIds: Set[String], activeId: String): SessionStorePort = new SessionStorePort:
    def getSessionMeta(id: String): IO[Option[SessionMeta]] =
      IO.pure(if metaIds.contains(id) then Some(SessionMeta(id, id, 0L, 0L, false)) else None)
    def getActiveId: IO[String] = IO.pure(activeId)
    def appendUiMessages(sessionId: String, msgs: List[UiMessage]): IO[Unit] =
      IO.delay { appendedUi += ((sessionId, msgs)) }.void
    def getFolderName(folderId: String): Option[String] = None
    def flushPendingUiWrites: IO[Unit] = IO.unit
    def flushPendingMessages: IO[Unit] = IO.unit

  /** fresh harness per test（ListBuffer 有状态）。 */
  private def mkService(metaIds: Set[String] = Set("s1"), activeId: String = "s1"): ScheduledTaskService =
    routed.clear(); broadcasted.clear(); appendedUi.clear()
    new ScheduledTaskService(
      dispatcher = null, // service 本体不消费 dispatcher（历史构造参数）
      taskStore = taskStore,
      routeToAgent = (sid, event) => IO.delay { routed += ((sid, event)) }.void,
      broadcast = j => IO.delay { broadcasted += j }.void,
      sessionStore = fakeSessionStore(metaIds, activeId)
    )

  private def events: List[AgentCommand.ExternalEvent] = routed.map(_._2).toList

  test("unfinished due todo re-surfaces: ONE digest ExternalEvent with ids + done guidance, then bumped to next 09:00"):
    for
      _ <- reset()
      due <- IO(ScheduledTask.create("s1", "待办A：review PR #42", past, None, None, None, Some(ScheduledTask.KindTodo)))
      notDue <- IO(ScheduledTask.create("s1", "待办B：还没到点", future, None, None, None, Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(due)
      _ <- taskStore.addTask(notDue)
      svc = mkService()
      _ <- svc.fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      // 一条汇总注入（不是逐条轰炸），内容带全部未完成待办 + id + 销账口径
      assertEquals(events.size, 1, s"exactly one digest, got ${events.size}")
      val e = events.head
      assertEquals(e.source, "todo-surface")
      assertEquals(e.eventType, "digest")
      assert(e.payload.contains("待办A：review PR #42"), e.payload)
      assert(e.payload.contains("待办B：还没到点"), "digest covers ALL unfinished todos")
      assert(e.payload.contains(due.id), e.payload)
      assert(e.payload.toLowerCase.contains("done"), "must carry the write-off instruction")
      // 落历史（压缩不丢的另一半：注入以真实 user 消息持久化）
      assertEquals(appendedUi.map(_._1).toList, List("s1"))
      assert(
        appendedUi.head._2.head match
          case u: UiMessage.User => u.text.contains("待办A")
          case _ => false
      )
      // 到期的顺延到下一次 09:00（> now），未到期的首次浮出时间不动
      val dueAfter = stored.find(_.id == due.id).get
      val notDueAfter = stored.find(_.id == notDue.id).get
      assert(dueAfter.triggerAt > System.currentTimeMillis(), "due todo must be bumped into the future")
      assertEquals(
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(dueAfter.triggerAt), ZoneId.systemDefault()).getHour,
        9,
        "bump target is the daily 09:00 digest"
      )
      assertEquals(notDueAfter.triggerAt, notDue.triggerAt, "not-yet-due todo keeps its first surface time")
      // 前端广播
      assert(broadcasted.exists(_.hcursor.downField("type").as[String].toOption.contains("userMessage")))
      assert(broadcasted.exists(_.hcursor.downField("type").as[String].toOption.contains("todoSurfaced")))

  test("period honored: after surfacing, next cycle injects nothing until due again (next day)"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "每日待办", past, None, None, None, Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(t)
      svc = mkService()
      _ <- svc.fireDueTasks
      countAfterFirst = events.size
      _ <- svc.fireDueTasks // 立刻再跑一轮：未到期 ⇒ 零注入
      countAfterIdle = events.size
      // 模拟隔日：把浮出时间改回过去 ⇒ 再一轮 ⇒ 重新注入
      _ <- taskStore.rescheduleTask("s1", t.id, System.currentTimeMillis() - 1_000)
      _ <- svc.fireDueTasks
      countAfterNextDay = events.size
    yield
      assertEquals(countAfterFirst, 1)
      assertEquals(countAfterIdle, 1, "not due again => must NOT re-inject")
      assertEquals(countAfterNextDay, 2, "due again => re-injects (periodic re-surfacing)")

  // ==================================================================
  // 断言 3：销账后不再注入
  // ==================================================================

  test("written-off (done) todo is deleted and never surfaces again"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "一次性待办", past, None, None, None, Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(t)
      svc = mkService()
      _ <- taskStore.deleteTask("s1", t.id) // done 销账 = 从存储删除（无 tombstone）
      _ <- svc.fireDueTasks
      _ <- taskStore.rescheduleTask("s1", t.id, past) // 即便有人手滑改时间：条目已不在
      _ <- svc.fireDueTasks
      todos <- taskStore.getAllTodos
    yield
      assertEquals(events, Nil, "written-off todo must never surface again")
      assertEquals(todos, Nil)

  // ==================================================================
  // 断言 4（service 面）：定时任务逐字回归——老路径触发、待办路径不碰它
  // ==================================================================

  test("regression: due legacy one-shot task still fires individually on the old path (and is deleted)"):
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(
          tempRoot / "scheduled-tasks" / "s1.json",
          Json.arr(legacyJson("legacy01", "s1", past)).noSpaces
        )
      }
      svc = mkService()
      _ <- svc.fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events.size, 1, "legacy task fires exactly as before")
      val e = events.head
      assertEquals(e.source, "scheduled-task", "old source string byte-identical")
      assertEquals(e.eventType, "trigger")
      assert(e.payload.startsWith("梦境日审"), e.payload)
      assert(e.payload.contains("（实际触发"), "legacy payload suffix intact")
      assertEquals(stored, Nil, "one-shot still deleted after firing")

  test("mixed faces: due task + due todo in one cycle -> task fires old path, todo gets the digest"):
    for
      _ <- reset()
      task <- IO(ScheduledTask.create("s1", "定时：汇报进度", past - 2_000))
      todo <- IO(ScheduledTask.create("s1", "待办：买牛奶", past, None, None, None, Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(task)
      _ <- taskStore.addTask(todo)
      svc = mkService()
      _ <- svc.fireDueTasks
      stored <- taskStore.loadTasks("s1")
    yield
      assertEquals(events.size, 2)
      val bySource = events.groupBy(_.source)
      assertEquals(bySource("scheduled-task").map(_.payload.take(5)), List("定时："))
      assertEquals(bySource("scheduled-task").map(_.eventType), List("trigger"))
      assertEquals(bySource("todo-surface").size, 1)
      assert(bySource("todo-surface").head.payload.contains("待办：买牛奶"))
      // 定时任务 one-shot 已删；待办仍在（顺延到明晨）
      assertEquals(stored.map(_.id), List(todo.id), "todo survives the cycle; task consumed")
      assert(stored.head.triggerAt > System.currentTimeMillis())

  test("session fallback: todo in a dead session surfaces to the active session (restart/open-equivalent)"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s-dead", "孤儿待办", past, None, None, None, Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(t)
      svc = mkService(metaIds = Set.empty, activeId = "s-live")
      _ <- svc.fireDueTasks
    yield
      assertEquals(routed.map(_._1).toList, List("s-live"), "digest must follow the live session")
      assertEquals(appendedUi.map(_._1).toList, List("s-live"))

  test("digestPayload: empty -> empty string; non-empty -> count + ids + done/cancel guidance"):
    val now = System.currentTimeMillis()
    assertEquals(ScheduledTaskService.digestPayload(Nil, now), "")
    val t = ScheduledTask.create("s1", "内容X", now, None, None, Some("nm"), Some(ScheduledTask.KindTodo))
    val p = ScheduledTaskService.digestPayload(List(t), now)
    assert(p.contains("1 项未完成待办"), p)
    assert(p.contains(t.id), p)
    assert(p.contains("内容X"), p)
    assert(p.contains("done") && p.contains("cancel"), p)

  // ==================================================================
  // 工具面：create(kind=todo) / list 双节 / done / cancel
  // ==================================================================

  private val wsSent = scala.collection.mutable.ListBuffer.empty[Json]

  private def mkCtx(sessionId: Option[String] = Some("s-tool")): ToolContext =
    wsSent.clear()
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "todo-schedule-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
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
      wsSend = Some(j => IO(wsSent += j).void)
    )

  private def callTool(input: Json, ctx: ToolContext): Either[ToolError, String] =
    ScheduleTool.call(input.asObject.get, ctx).unsafeRunSync()

  test("tool create kind=todo needs no triggerAt and lands in engine storage at next 09:00"):
    for
      _ <- reset()
      res <- IO(callTool(Json.obj("kind" -> "todo".asJson, "content" -> "整理会议纪要".asJson), mkCtx()))
      stored <- taskStore.loadTasks("s-tool")
    yield
      val text = res.toOption.get
      assert(text.startsWith("Todo"), s"todo success prefix, got: $text")
      assertEquals(stored.size, 1)
      val t = stored.head
      assertEquals(t.isTodo, true)
      assertEquals(t.repeat, None, "todo carries no repeat semantics")
      val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t.triggerAt), ZoneId.systemDefault())
      assertEquals(zdt.getHour, 9)
      assertEquals(zdt.getMinute, 0)
      assert(t.triggerAt > t.createdAt, "first surface must be in the future")
      assert(
        wsSent.exists(_.hcursor.downField("type").as[String].toOption.contains("scheduledTaskCreated")),
        s"panel broadcast missing: $wsSent"
      )

  test("tool list shows both faces: scheduled section (legacy format) + todos section with ids"):
    for
      _ <- reset()
      task <- IO(ScheduledTask.create("s1", "定时例行", future, None, Some("daily"), Some("routine")))
      todo <- IO(ScheduledTask.create("s2", "记得买牛奶", future, None, None, Some("groceries"), Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(task)
      _ <- taskStore.addTask(todo)
      res <- IO(callTool(Json.obj("action" -> "list".asJson), mkCtx()))
    yield
      val text = res.toOption.get
      assert(text.contains("Pending scheduled tasks (1)"), text)
      assert(text.contains("Todos (1)"), text)
      assert(text.contains(todo.id), "todo must carry its id (done/cancel handle)")
      assert(text.contains("买牛奶"), text)
      assert(text.contains("done"), text)

  test("tool done writes off a todo (deleted, panel broadcast); done on a scheduled task is a category error"):
    for
      _ <- reset()
      todo <- IO(ScheduledTask.create("s1", "已完成的待办", future, None, None, None, Some(ScheduledTask.KindTodo)))
      task <- IO(ScheduledTask.create("s1", "定时任务", future, None, None, None))
      _ <- taskStore.addTask(todo)
      _ <- taskStore.addTask(task)
      resTodo <- IO(callTool(Json.obj("action" -> "done".asJson, "id" -> todo.id.asJson), mkCtx()))
      resTask <- IO(callTool(Json.obj("action" -> "done".asJson, "id" -> task.id.asJson), mkCtx()))
      pending <- taskStore.getAllPendingTasks
    yield
      assert(resTodo.toOption.get.startsWith("Done"), resTodo.toString)
      assert(resTodo.toOption.get.contains("will not surface again"), resTodo.toString)
      val err = resTask.left.toOption.get.message
      assert(err.contains("cancel"), s"must redirect to cancel: $err")
      assertEquals(pending.map(_.id), List(task.id), "todo gone, task untouched")
      assert(
        wsSent.exists(j =>
          j.hcursor.downField("type").as[String].toOption.contains("scheduledTaskDeleted") &&
            j.hcursor.downField("id").as[String].toOption.contains(todo.id)
        ),
        s"panel delete broadcast missing: $wsSent"
      )

  test("tool cancel on a todo reports no-more-surfacing (same id semantics as task cancel)"):
    for
      _ <- reset()
      todo <- IO(ScheduledTask.create("s1", "不需要的待办", future, None, None, None, Some(ScheduledTask.KindTodo)))
      _ <- taskStore.addTask(todo)
      res <- IO(callTool(Json.obj("action" -> "cancel".asJson, "id" -> todo.id.asJson), mkCtx()))
      pending <- taskStore.getAllPendingTasks
    yield
      val text = res.toOption.get
      assert(text.startsWith("Cancelled todo"), text)
      assert(text.contains("will not surface again"), text)
      assertEquals(pending, Nil)

  test("tool todo upsert by name converges to a single copy"):
    for
      _ <- reset()
      r1 <- IO(callTool(Json.obj("kind" -> "todo".asJson, "content" -> "v1".asJson, "name" -> "pack-bag".asJson), mkCtx()))
      r2 <- IO(callTool(Json.obj("kind" -> "todo".asJson, "content" -> "v2".asJson, "name" -> "pack-bag".asJson), mkCtx()))
      todos <- taskStore.getAllTodos
    yield
      assert(r1.isRight && r2.isRight)
      assertEquals(todos.map(_.content), List("v2"), "same-name todo must replace, not stack")

  // ==================================================================
  // 提醒面（root agent schedule reminder 双节化）
  // ==================================================================

  test("root schedule reminder carries a todos section with ids; task-only session keeps the legacy header"):
    for
      _ <- reset()
      _ <- taskStore.addTask(ScheduledTask.create("s1", "整理记忆", future))
      reminders <- nebflow.core.SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = true)
      _ <- IO {
        val sched = reminders.find(_.category == "schedule")
        assert(sched.isDefined)
        assert(sched.get.content.contains("Pending schedules (1)"), "legacy header byte-stable")
        assert(!sched.get.content.contains("Pending todos"))
      }
      _ <- taskStore.addTask(
        ScheduledTask.create("s1", "记得买牛奶", future, None, None, None, Some(ScheduledTask.KindTodo))
      )
      reminders2 <- nebflow.core.SystemReminders.collectAllIO(true, taskStore, Some("s1"), isRootAgent = true)
    yield
      val sched2 = reminders2.find(_.category == "schedule").get
      assert(sched2.content.contains("Pending schedules (1)"), sched2.content)
      assert(sched2.content.contains("Pending todos (1)"), sched2.content)
      assert(sched2.content.contains("买牛奶"), sched2.content)

end TodoScheduleSpec
