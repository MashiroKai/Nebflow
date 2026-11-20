package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * 待办体系统一批（2026-11-04）·真源扩展：`priority` + `completedAt`（软删）+ 触发面
 * 硬闸，全部在**存储/模型层**钉死（handler 帧形由 `gateway/TodoUnifyFramesSpec` 钉）。
 *
 * 断言面：
 *  1. priority 落库与钳位（0..3，create 单点；roundtrip 持久化）
 *  2. 老库 JSON 兼容读（无 priority / completedAt 键 → 0 / None，零迁移）
 *  3. 完成 = 软删：行留在库里、completedAt 置位、done 面不再返回（但可 opt-in）
 *  4. 取消完成 = 清 completedAt（幂等：再清一次不变）
 *  5. deleteTask 仍是物理删（语义不变）
 *  6. 🔴 已完成 todo 绝不被触发（getAllDueTodos / getAllDueTasks / getAllPendingTasks /
 *     getPendingCount 四口 + surfaceTodos 的二道闸）
 *  7. 收件箱排序键可从数据算出（priority 降序 → triggerAt 升序，已完成殿后）
 */
class TodoPriorityCompletionSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-todo-priority"
  private val store = new ScheduledTaskStore(tempRoot / "scheduled-tasks")

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "scheduled-tasks")
    PathUtil.setDataRoot(tempRoot)

  private val now = System.currentTimeMillis()
  private val past = now - 60_000L
  private val future = now + 3_600_000L

  private def todo(
    id: String,
    triggerAt: Long = past,
    priority: Int = 0,
    completedAt: Option[Long] = None,
    content: String = "把周报模板整理进 memory"
  ): ScheduledTask =
    ScheduledTask(
      id = id,
      sessionId = "s1",
      content = content,
      triggerAt = triggerAt,
      createdAt = 1_000L,
      kind = Some(ScheduledTask.KindTodo),
      priority = priority,
      completedAt = completedAt
    )

  // ==================================================================
  // ① priority 落库与钳位
  // ==================================================================

  test("① create 钳位 0..3：越界收敛（9→3 / -4→0 / 缺省→0），kind=task 同点不炸") {
    assertEquals(ScheduledTask.clampPriority(9), 3)
    assertEquals(ScheduledTask.clampPriority(-4), 0)
    assertEquals(ScheduledTask.clampPriority(2), 2)
    assertEquals(
      ScheduledTask.create("s1", "c", future, kind = Some("todo"), priority = 9).priority,
      3
    )
    assertEquals(
      ScheduledTask.create("s1", "c", future, kind = Some("todo"), priority = -4).priority,
      0
    )
    // 缺省：待办与定时任务都 0（定时任务面语义零改动）
    assertEquals(ScheduledTask.create("s1", "c", future, kind = Some("todo")).priority, 0)
    assertEquals(ScheduledTask.create("s1", "c", future).priority, 0)
  }

  test("① priority / completedAt 随任务 roundtrip 持久化（file 重读）") {
    for
      _ <- store.addTask(todo("a", priority = 2, completedAt = None))
      _ <- store.addTask(todo("b", priority = 0, completedAt = Some(1712345678000L)))
      fresh = new ScheduledTaskStore(tempRoot / "scheduled-tasks")
      tasks <- fresh.loadTasks("s1")
    yield
      val a = tasks.find(_.id == "a").getOrElse(fail("a 未落库"))
      val b = tasks.find(_.id == "b").getOrElse(fail("b 未落库"))
      assertEquals(a.priority, 2)
      assertEquals(a.completedAt, None)
      assertEquals(b.completedAt, Some(1712345678000L))
      assert(b.isCompleted)
  }

  // ==================================================================
  // ② 老库 JSON 兼容读（缺键 → 默认值，零迁移）
  // ==================================================================

  test("② 老库 JSON 无 priority / completedAt 键 ⇒ 0 / None（withDefaults 零迁移）") {
    val legacy = Json.obj(
      "id" -> Json.fromString("legacy-1"),
      "sessionId" -> Json.fromString("s1"),
      "content" -> Json.fromString("缴房租"),
      "triggerAt" -> Json.fromLong(past),
      "createdAt" -> Json.fromLong(1_000L),
      "kind" -> Json.fromString("todo")
    )
    decode[ScheduledTask](legacy.noSpaces) match
      case Right(t) =>
        assertEquals(t.priority, 0, "缺 priority 键 → 0")
        assertEquals(t.completedAt, None, "缺 completedAt 键 → None（未完成）")
        assertEquals(t.isCompleted, false)
      case Left(e) => fail(e.getMessage)
  }

  test("② 老库文件（整文件缺新键）经 store 读出：待办仍在、字段回默认、不被当成已完成") {
    val raw = Json.arr(
      Json.obj(
        "id" -> Json.fromString("legacy-1"),
        "sessionId" -> Json.fromString("s1"),
        "content" -> Json.fromString("缴房租"),
        "triggerAt" -> Json.fromLong(past),
        "createdAt" -> Json.fromLong(1_000L),
        "kind" -> Json.fromString("todo")
      )
    )
    for
      _ <- IO(os.write.over(tempRoot / "scheduled-tasks" / "s1.json", raw.noSpaces))
      tasks <- store.loadTasks("s1")
      due <- store.getAllDueTodos
    yield
      assertEquals(tasks.length, 1, "老文件必须整体可解码（不是解码失败 ⇒ Nil）")
      assertEquals(tasks.head.priority, 0)
      assertEquals(tasks.head.completedAt, None)
      assertEquals(due.map(_.id), List("legacy-1"), "未完成的老待办照常到期浮出")
  }

  // ==================================================================
  // ③ 完成 = 软删：行不物理删除 + completedAt 置位 + 收件箱默认面消失
  // ==================================================================

  test("③ 完成（completedAt 置位）：行仍在库里，默认读口不再返回，includeCompleted 可见") {
    val doneAt = now - 1_000L
    for
      _ <- store.addTask(todo("a", priority = 2))
      _ <- store.addTask(todo("b", priority = 0))
      changed <- store.updateTask("s1", "a")(_.copy(completedAt = Some(doneAt)))
      onDisk <- store.loadTasks("s1")
      openDefault <- store.getAllTodos
      openIncluding <- store.getAllTodosIncludingCompleted
      pending <- store.getAllPendingTasks
      count <- store.getPendingCount("s1")
    yield
      assert(changed)
      assertEquals(onDisk.length, 2, "软删：行未被物理删除")
      assertEquals(onDisk.find(_.id == "a").flatMap(_.completedAt), Some(doneAt))
      assertEquals(openDefault.map(_.id), List("b"), "默认收件箱/收件面不含已完成")
      assertEquals(openIncluding.map(_.id).sorted, List("a", "b"), "opt-in 视图含已完成")
      assertEquals(pending.map(_.id), List("b"), "已完成不算 pending")
      assertEquals(count, 1, "getPendingCount 不算已完成")
    end for
  }

  // ==================================================================
  // ④ 取消完成 = 清 completedAt（幂等）
  // ==================================================================

  test("④ 取消完成：completedAt 清空 ⇒ 重回未完成面；再清一次不变（幂等）") {
    for
      _ <- store.addTask(todo("a", completedAt = Some(now - 1_000L)))
      before <- store.getAllTodos
      _ <- store.updateTask("s1", "a")(_.copy(completedAt = None))
      after <- store.getAllTodos
      _ <- store.updateTask("s1", "a")(_.copy(completedAt = None))
      afterTwice <- store.getAllTodos
      onDisk <- store.loadTasks("s1")
    yield
      assertEquals(before, Nil, "已完成行不在默认可写面")
      assertEquals(after.map(_.id), List("a"), "取消完成后回到收件面")
      assertEquals(afterTwice.map(_.id), List("a"), "幂等：再取消一次仍是同态")
      assertEquals(onDisk.length, 1, "取消完成不产生新行（仍是同一行）")
      assertEquals(onDisk.head.completedAt, None)
  }

  // ==================================================================
  // ⑤ 删除仍是物理删（语义不变）
  // ==================================================================

  test("⑤ deleteTask 语义不变：整行物理消失（含已完成的软删行也可被硬删）") {
    for
      _ <- store.addTask(todo("a"))
      _ <- store.addTask(todo("b", completedAt = Some(now - 1_000L)))
      _ <- store.deleteTask("s1", "a")
      _ <- store.deleteTask("s1", "b")
      onDisk <- store.loadTasks("s1")
      all <- store.getAllTodosIncludingCompleted
    yield
      assertEquals(onDisk, Nil, "物理删：文件里零行（不是 completedAt 置位）")
      assertEquals(all, Nil)
  }

  // ==================================================================
  // ⑥ 🔴 已完成 todo 绝不被触发（触发面硬闸）
  // ==================================================================

  test("⑥ 已完成 todo 不被浮出：getAllDueTodos 排除（到期也未完成者照常返回）") {
    for
      _ <- store.addTask(todo("open", triggerAt = past))
      _ <- store.addTask(todo("done", triggerAt = past, completedAt = Some(now - 1_000L)))
      due <- store.getAllDueTodos
      dueTasks <- store.getAllDueTasks
      allTodos <- store.getAllTodos
    yield
      assertEquals(due.map(_.id), List("open"), "已完成待办绝不进浮出列表（scheduler 唯一入口）")
      assertEquals(dueTasks, Nil, "getAllDueTasks 本就排除待办（双面化语义不变）")
      assertEquals(allTodos.map(_.id), List("open"), "digest 内容源同样不含已完成")
  }

  test("⑥ surfaceTodos 二道闸：喂进已完成行也剔掉（digest 不含它、不涨 missCount）") {
    val open = todo("open", triggerAt = past)
    val done = todo("done", triggerAt = past, completedAt = Some(now - 1_000L))
    val survivors = ScheduledTaskService.surfacedTodos(List(open, done))
    assertEquals(survivors.map(_.id), List("open"), "已完成行被二道闸剔除（治 surfaceTodos 直喂）")
    val payload = ScheduledTaskService.digestPayload(survivors, now)
    assert(payload.contains("[id: open]"), "未完成行仍进 digest")
    assert(!payload.contains("[id: done]"), "已完成行绝不进 digest——否则用户面会看到已完成待办")
    // 顺延与 missCount 只作用在 survivors 上（surfaceDigest 的 dueTodos 参数即它）
    assertEquals(survivors.head.missCount, 0)
  }

  // ==================================================================
  // ⑦ 排序键可从数据算出（服务端权威）
  // ==================================================================

  test("⑦ todoSortKey：未完成优先 → priority 降序 → triggerAt 升序 → createdAt 升序") {
    val rows = List(
      todo("low-early", triggerAt = 100L, priority = 1),
      todo("high-late", triggerAt = 900L, priority = 3),
      todo("high-early", triggerAt = 500L, priority = 3),
      todo("none", triggerAt = 100L, priority = 0),
      todo("done-high", triggerAt = 100L, priority = 3, completedAt = Some(now))
    )
    val ordered = rows.sortBy(ScheduledTask.todoSortKey).map(_.id)
    assertEquals(
      ordered,
      List("high-early", "high-late", "low-early", "none", "done-high"),
      "同 priority 按 triggerAt 升序；已完成一律殿后"
    )
  }

  test("FDC-SCN-007 tool rearm keeps user and unknown-origin todos") {
    val root = os.Path(java.nio.file.Files.createTempDirectory("data-owner-test"))
    val ledger = new ScheduledTaskStore(root)
    val base = ScheduledTask.create("s1", "body", 1000L, name = Some("same"), kind = Some("todo"))
    val human = base.copy(id = "human", source = Some("user"))
    val unknown = base.copy(id = "unknown")
    val tool = base.copy(id = "tool1", source = Some("tool"))
    val replacement = base.copy(id = "tool2", source = Some("tool"))
    val result = (for
      _ <- ledger.addTask(human)
      _ <- ledger.addTask(unknown)
      _ <- ledger.addTask(tool)
      rejected <- ledger.upsertTaskByName(replacement).attempt
      rows <- ledger.loadTasks("s1")
      _ <- ledger.deleteTask("s1", "unknown")
      rearm <- ledger.upsertTaskByName(replacement)
      updated <- ledger.loadTasks("s1")
    yield (rejected, rows, rearm, updated)).unsafeRunSync()
    assert(result._1.isLeft)
    assertEquals(result._2.map(_.id).toSet, Set("human", "unknown", "tool1"))
    // The rearm keeps the record it already owns (stable identity), not the request's id.
    assertEquals(result._3.saved.id, "tool1")
    assertEquals(result._3.replaced.map(_.id), List("tool1"))
    assertEquals(result._4.map(_.id).toSet, Set("human", "tool1"))
  }

  test("FDC-SCN-011 cross-session rearm moves the owned record with its id and no loss") {
    val root = os.Path(java.nio.file.Files.createTempDirectory("data-cross-test"))
    val ledger = new ScheduledTaskStore(root)
    val old = ScheduledTask.create("old", "before", 1000L, name = Some("routine")).copy(source = Some("tool"))
    val fresh = ScheduledTask
      .create("new", "after", 2000L, name = Some("routine"))
      .copy(id = "requested-id", source = Some("tool"))
    val result = (for
      _ <- ledger.addTask(old)
      rearm <- ledger.upsertTaskByName(fresh, ScheduledTaskStore.toolOwnsLegacyNamedTask)
      before <- ledger.loadTasks("old")
      after <- ledger.loadTasks("new")
    yield (rearm, before, after)).unsafeRunSync()
    assertEquals(result._1.saved.id, old.id, "rearm must preserve the owned record's id")
    assertEquals(result._1.saved.sessionId, "new")
    assertEquals(result._1.saved.content, "after")
    assertEquals(result._2, Nil, "the source session no longer holds the moved record")
    assertEquals(result._3.map(_.id), List(old.id), "the destination holds exactly one copy")
  }

end TodoPriorityCompletionSpec
