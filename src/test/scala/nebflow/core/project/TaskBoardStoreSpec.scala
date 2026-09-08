package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.tools.ToolError

import java.nio.file.Files
import java.time.Instant

/**
 * TaskBoardStore spec（TaskBoard 批 1：规格 §6 验收点 ③④⑤⑥ 的单测层 + 同族行为
 * 锁死）。参照 TaskListToolSpec 风格（munit FunSuite，每用例空库起）。
 *
 * 覆盖：
 *  - 全链路 create/update/close + 落盘字段完整 + 重建实例逐字段相等（验收④单测层）；
 *  - 四态迁移矩阵逐格（16 组合全断言；变异验红锚：改坏 isValidTransition 即红）；
 *  - 验收⑥同族行为：update 携带 done → TBOARD_DONE_VIA_CLOSE；blocks 未闭环
 *    in_progress/close → TBOARD_BLOCKED（附未闭环清单+逃生提示）；成环 →
 *    TBOARD_CYCLE（含自依赖）；重复 close → no-op 成功回显 closedAt；
 *  - 并发写零丢失（验收③）：同实例两 fiber 各 50 次 create 交错 → 2N+初始计数、
 *    并发 close 20 个全落盘、文件合法 JSON 且与内存读回一致；
 *  - 损坏容错：读损坏空视图零副作用；写损坏隔离 quarantaine + 空库续写；
 *  - 30 天惰性 prune（解析失败保守保留）；
 *  - 结构字段编辑语义（assignee/nodeId 空串清除、note 替换、blocks 全量替换）；
 *  - list 过滤（status/assignee）与紧凑行标记（@assignee/(→node)/⚠deps-open/
 *    ⚠node-done 经 nodeTerminal 纯映射）。
 */
class TaskBoardStoreSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-taskboard-spec"))

  override def afterAll(): Unit = os.remove.all(home)

  private def file: os.Path = home / ".nebflow" / "task-board.json"

  /** 每用例从空库开始（无内存态——删文件即全新 store；隔离文件一并清）。 */
  private def resetFile(): Unit =
    if os.exists(file) then os.remove(file)
    if os.exists(home / ".nebflow") then
      os.list(home / ".nebflow").filter(_.last.startsWith("task-board.json.corrupt")).foreach(os.remove)

  private def store: TaskBoardStore = TaskBoardStore.open("projA", home.toString)

  private def create(
    title: String,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocks: List[String] = Nil
  ): Either[ToolError, String] =
    store.createSync(title, assignee, nodeId, note, if blocks.isEmpty then None else Some(blocks))

  private def update(
    id: String,
    title: Option[String] = None,
    status: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocks: Option[List[String]] = None
  ): Either[ToolError, String] =
    store.updateSync(id, title, status, assignee, nodeId, note, blocks)

  private def readDisk: Option[TaskBoardStore.Store] =
    if os.exists(file) then decode[TaskBoardStore.Store](os.read(file)).toOption else None

  // ===== ① 全链路 + 持久化 + 重建逐字段相等（验收④单测层）=====

  test("create→update→close 全链路：字段完整落盘（含缺省 assignee=dispatcher）；重建实例逐字段相等"):
    resetFile()
    assert(create("设计方案").isRight)
    assert(create("实现后端", assignee = Some("n-impl"), nodeId = Some("n-impl"),
      note = Some("工作备注"), blocks = List("1")).isRight)
    // 依赖闸：#2 依赖 #1，先闭环 #1 才能开工（闸行为顺带验证）
    assert(store.closeSync("1").isRight)
    assert(update("2", status = Some("in_progress")).isRight)

    val disk = readDisk.get
    assertEquals(disk.version, 1, "envelope version")
    assertEquals(disk.tasks.map(_.id), List("1", "2"), "板内自增 id 1 起")
    // create 缺省 assignee = "dispatcher"（§2c）
    assertEquals(disk.tasks.find(_.id == "1").get.assignee, Some("dispatcher"))
    val e1 = disk.tasks.find(_.id == "1").get
    assertEquals(e1.status, "done")
    assert(e1.closedAt.isDefined, "close 记 closedAt")
    val e2 = disk.tasks.find(_.id == "2").get
    assertEquals(e2.status, "in_progress")
    assertEquals(e2.assignee, Some("n-impl"))
    assertEquals(e2.nodeId, Some("n-impl"))
    assertEquals(e2.note, Some("工作备注"))
    assertEquals(e2.blocks, List("1"))
    assert(e2.createdAt.isDefined && e2.updatedAt.isDefined)

    // 重启 = 重开实例（整读整写无内存态）：list 输出逐字节一致 + 磁盘逐字段一致
    val before = store.listSync().toOption.get
    val reopened = TaskBoardStore.open("projA", home.toString)
    assertEquals(reopened.listSync().toOption.get, before, "重建后 list 输出一致")
    assertEquals(readDisk.get, disk, "重建后磁盘逐字段相等")

  test("update：note 替换（非追加）、assignee/nodeId 空串清除、blocks 全量替换（空列表清空）、title 替换"):
    resetFile()
    create("任务A")
    create("任务B")
    assert(update("1", blocks = Some(List("2"))).isRight)
    assert(update("1", assignee = Some("n-x"), nodeId = Some("n-x"), note = Some("备注一")).isRight)
    var e = readDisk.get.tasks.find(_.id == "1").get
    assertEquals(e.assignee, Some("n-x"))
    assertEquals(e.nodeId, Some("n-x"))
    assertEquals(e.note, Some("备注一"))
    assertEquals(e.blocks, List("2"))
    // note 替换语义（追加是 close 专属）
    assert(update("1", note = Some("备注二")).isRight)
    e = readDisk.get.tasks.find(_.id == "1").get
    assertEquals(e.note, Some("备注二"))
    // 空串清除 assignee/nodeId（对齐 TaskList project 空串清除语义）
    assert(update("1", assignee = Some(""), nodeId = Some("")).isRight)
    e = readDisk.get.tasks.find(_.id == "1").get
    assertEquals(e.assignee, None)
    assertEquals(e.nodeId, None)
    // blocks 全量替换：空列表清空
    assert(update("1", blocks = Some(Nil)).isRight)
    assertEquals(readDisk.get.tasks.find(_.id == "1").get.blocks, Nil)
    // title 替换；空白 title 更新 = 保持原值
    assert(update("1", title = Some("新标题")).isRight)
    assert(update("1", title = Some("  ")).isRight)
    assertEquals(readDisk.get.tasks.find(_.id == "1").get.title, "新标题")

  // ===== ② 四态迁移矩阵逐格（变异验红锚：改坏 isValidTransition 即红）=====

  test("isValidTransition 迁移矩阵逐格：4 同态 no-op + 5 条合法边 + 其余 7 格全拒"):
    import TaskBoardStore.Status.*
    val legal = Set(
      (Open, InProgress), (Open, Blocked), (InProgress, Blocked),
      (Blocked, Open), (Blocked, InProgress))
    val states = List(Open, InProgress, Done, Blocked)
    states.foreach { from =>
      states.foreach { to =>
        val expected = if from == to then true else legal.contains(((from, to)))
        assertEquals(TaskBoardStore.isValidTransition(from, to), expected, s"$from→$to")
      }
    }

  test("合法迁移经 store 全通过 + 同态 no-op；未知 status 值拒绝（TBOARD_STATUS）"):
    resetFile()
    create("任务A")
    assert(update("1", status = Some("blocked")).isRight, "open→blocked")
    assert(update("1", status = Some("open")).isRight, "blocked→open")
    assert(update("1", status = Some("in_progress")).isRight, "open→in_progress")
    assert(update("1", status = Some("blocked")).isRight, "in_progress→blocked")
    assert(update("1", status = Some("in_progress")).isRight, "blocked→in_progress")
    assert(update("1", status = Some("in_progress")).isRight, "同态 no-op")
    assertEquals(readDisk.get.tasks.find(_.id == "1").get.status, "in_progress")
    val r = update("1", status = Some("paused"))
    assert(r.isLeft && r.swap.toOption.get.message.contains("TBOARD_STATUS"), r)

  // ===== ③ 验收⑥同族行为锁死 =====

  test("验收⑥：update 携带 done 结构化拒绝（TBOARD_DONE_VIA_CLOSE）；done 终态 done→任何拒绝；被拒不写盘"):
    resetFile()
    create("任务A")
    create("任务B")
    assert(store.closeSync("1").isRight)
    // done → open / in_progress / blocked 全拒（终态）
    val r = update("1", status = Some("open"))
    assert(r.isLeft && r.swap.toOption.get.message.contains("TBOARD_STATUS"), r)
    assert(update("1", status = Some("in_progress")).isLeft)
    assert(update("1", status = Some("blocked")).isLeft)
    // →done 单通道：update 结构化拒绝并指向 close
    val r3 = update("2", status = Some("done"))
    assert(r3.isLeft && r3.swap.toOption.get.message.contains("TBOARD_DONE_VIA_CLOSE"), r3)
    assertEquals(readDisk.get.tasks.find(_.id == "2").get.status, "open", "被拒更新未写盘")

  test("验收⑥：blocks 未闭环时 in_progress/close 拒绝（TBOARD_BLOCKED 附未闭环清单+逃生提示）；→blocked 不设闸；闭环后放行"):
    resetFile()
    create("上游任务")
    create("下游任务", blocks = List("1"))
    val r = update("2", status = Some("in_progress"))
    assert(r.isLeft, "依赖未闭环 → in_progress 必须拒绝")
    val msg = r.swap.toOption.get.message
    assert(msg.contains("TBOARD_BLOCKED"), msg)
    assert(msg.contains("#1[open]"), s"错误应列出未闭环依赖: $msg")
    assert(msg.contains("blocked"), s"错误应给「或置 blocked 记录等待」逃生提示: $msg")
    val rc = store.closeSync("2")
    assert(rc.isLeft && rc.swap.toOption.get.message.contains("TBOARD_BLOCKED"), rc)
    assert(update("2", status = Some("blocked")).isRight, "→blocked 逃生通道不设闸")
    assert(store.closeSync("1").isRight)
    assert(update("2", status = Some("in_progress")).isRight, "依赖闭环后可开工")
    assert(store.closeSync("2").isRight, "依赖闭环后可 close")

  test("验收⑥：环检测——自依赖与两节点环均拒绝（TBOARD_CYCLE），失败不写盘；纯函数三节点环"):
    resetFile()
    create("任务A")
    create("任务B")
    val selfRef = update("1", blocks = Some(List("1")))
    assert(selfRef.isLeft && selfRef.swap.toOption.get.message.contains("TBOARD_CYCLE"), selfRef)
    assert(update("1", blocks = Some(List("2"))).isRight)
    val cycle = update("2", blocks = Some(List("1")))
    assert(cycle.isLeft && cycle.swap.toOption.get.message.contains("TBOARD_CYCLE"), cycle)
    assertEquals(readDisk.get.tasks.find(_.id == "2").get.blocks, Nil, "被拒更新不得写盘")
    val triangle = List(
      TaskBoardStore.Entry("a", "a", blocks = List("b")),
      TaskBoardStore.Entry("b", "b", blocks = List("c")),
      TaskBoardStore.Entry("c", "c", blocks = List("a")))
    assert(TaskBoardStore.hasCycle(triangle), "三节点环")
    assert(!TaskBoardStore.hasCycle(List(
      TaskBoardStore.Entry("a", "a", blocks = List("b")),
      TaskBoardStore.Entry("b", "b"))), "无环链")

  test("验收⑥：close 幂等——重复 close = no-op 成功回显 closedAt；close note 以 [done] 前缀追加保留工作 note"):
    resetFile()
    create("任务A", note = Some("工作记录"))
    assert(store.closeSync("1", Some("已交付 commit abc")).isRight)
    val closedAt1 = readDisk.get.tasks.find(_.id == "1").get.closedAt.get
    val r = store.closeSync("1")
    assert(r.isRight && r.toOption.get.contains("no-op"), r)
    assert(r.toOption.get.contains(closedAt1), "回显 closedAt")
    val e = readDisk.get.tasks.find(_.id == "1").get
    assertEquals(e.closedAt, Some(closedAt1), "幂等 close 不改 closedAt")
    assertEquals(e.note, Some("工作记录\n[done] 已交付 commit abc"), "工作 note 保留 + [done] 追加")

  test("依赖 id 存在性：未知 id 拒绝（create/update 两侧，TBOARD_BLOCK_UNKNOWN）；依赖已 done 条目合法"):
    resetFile()
    val r = create("孤儿任务", blocks = List("9"))
    assert(r.isLeft && r.swap.toOption.get.message.contains("TBOARD_BLOCK_UNKNOWN"), r)
    create("已完任务")
    assert(store.closeSync("1").isRight)
    val r2 = create("后继任务", blocks = List("1"))
    assert(r2.isRight, "依赖已 done 条目合法")
    assert(update("2", status = Some("in_progress")).isRight, "done 依赖闸恒过")
    val r3 = update("2", blocks = Some(List("42")))
    assert(r3.isLeft && r3.swap.toOption.get.message.contains("TBOARD_BLOCK_UNKNOWN"), r3)

  test("未知 id：update/close 结构化拒绝（TBOARD_NOT_FOUND 列出现有条目）；FORBIDDEN 码已定义待批 2 消费"):
    resetFile()
    create("任务A")
    val r = update("42", title = Some("x"))
    assert(r.isLeft && r.swap.toOption.get.message.contains("TBOARD_NOT_FOUND"), r)
    assert(r.swap.toOption.get.message.contains("#1[open]"), "NOT_FOUND 错误应列出现有条目线索")
    val rc = store.closeSync("42")
    assert(rc.isLeft && rc.swap.toOption.get.message.contains("TBOARD_NOT_FOUND"), rc)
    assertEquals(TaskBoardStore.Codes.Forbidden, "TBOARD_FORBIDDEN")
    assertEquals(TaskBoardStore.Codes.DoneViaClose, "TBOARD_DONE_VIA_CLOSE")

  test("空 title create 拒绝（TBOARD_PARAM）"):
    resetFile()
    val r = create("  ")
    assert(r.isLeft && r.swap.toOption.get.message.contains("TBOARD_PARAM"), r)

  // ===== ④ 并发写零丢失（验收③）=====

  test("验收③：同实例两 fiber 各 50 次 create 交错 + 并发 close——2N+初始计数、零丢失、文件合法 JSON 与内存读回一致"):
    resetFile()
    val s = store
    assert(create("初始任务").isRight) // #1
    // 两 fiber 各 50 次 create 交错（同实例、实例锁串行化覆盖磁盘写完成）
    val creates = IO.blocking { (1 to 50).toList.map(i => s.createSync(s"A任务$i")).forall(_.isRight) }
      .both(IO.blocking { (1 to 50).toList.map(i => s.createSync(s"B任务$i")).forall(_.isRight) })
    val (aOk, bOk) = creates.unsafeRunSync()
    assert(aOk && bOk, "全部 create 成功")
    // 并发 update/close：两 fiber 并发 close 不同任务各 10 个（update 族并发以 close 落点验证）
    val closes = IO.blocking { (2 to 11).toList.map(i => s.closeSync(i.toString)).forall(_.isRight) }
      .both(IO.blocking { (12 to 21).toList.map(i => s.closeSync(i.toString)).forall(_.isRight) })
    val (cOk, dOk) = closes.unsafeRunSync()
    assert(cOk && dOk, "全部 close 成功")

    // 断言：最终 tasks = 2N + 初始 = 101，零丢失；文件为合法 JSON（readDisk 即解码）且与内存读回一致
    val disk = readDisk.get
    assertEquals(disk.tasks.size, 101, "2×50 create + 初始 1，零丢失")
    val listed = s.listSync().toOption.get
    assert(listed.contains("101 entries"), listed.linesIterator.next())
    assertEquals(listed.linesIterator.drop(1).size, 101, "list 行数与 store 内存视图一致")
    // 并发 close 全部落盘
    assertEquals(disk.tasks.count(_.status == "done"), 20, "20 个并发 close 全落盘")
    assertEquals(disk.tasks.filter(_.status == "done").map(_.id).map(_.toInt).sorted, (2 to 21).toList)
    // 两族标题各 50 条全在
    assert((1 to 50).forall(i => disk.tasks.exists(_.title == s"A任务$i")))
    assert((1 to 50).forall(i => disk.tasks.exists(_.title == s"B任务$i")))

  // ===== ⑤ 损坏容错 =====

  test("读损坏：list 空视图不崩、零写副作用"):
    resetFile()
    os.makeDir.all(home / ".nebflow")
    os.write(file, "{ this is not json !!!")
    val listed = store.listSync()
    assert(listed.isRight, "读路径不崩")
    assert(listed.toOption.get.contains("empty"), listed.toOption.get)
    assertEquals(os.list(home / ".nebflow").count(_.last.startsWith("task-board.json.corrupt")), 0,
      "读路径零写副作用")

  test("写路径遇损坏：隔离 quarantaine（旧字节保留）+ 空库续写、id 从 1 重起算；损坏视图下更新报 NOT_FOUND 不误写"):
    resetFile()
    os.makeDir.all(home / ".nebflow")
    os.write(file, "{{{ broken")
    // 写路径第一个动作（即使后续失败）按同构语义先隔离——与 TaskListStore 一致
    val r = update("1", status = Some("in_progress"))
    assert(r.isLeft && r.swap.toOption.get.message.contains("TBOARD_NOT_FOUND"),
      s"损坏视图为空库 → 未找到，绝不误写: $r")
    val quarantined = os.list(home / ".nebflow").filter(_.last.startsWith("task-board.json.corrupt"))
    assertEquals(quarantined.size, 1, "恰好一个隔离文件")
    assertEquals(os.read(quarantined.head), "{{{ broken", "旧字节完整保留（可人工恢复）")
    // 隔离后续写：全新空库（隔离由上面首个写路径操作完成，本次 create 不再重复隔离）
    val rc = create("损坏后续写")
    assert(rc.isRight, rc)
    val disk = readDisk.get
    assertEquals(disk.tasks.map(_.id), List("1"), "空库续写 id 从 1 重起算")
    assertEquals(disk.tasks.head.title, "损坏后续写")

  // ===== ⑥ 30 天惰性 prune =====

  test("30 天惰性 prune：create 顺带清理 closedAt 超期 done 条目；解析失败保守保留"):
    resetFile()
    create("老任务")
    create("新任务")
    assert(store.closeSync("1").isRight)
    val old = Instant.now().minusSeconds(40L * 24 * 3600).toString
    val disk = readDisk.get
    os.write.over(file, disk.copy(
      tasks = disk.tasks.map(t => if t.id == "1" then t.copy(closedAt = Some(old)) else t)).asJson.noSpaces)
    assert(create("触发清理").isRight)
    val disk2 = readDisk.get
    assert(!disk2.tasks.exists(_.id == "1"), "超期 done 条目被惰性清理")
    // closedAt 解析失败 → 保守保留
    os.write.over(file, disk2.copy(
      tasks = disk2.tasks :+ TaskBoardStore.Entry("99", "坏时间戳", status = "done", closedAt = Some("not-a-time"))
    ).asJson.noSpaces)
    assert(create("再触发").isRight)
    assert(readDisk.get.tasks.exists(_.id == "99"), "解析失败的 done 条目保守保留")

  // ===== ⑦ list 过滤与渲染标记 =====

  test("list：status/assignee 精确过滤 + 紧凑行标记（@assignee/(→node)/⚠deps-open/⚠node-done 经 nodeTerminal 纯映射）"):
    resetFile()
    create("甲", assignee = Some("n-a"), nodeId = Some("n-a"))
    create("乙", assignee = Some("dispatcher"))
    create("丙", assignee = Some("n-a"), blocks = List("1"))
    assert(store.closeSync("2").isRight)

    val open = store.listSync(status = Some("open")).toOption.get
    assert(open.contains("#1[open @n-a] 甲 (→node n-a)"), open)
    assert(open.contains("#3[open @n-a] 丙 ⚠deps-open deps: #1[open]"), open)
    assert(!open.contains("乙"), s"open 过滤排除 done 条目: $open")

    val done = store.listSync(status = Some("done")).toOption.get
    assert(done.contains("#2[done @dispatcher] 乙"), done)

    val byA = store.listSync(assignee = Some("n-a")).toOption.get
    assert(byA.contains("#1") && byA.contains("#3"), byA)
    assert(!byA.contains("#2"), s"assignee 精确过滤: $byA")

    // ⚠node-done：传入 nodeId→终态映射（批 2 由 Flow Map 接线）→ 只读漂移标记
    val drift = store.listSync(nodeTerminal = Map("n-a" -> "completed")).toOption.get
    assert(drift.contains("(→node n-a) ⚠node-done"), drift)
    // 默认空映射 → 无该标记
    assert(!store.listSync().toOption.get.contains("⚠node-done"))

end TaskBoardStoreSpec
