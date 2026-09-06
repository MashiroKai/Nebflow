package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskStore}
import nebflow.core.task.FileTaskStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}

import java.time.{Instant, ZoneId}

/**
 * Schedule 工具 list / cancel / upsert 升级（2026-09-06 作者拍板）。
 *
 * 事故根因：重启后 Nebula 无法 list 既有任务 → re-arm 出第二份梦境日审
 * （16820015 / 8f85c4c3 同 triggerAt 并存），且无 cancel 能力删不掉。
 *
 * 本 spec 钉死：
 *  1. 向后兼容——存量任务文件没有 name 键照常加载（重启语义：重启后 list 完整）
 *  2. upsert——同 name 再建=替换不叠加（跨会话全局收敛单份，重启 re-arm 去重）
 *  3. cancel——取消后零触发、list 即时消失（跨会话按 id）
 *  4. list——跨会话全量待触发任务（id/时间/repeat/状态/content 摘要）
 *  5. 工具面——action ∈ {create(默认), list, cancel}，单条目不加新工具
 */
class ScheduleActionsSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-schedule-actions"
  PathUtil.setDataRoot(tempRoot)

  private val taskStore: ScheduledTaskStore = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(tempRoot / "scheduled-tasks") }

  /** 与旧版 encoder 逐字段一致的裸 JSON（无 name 键）——模拟升级前落盘的存量文件。 */
  private def legacyJson(id: String, sessionId: String, triggerAt: Long, repeat: Option[String]): Json =
    Json.obj(
      "id" -> id.asJson,
      "sessionId" -> sessionId.asJson,
      "content" -> "梦境日审：回顾昨日梦境记录".asJson,
      "triggerAt" -> triggerAt.asJson,
      "createdAt" -> (triggerAt - 86_400_000L).asJson,
      "triggered" -> false.asJson,
      "triggeredAt" -> Json.Null,
      "referencePath" -> Json.Null,
      "repeat" -> repeat.asJson,
      "enabled" -> true.asJson
    )

  private val future = System.currentTimeMillis() + 3_600_000L

  // ==================================================================
  // 1. 向后兼容 / 重启语义：存量文件（无 name 键）照常加载
  // ==================================================================

  test("legacy file without name key loads with name=None (restart keeps tasks listable)"):
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(
          tempRoot / "scheduled-tasks" / "s-legacy.json",
          Json.arr(legacyJson("16820015", "s-legacy", future, Some("daily"))).noSpaces
        )
      }
      tasks <- taskStore.loadTasks("s-legacy")
    yield
      assertEquals(tasks.size, 1)
      assertEquals(tasks.head.id, "16820015")
      assertEquals(tasks.head.name, None)
      assertEquals(tasks.head.repeat, Some("daily"))

  test("file with explicit name:null also decodes to None"):
    for
      _ <- reset()
      _ <- IO.blocking {
        os.write.over(
          tempRoot / "scheduled-tasks" / "s-nullname.json",
          Json.arr(legacyJson("8f85c4c3", "s-nullname", future, None).deepMerge(Json.obj("name" -> Json.Null))).noSpaces
        )
      }
      tasks <- taskStore.loadTasks("s-nullname")
    yield
      assertEquals(tasks.size, 1)
      assertEquals(tasks.head.name, None)

  test("restart semantics: fresh store instance lists identical pending set from disk"):
    for
      _ <- reset()
      a <- IO(ScheduledTask.create("s1", "task-a", future, None, None, Some("routine-a")))
      _ <- taskStore.addTask(a)
      fresh = new ScheduledTaskStore(tempRoot / "scheduled-tasks") // 模拟重启后新实例
      pending <- fresh.getAllPendingTasks
    yield
      assertEquals(pending.map(_.id), List(a.id))
      assertEquals(pending.head.name, Some("routine-a"))

  // ==================================================================
  // 2. upsert：同 name 再建=替换不叠加（跨会话全局收敛）
  // ==================================================================

  test("upsert by name removes same-name tasks across ALL sessions — converges to single"):
    for
      _ <- reset()
      a <- IO(ScheduledTask.create("s1", "old copy 1", future, None, Some("daily"), Some("dream-review")))
      b <- IO(ScheduledTask.create("s2", "old copy 2", future, None, Some("daily"), Some("dream-review")))
      c <- IO(ScheduledTask.create("s2", "unrelated", future, None, None, Some("other-routine")))
      _ <- taskStore.addTask(a)
      _ <- taskStore.addTask(b)
      _ <- taskStore.addTask(c)
      fresh <- IO(ScheduledTask.create("s3", "new dream review", future, None, Some("daily"), Some("dream-review")))
      removed <- taskStore.upsertTaskByName(fresh)
      pending <- taskStore.getAllPendingTasks
      s1 <- taskStore.loadTasks("s1")
      s2 <- taskStore.loadTasks("s2")
      s3 <- taskStore.loadTasks("s3")
    yield
      // 旧同 name 任务全部作废（跨 s1/s2），返回值报告被替换者
      assertEquals(removed.map(_.id).toSet, Set(a.id, b.id))
      // 收敛单份：全库 dream-review 只剩新任务
      assertEquals(pending.count(_.name.contains("dream-review")), 1)
      assert(pending.exists(t => t.id == fresh.id && t.sessionId == "s3"), "new task must be the surviving one")
      // 无关任务不动
      assertEquals(s1, Nil)
      assertEquals(s2.map(_.id), List(c.id))
      assertEquals(s3.map(_.id), List(fresh.id))

  test("upsert is idempotent — re-arm with same name never stacks"):
    for
      _ <- reset()
      first <- IO(ScheduledTask.create("s1", "v1", future, None, None, Some("daily-review")))
      _ <- taskStore.upsertTaskByName(first)
      second <- IO(ScheduledTask.create("s2", "v2", future + 1, None, None, Some("daily-review")))
      _ <- taskStore.upsertTaskByName(second)
      third <- IO(ScheduledTask.create("s3", "v3", future + 2, None, None, Some("daily-review")))
      _ <- taskStore.upsertTaskByName(third)
      pending <- taskStore.getAllPendingTasks
    yield assertEquals(pending.map(_.content), List("v3"))

  test("unnamed task upsert behaves as plain add — legacy callers unchanged"):
    for
      _ <- reset()
      x1 <- IO(ScheduledTask.create("s1", "nameless 1", future, None, None, None))
      x2 <- IO(ScheduledTask.create("s1", "nameless 2", future + 1, None, None, None))
      _ <- taskStore.upsertTaskByName(x1)
      _ <- taskStore.upsertTaskByName(x2)
      pending <- taskStore.getAllPendingTasks
    yield assertEquals(pending.map(_.content).sorted, List("nameless 1", "nameless 2"))

  // ==================================================================
  // 3. cancel：取消后零触发 + list 即时消失（跨会话按 id）
  // ==================================================================

  test("cancelled task never fires — getAllDueTasks drops it permanently"):
    val due = System.currentTimeMillis() - 5_000
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "due soon", due, None, None, None))
      _ <- taskStore.addTask(t)
      before <- taskStore.getAllDueTasks
      _ <- taskStore.deleteTask("s1", t.id) // cancel = delete（无 tombstone，永不再见）
      afterDue <- taskStore.getAllDueTasks
      afterPending <- taskStore.getAllPendingTasks
    yield
      assertEquals(before.map(_.id), List(t.id), "must be due before cancel")
      assertEquals(afterDue, Nil, "cancelled task must never fire")
      assertEquals(afterPending, Nil, "cancelled task must vanish from list immediately")

  test("findTaskById works across sessions; unknown id returns None"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s-origin", "cross session target", future, None, None, Some("x")))
      _ <- taskStore.addTask(t)
      found <- taskStore.findTaskById(t.id)
      missing <- taskStore.findTaskById("no-such-id")
    yield
      assertEquals(found.map(_.id), Some(t.id))
      assertEquals(found.map(_.sessionId), Some("s-origin"))
      assertEquals(missing, None)

  // ==================================================================
  // 4. list：跨会话全量待触发
  // ==================================================================

  test("pending list is cross-session (store layer unsorted; sorting is the tool's job)"):
    for
      _ <- reset()
      later <- IO(ScheduledTask.create("s1", "later", future + 5_000, None, Some("weekly"), None))
      earlier <- IO(ScheduledTask.create("s2", "earlier", future - 5_000, None, None, None))
      _ <- taskStore.addTask(later)
      _ <- taskStore.addTask(earlier)
      pending <- taskStore.getAllPendingTasks
    yield
      assertEquals(pending.map(_.content).toSet, Set("earlier", "later"))
      assertEquals(pending.map(_.sessionId).distinct.size, 2, "must span sessions")

  // ==================================================================
  // 5. 工具面：action ∈ {create(默认), list, cancel}（单条目）
  // ==================================================================

  private val wsSent = scala.collection.mutable.ListBuffer.empty[Json]

  private def mkCtx(store: ScheduledTaskStore, sessionId: Option[String] = Some("s-tool")): ToolContext =
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "schedule-actions-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      scheduledTaskStore = store,
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

  test("tool action=list shows cross-session pending tasks with id/time/repeat/status"):
    for
      _ <- reset()
      paused <- IO(ScheduledTask.create("s1", "paused routine", future, None, Some("daily"), Some("paused-one")))
      _ <- taskStore.addTask(paused)
      _ <- taskStore.toggleTask("s1", paused.id)
      live <- IO(ScheduledTask.create("s2", "梦境日审任务", future + 1_000, None, Some("daily"), Some("dream-review")))
      _ <- taskStore.addTask(live)
      res <- IO(callTool(Json.obj("action" -> "list".asJson), mkCtx(taskStore)))
    yield
      val text = res.toOption.get
      assert(text.contains("Pending scheduled tasks (2)"), text)
      assert(text.contains(paused.id) && text.contains("PAUSED"), text)
      assert(text.contains(live.id) && text.contains("daily") && text.contains("dream-review"), text)
      assert(text.contains(live.content.take(10)), text)
      assert(text.contains("cancel"), "must tell the LLM how to cancel")
      assert(text.indexOf(paused.id) < text.indexOf(live.id), s"tool list must be triggerAt-sorted:\n$text")

  test("tool action=list with zero tasks says so"):
    for
      _ <- reset()
      res <- IO(callTool(Json.obj("action" -> "list".asJson), mkCtx(taskStore)))
    yield assert(res.toOption.get.toLowerCase.contains("no pending"), res.toString)

  test("tool cancel with unknown id errors and lists pending ids (self-healing)"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "existing", future, None, None, None))
      _ <- taskStore.addTask(t)
      res <- IO(callTool(Json.obj("action" -> "cancel".asJson, "id" -> "bogus123".asJson), mkCtx(taskStore)))
    yield
      val err = res.left.toOption.get.message
      assert(err.contains("bogus123"), err)
      assert(err.contains(t.id), s"error must list pending ids for recovery: $err")

  test("tool cancel removes the task (zero fire, list gone, WS broadcast)"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s1", "to be cancelled", future, None, Some("daily"), Some("routine")))
      _ <- taskStore.addTask(t)
      res <- IO(callTool(Json.obj("action" -> "cancel".asJson, "id" -> t.id.asJson), mkCtx(taskStore)))
      due <- taskStore.getAllDueTasks
      pending <- taskStore.getAllPendingTasks
    yield
      val text = res.toOption.get
      assert(text.startsWith("Cancelled task"), text)
      assert(text.contains("will not fire"), text)
      assertEquals(due, Nil, "cancelled recurring task must never fire")
      assertEquals(pending, Nil, "cancelled task must vanish from list immediately")
      assert(wsSent.exists(j => j.hcursor.downField("type").as[String].toOption.contains("scheduledTaskDeleted") &&
        j.hcursor.downField("id").as[String].toOption.contains(t.id)), s"WS delete broadcast missing: $wsSent")

  test("tool cancel works cross-session (task lives in another session file)"):
    for
      _ <- reset()
      t <- IO(ScheduledTask.create("s-other", "far away task", future, None, None, None))
      _ <- taskStore.addTask(t)
      res <- IO(callTool(Json.obj("action" -> "cancel".asJson, "id" -> t.id.asJson), mkCtx(taskStore, sessionId = Some("s-tool"))))
      pending <- taskStore.getAllPendingTasks
    yield
      assert(res.isRight, res.toString)
      assertEquals(pending, Nil)

  test("tool create with name replaces existing same-name task (upsert via tool)"):
    for
      _ <- reset()
      old <- IO(ScheduledTask.create("s-old", "old dream review", future, None, Some("daily"), Some("dream-review")))
      _ <- taskStore.addTask(old)
      res <- IO(callTool(
        Json.obj(
          "content" -> "新的梦境日审".asJson,
          "triggerAt" -> (future + 60_000L).asJson,
          "repeat" -> "daily".asJson,
          "name" -> "dream-review".asJson
        ),
        mkCtx(taskStore)
      ))
      pending <- taskStore.getAllPendingTasks
    yield
      val text = res.toOption.get
      assert(text.startsWith("Scheduled task"), "success message prefix must stay stable")
      assert(text.contains("replaced"), s"must report the replacement: $text")
      assert(text.contains(old.id), s"must name the replaced task id: $text")
      // 收敛单份 + 不双份注入的前提：全库只剩一条该 name 的待触发任务
      assertEquals(pending.count(_.name.contains("dream-review")), 1)
      // WS：旧任务广播删除、新任务广播创建（前端面板同步）
      assert(wsSent.exists(j => j.hcursor.downField("type").as[String].toOption.contains("scheduledTaskDeleted") &&
        j.hcursor.downField("id").as[String].toOption.contains(old.id)), s"old task delete broadcast missing: $wsSent")
      assert(wsSent.exists(j => j.hcursor.downField("type").as[String].toOption.contains("scheduledTaskCreated") &&
        j.hcursor.downField("task").downField("name").as[String].toOption.contains("dream-review")),
        s"created broadcast must carry name: $wsSent")

  test("tool create without action/name keeps legacy behavior (plain add, no dedup)"):
    for
      _ <- reset()
      r1 <- IO(callTool(
        Json.obj("content" -> "one-off a".asJson, "triggerAt" -> future.asJson),
        mkCtx(taskStore)
      ))
      r2 <- IO(callTool(
        Json.obj("content" -> "one-off b".asJson, "triggerAt" -> (future + 1).asJson),
        mkCtx(taskStore)
      ))
      pending <- taskStore.getAllPendingTasks
    yield
      assert(r1.toOption.get.startsWith("Scheduled task"), r1.toString)
      assert(r2.toOption.get.startsWith("Scheduled task"), r2.toString)
      assertEquals(pending.size, 2)

  test("tool rejects unknown action values"):
    val res = callTool(Json.obj("action" -> "purge".asJson), mkCtx(taskStore))
    val err = res.left.toOption.get.message
    assert(err.contains("purge") && err.contains("create"), err)

  test("tool create still requires content and future triggerAt"):
    for
      _ <- reset()
      noContent <- IO(callTool(Json.obj("action" -> "create".asJson, "triggerAt" -> future.asJson), mkCtx(taskStore)))
      pastTrigger <- IO(callTool(
        Json.obj("content" -> "x".asJson, "triggerAt" -> (System.currentTimeMillis() - 60_000L).asJson),
        mkCtx(taskStore)
      ))
    yield
      assert(noContent.left.toOption.get.message.contains("content"), noContent.toString)
      assert(pastTrigger.left.toOption.get.message.contains("future"), pastTrigger.toString)

  test("tool cancel requires id"):
    val res = callTool(Json.obj("action" -> "cancel".asJson), mkCtx(taskStore))
    assert(res.left.toOption.get.message.contains("id"), res.toString)

end ScheduleActionsSpec
