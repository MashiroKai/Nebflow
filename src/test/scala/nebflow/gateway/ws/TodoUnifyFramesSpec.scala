package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.core.scheduler.{DoneRecord, ScheduledTask, ScheduledTaskStore}
import nebflow.service.ConfigService
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * 待办体系统一批（2026-11-04）·**帧级契约钉**：`WsTasksWorkspaceHandlers` 的四条
 * 待办读写腿（create / completeTodo / uncompleteTodo / delete / update / list）。
 *
 * 为什么在帧层钉而不是只钉 store：下游两个前端节点（胶囊 / 面板）照这份报告施工，
 * 它们消费的是**帧型与行字段**——store 语义绿不等于帧契约对。本 spec 走
 * `WsDispatch.handlers(<msgType>)` 真入口（真 handler + 真 store + 真 DoneRecord
 * JSONL，只有 ctx 的无关依赖按既有 `ChatCtx`/`BrowseCtx` 纪律置 null）。
 *
 * 覆盖：① create 收 priority 并钳位落库；② list scope:all 的字段/排序/已完成过滤
 * 与 includeCompleted opt-in；③ completeTodo = 软删（行留库 + completedAt 置位 +
 * DoneRecord 一条 via:"ws"）+ 新帧 scheduledTaskCompleted 与老帧 scheduledTaskDeleted
 * **双发**（老前端零改动）；④ completeTodo 幂等（重复打卡不虚涨周报）；⑤
 * uncompleteTodo 清完成态 + via:"undo" 反向事件 + 幂等；⑥ deleteScheduledTask 仍物理
 * 删；⑦ updateScheduledTask 收 priority（定时任务面无副作用）；⑧ 已完成 todo 不被
 * 浮出（跨层收口）。
 */
class TodoUnifyFramesSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-todo-unify-frames"
  private val store = new ScheduledTaskStore(tempRoot / "scheduled-tasks")
  private val originalRoot = PathUtil.dataRoot

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "scheduled-tasks")
    PathUtil.setDataRoot(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    if os.exists(tempRoot) then os.remove.all(tempRoot)

  private val now = System.currentTimeMillis()
  private val past = now - 60_000L

  private def todo(
    id: String,
    triggerAt: Long = past,
    priority: Int = 0,
    completedAt: Option[Long] = None,
    sessionId: String = "s1"
  ): ScheduledTask =
    ScheduledTask(
      id = id,
      sessionId = sessionId,
      content = s"待办 $id",
      triggerAt = triggerAt,
      createdAt = 1_000L,
      kind = Some(ScheduledTask.KindTodo),
      priority = priority,
      completedAt = completedAt
    )

  /**
   * 只喂待办读写腿的 ctx（`WsBrowseDeviceLegSpec.browseCtx` / `ChainViewWsEntrySpec.chatCtx`
   *  同款 null 占位纪律：腿读不到的依赖刻意置 null，越界即 NPE 报警，不静默蒙混）。
   */
  private def ctx(res: SharedResources): WsDispatchCtx =
    WsDispatchCtx(
      logger = NebflowLogger.forName("todo.unify.frames.spec"),
      sessionService = null,
      agentService = null,
      configService = ConfigService,
      configRef = null,
      sessionStore = null,
      sharedResources = res,
      mcpManager = null,
      sttServiceRef = null,
      textSearch = null,
      sessionTextBuffers = null,
      sessionThinkingBuffers = null,
      sessionTurnStarts = null,
      replayPendingAsksImpl = null,
      listAllPendingAsksImpl = null,
      applyPermissionUpgradeImpl = null,
      persistGlobalSafetyModeImpl = null,
      removeRootAgentImpl = null,
      stopChildDelegateActorsImpl = null,
      forwardInteractionAnswerImpl = null,
      isRootScopeSessionImpl = null,
      compactThresholdInfoImpl = null,
      ensureAgentImpl = null,
      maybeSessionKickImpl = null,
      broadcastServerConfigImpl = null,
      persistThinkingConfigImpl = null,
      persistWorkScheduleImpl = null,
      persistMcpServerEnabledImpl = null,
      broadcastMcpServersUpdateImpl = null,
      sendAgentSessionListImpl = null,
      sendAgentSessionListByNameImpl = null,
      sendMemoryStatusImpl = null,
      sendToolsListImpl = null,
      expandTildeImpl = null,
      wsBrowseEventImpl = null,
      browseResultFrameImpl = null,
      browseErrorFrameImpl = null,
      browseEntryCapImpl = 0,
      resolveExplorerBaseRootImpl = null,
      admitWorkOrRefuseImpl = null,
      handleUserTextImpl = null,
      deviceProvenanceImpl = null,
      skipCurrentFreezeWindowImpl = null,
      executeAskImpl = null,
      executeSkillImpl = null,
      resolveRootSessionIdImpl = null,
      projectCreateBroadcastImpl = null
    )

  /**
   * 起一套隔离资源（dataRoot 已在 beforeEach 指到 temp ⇒ scheduledTaskStore /
   *  doneRecordStore 都落在 temp 下，零写真实 ~/.stand）。
   */
  private def withRig[A](f: (WsDispatchCtx, SharedResources) => IO[A]): IO[A] =
    for
      system <- IO(ActorSystem(s"todo-unify-${scala.util.Random.nextInt(1000000)}"))
      res0 <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      res = res0.copy(scheduledTaskStore = store)
      a <- f(ctx(res), res)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield a

  /** 发一帧，收集该处理器回给这条连接的全部帧。 */
  private def send(ctx: WsDispatchCtx, msgType: String, frame: String): IO[List[Json]] =
    for
      frames <- Ref.of[IO, List[Json]](Nil)
      watch = new ExplorerWatchSession(_ => IO.unit, ctx.logger, live = false)
      _ <- WsDispatch.handlers(msgType)(ctx, frame, (j: Json) => frames.update(_ :+ j), watch)
      out <- frames.get
    yield out

  private def types(frames: List[Json]): List[String] =
    frames.flatMap(_.hcursor.get[String]("type").toOption)

  private def rowOf(frames: List[Json], idx: Int = 0): Json =
    frames.lift(idx).flatMap(_.hcursor.downField("task").focus).getOrElse(Json.Null)

  // ==================================================================
  // ① createScheduledTask 收 priority（钳位落库）
  // ==================================================================

  test("① createScheduledTask(kind=todo, priority=7) ⇒ 落库 3（钳位）+ created 帧带三键") {
    withRig { (c, res) =>
      for
        frames <- send(
          c,
          "createScheduledTask",
          """{"type":"createScheduledTask","sessionId":"s1","kind":"todo","content":"买牛奶","priority":7}"""
        )
        onDisk <- res.scheduledTaskStore.loadTasks("s1")
      yield
        assertEquals(types(frames), List("scheduledTaskCreated"))
        assertEquals(onDisk.length, 1)
        assertEquals(onDisk.head.priority, 3, "越界 priority 钳位到 3 后落库")
        val task = rowOf(frames)
        assertEquals(task.hcursor.get[Int]("priority").toOption, Some(3), "created 帧回带 priority")
        assertEquals(task.hcursor.get[Boolean]("completed").toOption, Some(false))
        assert(task.hcursor.downField("completedAt").focus.contains(Json.Null), "未完成 ⇒ completedAt:null")
        // 存量键一个不少（老前端零感知）
        assert(task.hcursor.downField("id").focus.isDefined)
        assert(task.hcursor.downField("project").focus.isDefined)
    }
  }

  test("FDC-SCN-006 same-name user creates append independent records") {
    withRig { (c, res) =>
      val frame = """{"type":"createScheduledTask","sessionId":"s1","kind":"todo","name":"same","content":"first"}"""
      for
        first <- send(c, "createScheduledTask", frame)
        second <- send(c, "createScheduledTask", frame.replace("first", "second"))
        rows <- res.scheduledTaskStore.loadTasks("s1")
      yield
        assertEquals(rows.length, 2)
        assertEquals(rows.map(_.id).distinct.length, 2)
        assertEquals(rows.map(_.content).toSet, Set("first", "second"))
        assert(rows.forall(_.source.contains("user")))
        assertEquals(types(first), List("scheduledTaskCreated"))
        assertEquals(types(second), List("scheduledTaskCreated"))
    }
  }

  test("FTI-SCN-007 correlated create retry returns same durable row and rejects identity collision") {
    withRig { (c, res) =>
      val id = "b642c22a-e761-48c2-9f29-4237263bc973"
      val frame =
        s"""{"type":"createScheduledTask","sessionId":"s1","kind":"todo","name":"same","content":"first","requestId":"$id"}"""
      for
        first <- send(c, "createScheduledTask", frame)
        second <- send(c, "createScheduledTask", frame)
        conflict <- send(c, "createScheduledTask", frame.replace("first", "different"))
        rows <- res.scheduledTaskStore.loadTasks("s1")
      yield
        assertEquals(types(first), List("scheduledTaskCreated"))
        assertEquals(types(second), List("scheduledTaskCreated"))
        assertEquals(types(conflict), List("error"))
        assertEquals(rows.size, 1)
        assertEquals(rows.head.content, "first")
        assertEquals(rowOf(first).hcursor.get[String]("id").toOption, Some(id))
        assertEquals(rowOf(second).hcursor.get[String]("id").toOption, Some(id))
        assertEquals(conflict.head.hcursor.get[String]("requestId").toOption, Some(id))
    }
  }

  test("FDC-SCN-026 a retry after completion reports the stored completed state") {
    withRig { (c, res) =>
      val id = "c9d1e2f3-a4b5-4c6d-8e7f-1029384756ab"
      val frame =
        s"""{"type":"createScheduledTask","sessionId":"s1","kind":"todo","name":"same","content":"first","requestId":"$id"}"""
      for
        _ <- send(c, "createScheduledTask", frame)
        _ <- res.scheduledTaskStore.updateTask("s1", id)(_.copy(completedAt = Some(1234L)))
        retry <- send(c, "createScheduledTask", frame)
        rows <- res.scheduledTaskStore.loadTasks("s1")
      yield
        assertEquals(types(retry), List("scheduledTaskCreated"))
        val task = rowOf(retry)
        assertEquals(task.hcursor.get[String]("id").toOption, Some(id))
        assertEquals(
          task.hcursor.get[Boolean]("completed").toOption,
          Some(true),
          "the receipt must not replay the request's initial completed=false"
        )
        assertEquals(task.hcursor.get[Long]("completedAt").toOption, Some(1234L))
        assertEquals(rows.head.completedAt, Some(1234L), "the durable row is unchanged by the retry")
      end for
    }
  }

  // ==================================================================
  // ② list scope:all：字段 / 排序 / 已完成过滤 / includeCompleted opt-in
  // ==================================================================

  test("② listScheduledTasks scope:all：默认不含已完成；排序 priority 降序 → triggerAt 升序") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("low", triggerAt = 200L, priority = 1))
        _ <- res.scheduledTaskStore.addTask(todo("high-late", triggerAt = 900L, priority = 3))
        _ <- res.scheduledTaskStore.addTask(todo("high-early", triggerAt = 500L, priority = 3))
        _ <- res.scheduledTaskStore.addTask(todo("done", triggerAt = 100L, priority = 3, completedAt = Some(now)))
        def_ <- send(c, "listScheduledTasks", """{"type":"listScheduledTasks","sessionId":"s1","scope":"all"}""")
        all <- send(
          c,
          "listScheduledTasks",
          """{"type":"listScheduledTasks","sessionId":"s1","scope":"all","includeCompleted":true}"""
        )
      yield
        val defRows = def_.head.hcursor.downField("tasks").as[List[Json]].getOrElse(Nil)
        val ids = defRows.flatMap(_.hcursor.get[String]("id").toOption)
        assertEquals(ids, List("high-early", "high-late", "low"), "默认面：已完成剔除；同 priority 按 triggerAt 升序")
        assertEquals(def_.head.hcursor.get[Boolean]("includeCompleted").toOption, Some(false))
        // 行字段（三个新增键，纯增量）
        val row = defRows.head
        assertEquals(row.hcursor.get[Int]("priority").toOption, Some(3))
        assertEquals(row.hcursor.get[Boolean]("completed").toOption, Some(false))
        assert(row.hcursor.downField("completedAt").focus.contains(Json.Null))
        assertEquals(row.hcursor.get[String]("sessionId").toOption, Some("s1"))
        assert(row.hcursor.downField("project").focus.contains(Json.Null))
        assert(row.hcursor.downField("creator").focus.contains(Json.Null))
        // opt-in 面：已完成殿后返回
        val allRows = all.head.hcursor.downField("tasks").as[List[Json]].getOrElse(Nil)
        assertEquals(
          allRows.flatMap(_.hcursor.get[String]("id").toOption),
          List("high-early", "high-late", "low", "done"),
          "includeCompleted:true ⇒ 已完成行殿后返回"
        )
        assertEquals(all.head.hcursor.get[Boolean]("includeCompleted").toOption, Some(true))
        val doneRow = allRows.last
        assertEquals(doneRow.hcursor.get[Boolean]("completed").toOption, Some(true))
        assert(doneRow.hcursor.get[Long]("completedAt").toOption.isDefined, "已完成行带完成时刻")
    }
  }

  // ==================================================================
  // ③ completeTodo = 软删 + DoneRecord + 新老帧双发
  // ==================================================================

  test("decision payload round trips in list and cannot be completed without answer support") {
    withRig { (c, res) =>
      // 待办数据模型批（2026-11-18）：options 由裸字符串升级为结构化 DecisionOption
      // （title 必填、可选 media 叠卡）。
      val decision = nebflow.core.scheduler.TodoDecision(
        question = "Choose",
        options = List(
          nebflow.core.scheduler.DecisionOption(id = "o1", title = "A"),
          nebflow.core.scheduler.DecisionOption(id = "o2", title = "B")
        )
      )
      for
        _ <- res.scheduledTaskStore.addTask(todo("decision-1").copy(decision = Some(decision)))
        listed <- send(c, "listScheduledTasks", """{"type":"listScheduledTasks","sessionId":"s1","scope":"all"}""")
        frames <- send(c, "completeTodo", """{"type":"completeTodo","sessionId":"s1","id":"decision-1"}""")
        retry <- send(c, "completeTodo", """{"type":"completeTodo","sessionId":"s1","id":"decision-1"}""")
        onDisk <- res.scheduledTaskStore.loadTasks("s1")
        done <- res.doneRecordStore.loadAll
      yield
        val rows = listed.head.hcursor.downField("tasks").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        assertEquals(rows.head.hcursor.downField("decision").get[String]("question").toOption, Some("Choose"))
        // 待办数据模型批：三面判定由服务端下发（reminder/todo/task/engine_mirror）。
        assert(
          rows.head.hcursor.get[String]("todoClass").toOption.exists(Set("reminder", "todo", "task", "engine_mirror")),
          "列表行必须带 todoClass（服务端判定单点）"
        )
        assertEquals(types(frames), List("error"))
        assertEquals(types(retry), List("error"))
        assertEquals(onDisk.head.decision, Some(decision))
        assertEquals(onDisk.head.completedAt, None)
        assertEquals(done.length, 0)
      end for
    }
  }

  test("③ completeTodo：行未物删（completedAt 置位）、DoneRecord 一条 via:ws、新帧与老帧同发") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1", priority = 2))
        frames <- send(c, "completeTodo", """{"type":"completeTodo","sessionId":"s1","id":"t1"}""")
        onDisk <- res.scheduledTaskStore.loadTasks("s1")
        done <- res.doneRecordStore.loadAll
        open <- res.scheduledTaskStore.getAllTodos
      yield
        assertEquals(
          types(frames),
          List("scheduledTaskCompleted", "scheduledTaskDeleted"),
          "新帧 scheduledTaskCompleted + 老帧 scheduledTaskDeleted 双发（老前端零改动）"
        )
        val completedFrame = frames.head
        assertEquals(completedFrame.hcursor.get[String]("id").toOption, Some("t1"))
        assert(completedFrame.hcursor.get[Long]("completedAt").toOption.isDefined, "completed 帧带完成时刻")
        assertEquals(rowOf(frames).hcursor.get[Boolean]("completed").toOption, Some(true))
        // 老帧逐字沿用原形（id + sessionId）
        assertEquals(frames(1).hcursor.get[String]("id").toOption, Some("t1"))
        assertEquals(frames(1).hcursor.get[String]("sessionId").toOption, Some("s1"))
        // 真源：行还在，completedAt 置位
        assertEquals(onDisk.length, 1, "软删：行未被物理删除（可一键回退）")
        assert(onDisk.head.completedAt.isDefined, "completedAt 置位")
        // 周报数据源：一条 ws 痕
        assertEquals(done.length, 1)
        assertEquals(done.head.taskId, "t1")
        assertEquals(done.head.via, DoneRecord.ViaWs)
        // 收件箱默认面：已完成不进
        assertEquals(open, Nil)
    }
  }

  test("③ completeTodo 幂等：重复打卡不重复写 done 痕、不改首次完成时刻") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1"))
        _ <- send(c, "completeTodo", """{"type":"completeTodo","sessionId":"s1","id":"t1"}""")
        first <- res.scheduledTaskStore.loadTasks("s1").map(_.head.completedAt)
        _ <- send(c, "completeTodo", """{"type":"completeTodo","sessionId":"s1","id":"t1"}""")
        done <- res.doneRecordStore.loadAll
        second <- res.scheduledTaskStore.loadTasks("s1").map(_.head.completedAt)
      yield
        assertEquals(done.length, 1, "重复打卡不虚涨周报（DoneRecord 只一条）")
        assertEquals(first, second, "完成时刻沿用首次读数")
    }
  }

  // ==================================================================
  // ④ uncompleteTodo：清完成态 + via:undo + 幂等
  // ==================================================================

  test("④ uncompleteTodo：completedAt 清空、追加 via:undo 反向事件、广播 updated（completed:false）") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1", completedAt = Some(now - 5_000L)))
        _ <- res.doneRecordStore.record(DoneRecord("t1", None, "待办 t1", "s1", now - 5_000L, DoneRecord.ViaWs))
        frames <- send(c, "uncompleteTodo", """{"type":"uncompleteTodo","sessionId":"s1","id":"t1"}""")
        onDisk <- res.scheduledTaskStore.loadTasks("s1")
        done <- res.doneRecordStore.loadAll
        back <- res.scheduledTaskStore.getAllTodos
      yield
        assertEquals(types(frames), List("scheduledTaskUpdated"))
        val task = rowOf(frames)
        assertEquals(task.hcursor.get[Boolean]("completed").toOption, Some(false))
        assert(task.hcursor.downField("completedAt").focus.contains(Json.Null), "取消完成 ⇒ completedAt:null")
        assertEquals(onDisk.length, 1, "取消完成不产生新行")
        assertEquals(onDisk.head.completedAt, None)
        assertEquals(done.map(_.via), List(DoneRecord.ViaWs, DoneRecord.ViaUndo), "只增不改：加一条 undo 反向事件")
        assertEquals(done.last.taskId, "t1")
        assertEquals(back.map(_.id), List("t1"), "取消完成后重回收件箱默认面")
    }
  }

  test("④ uncompleteTodo 幂等：本来就未完成 ⇒ 不写 undo 痕，仍回 updated 收敛") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1"))
        frames <- send(c, "uncompleteTodo", """{"type":"uncompleteTodo","sessionId":"s1","id":"t1"}""")
        done <- res.doneRecordStore.loadAll
      yield
        assertEquals(types(frames), List("scheduledTaskUpdated"))
        assertEquals(done, Nil, "幂等：无完成痕可抵销时不写 undo")
    }
  }

  // ==================================================================
  // ⑤ deleteScheduledTask 语义不变（物理删）
  // ==================================================================

  test("⑤ deleteScheduledTask 仍物理删（帧 scheduledTaskDeleted，库里零行）") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1"))
        frames <- send(c, "deleteScheduledTask", """{"type":"deleteScheduledTask","sessionId":"s1","id":"t1"}""")
        onDisk <- res.scheduledTaskStore.loadTasks("s1")
      yield
        assertEquals(types(frames), List("scheduledTaskDeleted"))
        assertEquals(onDisk, Nil, "删除 = 物理删（与完成区分：完成是软删）")
    }
  }

  // ==================================================================
  // ⑥ updateScheduledTask 收 priority（定时任务面无副作用）
  // ==================================================================

  test("⑥ updateScheduledTask {priority:2}：待办落库并广播全量行；定时任务面 priority 无效") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1"))
        _ <- res.scheduledTaskStore.addTask(
          ScheduledTask(
            id = "task1",
            sessionId = "s1",
            content = "例行",
            triggerAt = now + 3_600_000L,
            createdAt = 1_000L
          )
        )
        frames <- send(
          c,
          "updateScheduledTask",
          """{"type":"updateScheduledTask","sessionId":"s1","id":"t1","priority":2}"""
        )
        onDisk <- res.scheduledTaskStore.loadTasks("s1")
        taskFrames <- send(
          c,
          "updateScheduledTask",
          """{"type":"updateScheduledTask","sessionId":"s1","id":"task1","priority":3}"""
        )
        taskOnDisk <- res.scheduledTaskStore.loadTasks("s1")
      yield
        assertEquals(types(frames), List("scheduledTaskUpdated"))
        assertEquals(onDisk.find(_.id == "t1").map(_.priority), Some(2), "priority patch 落库")
        assertEquals(rowOf(frames).hcursor.get[Int]("priority").toOption, Some(2))
        // 编译断言：广播行与 list 行同形（全量键）
        assert(rowOf(frames).hcursor.downField("completedAt").focus.isDefined)
        assertEquals(types(taskFrames), List("scheduledTaskUpdated"))
        assertEquals(
          taskOnDisk.find(_.id == "task1").map(_.priority),
          Some(0),
          "定时任务面 priority 恒 0（语义零改动）"
        )
    }
  }

  // ==================================================================
  // ⑦ 已完成 todo 不被触发（跨层收口：帧完成 → 触发面零候选）
  // ==================================================================

  test("⑦ completeTodo 后：该 todo 不出现在浮出候选（scheduler 触发面硬闸）") {
    withRig { (c, res) =>
      for
        _ <- res.scheduledTaskStore.addTask(todo("t1", triggerAt = past))
        before <- res.scheduledTaskStore.getAllDueTodos
        _ <- send(c, "completeTodo", """{"type":"completeTodo","sessionId":"s1","id":"t1"}""")
        after <- res.scheduledTaskStore.getAllDueTodos
        pending <- res.scheduledTaskStore.getAllPendingTasks
      yield
        assertEquals(before.map(_.id), List("t1"), "完成前到期待办在浮出面")
        assertEquals(after, Nil, "完成后同一读口零候选 ⇒ 绝不被 ScheduledTaskActor 触发")
        assertEquals(pending, Nil)
    }
  }

end TodoUnifyFramesSpec
