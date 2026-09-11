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

  /** 每用例从空库开始（无内存态——删文件即全新 store；隔离文件**与变更史**一并清
    *（升级批：史是 per-workspace 独立文件，不隔离会跨用例串味））。 */
  private def resetFile(): Unit =
    if os.exists(file) then os.remove(file)
    if os.exists(home / ".nebflow") then
      os.list(home / ".nebflow")
        .filter(p => p.last.startsWith("task-board.json.corrupt") || p.last.startsWith("task-history"))
        .foreach(os.remove)

  /** 变更史句柄（与 store 同 workspace ⇒ 同隔离）。 */
  private def hist: TaskBoardHistory = TaskBoardHistory.open(home.toString)

  private def historyLines(): List[String] =
    if os.exists(hist.file) then os.read.lines(hist.file).filter(_.trim.nonEmpty).toList else Nil

  private def historyEvents(): List[TaskBoardEvent] =
    historyLines().flatMap(l => io.circe.parser.decode[TaskBoardEvent](l).toOption)

  private def sha256(p: os.Path): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  private def store: TaskBoardStore = TaskBoardStore.open("projA", home.toString)

  private def create(
    title: String,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocks: List[String] = Nil,
    links: List[String] = Nil
  ): Either[ToolError, String] =
    store.createSync(title, assignee, nodeId, note, if blocks.isEmpty then None else Some(blocks),
      linksRaw = if links.isEmpty then None else Some(links))

  private def update(
    id: String,
    title: Option[String] = None,
    status: Option[String] = None,
    assignee: Option[String] = None,
    nodeId: Option[String] = None,
    note: Option[String] = None,
    blocks: Option[List[String]] = None,
    links: Option[List[String]] = None
  ): Either[ToolError, String] =
    store.updateSync(id, title, status, assignee, nodeId, note, blocks, links)

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

  // ===== ⑧ 升级批 · R1 log（结构化追加，板面零改写）=====

  test("R1 log：追加记录不改 note（板面逐字未变）+ 史行 actor/at 齐全；空 text / 不存在 id 结构化拒绝"):
    resetFile()
    assert(create("任务A", note = Some("工作记录")).isRight)
    val before = readDisk.get
    val r = store.logSync("1", "第一段补充", actor = TaskBoardHistory.Actors.Dispatcher)
    assert(r.isRight, r)
    assertEquals(readDisk.get, before, "log 不改板面（note / updatedAt 逐字未变）")
    val evs = hist.readFor("1", only = TaskBoardHistory.isNoteChange).events
    assertEquals(evs.map(_.kind), List("log"))
    assertEquals(evs.head.text, Some("第一段补充"), "log 载荷 = 追加段全文")
    assertEquals(evs.head.actor, "dispatcher")
    assert(evs.head.at.endsWith("Z"), "ISO-8601 UTC")
    // 空 text → PARAM；不存在 id → NOT_FOUND（且不写史）
    val empty = store.logSync("1", "   ")
    assert(empty.isLeft && empty.swap.toOption.get.message.contains("TBOARD_PARAM"), empty)
    val nf = store.logSync("42", "x")
    assert(nf.isLeft && nf.swap.toOption.get.message.contains("TBOARD_NOT_FOUND"), nf)
    assertEquals(hist.readFor("42").total, 0, "被拒操作不写史")

  test("R1/R7 判定单点（作者 2026-09-11 17:27 口径）：同一任务 3 次 note 变更（含 update 覆盖）→ show 按时间序完整还原每一次内容，被覆盖版仍可读"):
    resetFile()
    assert(create("任务A", note = Some("第一版：方案 A")).isRight)
    assert(update("1", note = Some("第二版：方案 B（A 放弃）")).isRight, "update = note 覆盖（REPLACES）")
    assert(store.logSync("1", "补充：B 的边界条件", actor = TaskBoardHistory.Actors.Node).isRight)
    assert(update("1", note = Some("第三版：方案 C（B 回退）")).isRight)
    val out = store.showSync("1").toOption.get
    // 判定单点的实测原文随测试输出存档（可复现的验收证据）
    println("[show-acceptance]\n" + out)
    val tl = out.substring(out.indexOf("note changes"))
    val i1 = tl.indexOf("第一版：方案 A")
    val i2 = tl.indexOf("第二版：方案 B（A 放弃）")
    val i3 = tl.lastIndexOf("第三版：方案 C（B 回退）")
    assert(i1 >= 0 && i2 >= 0 && i3 >= 0, s"每一次 note 内容都要读得出:\n$tl")
    assert(i1 < i2 && i2 < i3, s"时间序（旧→新）:\n$tl")
    assert(tl.contains("before") && tl.contains("after"), "覆盖动作两侧（prev/next）都渲染")
    assert(tl.indexOf("补充：B 的边界条件") > i1 && tl.indexOf("补充：B 的边界条件") < i3, "log 落在时间序位置上")
    assert(out.contains("第三版：方案 C（B 回退）"), "当前 note 在字段区")
    // 史行侧（结构化判据）：update#1 prev=第一版 next=第二版
    val noteEvs = hist.readFor("1", only = TaskBoardHistory.isNoteChange).events.filter(_.field.contains("note"))
    assertEquals(noteEvs.size, 2, "两次 update 覆盖 = 两条 note 史行")
    assertEquals(noteEvs.head.prev, Some("第一版：方案 A"), "覆盖前版本必须留在史里（可还原）")
    assertEquals(noteEvs.head.next, Some("第二版：方案 B（A 放弃）"))
    assertEquals(noteEvs(1).prev, Some("第二版：方案 B（A 放弃）"))
    assertEquals(noteEvs(1).next, Some("第三版：方案 C（B 回退）"))

  // ===== ⑨ 升级批 · R2 links =====

  test("R2 links：create/update 往返 + 去空白去重 + 不校验可达性 + [] 清除 + 容量上限"):
    resetFile()
    assert(create("任务A", links = List("/tmp/does-not-exist.md", "abc1234", "   ", "abc1234")).isRight)
    assertEquals(readDisk.get.tasks.head.links, List("/tmp/does-not-exist.md", "abc1234"),
      "规整去重；**路径不存在不报错**（不校验可达性）")
    assertEquals(store.entriesSync().head.links, List("/tmp/does-not-exist.md", "abc1234"), "读回一致")
    assert(store.showSync("1").toOption.get.contains("abc1234"), "show 回读 link")
    assert(update("1", links = Some(List("only-one"))).isRight)
    assertEquals(readDisk.get.tasks.head.links, List("only-one"), "update links = FULL replacement")
    assert(update("1", links = Some(Nil)).isRight)
    assertEquals(readDisk.get.tasks.head.links, Nil, "[] 清空")
    val many = update("1", links = Some((1 to TaskBoardStore.LinksWriteMax + 1).map(i => s"link-$i").toList))
    assert(many.isLeft && many.swap.toOption.get.message.contains("too many `links`"), many)
    val long = update("1", links = Some(List("x" * (TaskBoardStore.LinkWriteMaxChars + 1))))
    assert(long.isLeft && long.swap.toOption.get.message.contains(s"max ${TaskBoardStore.LinkWriteMaxChars} per link"), long)
    // links 变更在史里留痕
    assert(historyEvents().exists(e => e.field.contains("links")), "links 变更留痕")

  // ===== ⑩ 升级批 · R3 show 全文 =====

  test("R3 show：全字段 + note 全文（不截 60）+ links/依赖 + 时间线；不存在 id → TBOARD_NOT_FOUND 列 open 清单"):
    resetFile()
    val longNote = "第一行正文\n" + ("细节" * 40)
    assert(create("任务A", assignee = Some("n-a"), nodeId = Some("n-a"), note = Some(longNote),
      links = List("docs/x.md")).isRight)
    val out = store.showSync("1").toOption.get
    assert(out.startsWith("#1[open @n-a] 任务A"), out)
    assert(out.contains("status: open"), out)
    assert(out.contains("created: 2") && out.contains("updated: 2"), out)
    assert(out.contains("assignee: n-a"))
    assert(out.contains("nodeId: n-a (→node n-a)"))
    assert(out.contains("links (1, not validated for reachability): docs/x.md"))
    assert(out.contains(s"note (${longNote.length} chars):"), out)
    val (noteL1, noteL2) = (longNote.split("\n")(0), longNote.split("\n")(1))
    assert(out.contains(noteL1) && out.contains(noteL2), s"note 全文（list 只截 60，show 必须完整）: $out")
    assert(out.contains("note changes"), "note 主线区标题")
    assert(out.contains("state events"), "状态类次区标题")
    val nf = store.showSync("42")
    assert(nf.isLeft && nf.swap.toOption.get.message.contains("TBOARD_NOT_FOUND"), nf)
    assert(nf.swap.toOption.get.message.contains("#1[open]"), "NOT_FOUND 列现存 open 条目")

  // ===== ⑪ 升级批 · R5 写入侧容量上限（本工具独有取值）=====

  test("R5 上限：note 16000 边界（错误文案含修法与存量 max 依据）/ title 300 / log text 4000；读路径零校验"):
    resetFile()
    assert(TaskBoardStore.NoteWriteMaxChars >= 10_993, "新上限必须 ≥ 存量 max（RK-4，不得跨工具照抄 2000）")
    val ok = create("t", note = Some("甲" * TaskBoardStore.NoteWriteMaxChars))
    assert(ok.isRight, s"恰在上限 → 放行: $ok")
    val over = update("1", note = Some("乙" * (TaskBoardStore.NoteWriteMaxChars + 1)))
    assert(over.isLeft, over)
    val msg = over.swap.toOption.get.message
    assert(msg.contains(s"the write-side limit is ${TaskBoardStore.NoteWriteMaxChars}"), msg)
    assert(msg.contains("action=log"), s"错误必须给修法（长内容走 log）: $msg")
    assert(msg.contains("max 10,993"), s"上限依据（存量读数）应可读: $msg")
    assert(msg.contains("TBOARD_PARAM"), msg)
    assertEquals(readDisk.get.tasks.head.note.map(_.length), Some(TaskBoardStore.NoteWriteMaxChars),
      "被拒写入零副作用")
    assert(create("丙" * (TaskBoardStore.TitleWriteMaxChars + 1)).isLeft, "title 超限拒绝")
    assert(create("丙" * TaskBoardStore.TitleWriteMaxChars).isRight, "title 恰在上限放行")
    assert(store.logSync("1", "丁" * (TaskBoardStore.LogTextWriteMaxChars + 1)).isLeft, "log text 超限拒绝")
    assert(store.logSync("1", "丁" * TaskBoardStore.LogTextWriteMaxChars).isRight)
    // 读路径零校验：手写超限存量 note（>16000）→ list/show 照常（存量数据不受影响）
    val disk = readDisk.get
    os.write.over(file, disk.copy(tasks = disk.tasks.map(t =>
      if t.id == "1" then t.copy(note = Some("戊" * 20_000)) else t)).asJson.noSpaces)
    assert(store.listSync().isRight, "存量超限不阻断读")
    val shown = store.showSync("1").toOption.get
    assert(shown.contains("showing first 16000"), s"超限存量 note 可见截断（明示）: ${shown.take(200)}")
    assert(shown.length <= TaskBoardRenderer.ShowHardCapChars, s"show 硬顶内: ${shown.length}")

  // ===== ⑫ 升级批 · R7 变更史（不随任务消失）+ 幂等早返零史行 =====

  test("R7 prune：被清条目写 prune 史行（actor=system）+ 史行数不减；close 幂等早返分支零史行（逐字保留）"):
    resetFile()
    assert(create("老任务", note = Some("旧 note")).isRight)
    assert(create("占位").isRight) // #2 保留 ⇒ prune 后 id 不复用
    assert(store.closeSync("1", Some("已交付")).isRight)
    val linesClosed = historyLines().size
    // 幂等 close（已 done）：早返分支逐字保留，不改板、不写史
    val noop = store.closeSync("1", Some("第二次 outcome 应被丢弃"))
    assert(noop.isRight && noop.toOption.get.contains("no-op"), noop)
    assert(!readDisk.get.tasks.find(_.id == "1").get.note.exists(_.contains("第二次 outcome")),
      "RK-5：已 done 条目的 note 实参被静默丢弃（既有契约逐字保留）")
    assertEquals(historyLines().size, linesClosed, "幂等早返分支零史行")
    // 把 #1 closedAt 改到 40 天前 → 下次 create 惰性 prune
    val old = Instant.now().minusSeconds(40L * 24 * 3600).toString
    val disk = readDisk.get
    os.write.over(file, disk.copy(tasks = disk.tasks.map(t =>
      if t.id == "1" then t.copy(closedAt = Some(old)) else t)).asJson.noSpaces)
    assert(create("触发清理").isRight)
    assert(!readDisk.get.tasks.exists(_.id == "1"), "超期 done 条目被清")
    assert(historyLines().size > linesClosed, "史行数不减（史不随任务消失）")
    val pruned = historyEvents().filter(_.kind == "prune")
    assertEquals(pruned.map(_.id), List(Some("1")))
    assertEquals(pruned.head.actor, "system")
    assert(pruned.head.detail.exists(_.contains("closedAt=")), pruned.head.detail.toString)

  test("R7/R6（已有能力，仅同步口径）：板面损坏 → 隔离改名 + 空库续写 + 1 条 quarantine 史行"):
    resetFile()
    os.makeDir.all(home / ".nebflow")
    os.write(file, "{{{ broken")
    assert(create("损坏后续写").isRight)
    val q = historyEvents().filter(_.kind == "quarantine")
    assertEquals(q.size, 1, "隔离事件留痕")
    assertEquals(q.head.actor, "system")
    assert(q.head.detail.exists(_.contains("task-board.json.corrupt")), q.head.detail.toString)

  // ===== ⑬ 升级批 · R8 依赖反查 + 归档命中降级路径 =====

  test("R8 show 依赖反查：谁依赖我（≤20 + (+N more)）与依赖当前态；>20 条扇入可见计数"):
    resetFile()
    assert(create("上游").isRight)
    assert(create("下游1", blocks = List("1")).isRight)
    assert(create("下游2", blocks = List("1")).isRight)
    val up = store.showSync("1").toOption.get
    assert(up.contains("required by (2, entries depending on this)"), up)
    assert(up.contains("#2[open @dispatcher] 下游1"), up)
    val down = store.showSync("2").toOption.get
    assert(down.contains("deps (1, this entry depends on): #1[open] 上游 ⚠"), down)
    (1 to 25).foreach(i => assert(create(s"扇入$i", blocks = List("1")).isRight))
    val big = store.showSync("1").toOption.get
    assert(big.contains("required by (27") && big.contains("(+7 more)"), big)

  test("R8 降级（作者钉死）：prune 后按 id show 得史时间线 + [gone] 标记、主库字段如实「不可得」；库史皆无的随机 id 仍报错"):
    resetFile()
    assert(create("将被清理", note = Some("第一版：做法 A")).isRight)
    assert(create("占位").isRight)
    assert(update("1", note = Some("第二版：做法 B")).isRight)
    assert(store.closeSync("1", Some("收尾")).isRight)
    val old = Instant.now().minusSeconds(40L * 24 * 3600).toString
    val disk = readDisk.get
    os.write.over(file, disk.copy(tasks = disk.tasks.map(t =>
      if t.id == "1" then t.copy(closedAt = Some(old)) else t)).asJson.noSpaces)
    assert(create("触发清理").isRight)
    assert(!readDisk.get.tasks.exists(_.id == "1"), "条目已被 prune")
    val out = store.showSync("1")
    assert(out.isRight, s"主库无 / 史有 ⇒ 不得报错退出（作者 2026-09-11 17:27 口径）: $out")
    val s = out.toOption.get
    assert(s.contains("#1[gone]"), s)
    assert(s.contains("cleaned up — NOT available"), s"主库字段如实标不可得（禁编造回填）")
    assert(s.contains("第一版：做法 A") && s.contains("第二版：做法 B"),
      s"pruned 后仍可还原 note 版本时间线:\n$s")
    assert(s.contains("[done] 收尾"), s"close 的 [done] 追加可还原: $s")
    val none = store.showSync("999")
    assert(none.isLeft && none.swap.toOption.get.message.contains("TBOARD_NOT_FOUND"),
      s"库与史皆无 ⇒ 原报错路径: $none")

  // ===== ⑭ 升级批 · 零迁移 + 事件构成占比读数 =====

  test("零迁移：旧 10 键 schema 原样解码（links 回默认 Nil）+ 读/show 零写盘、不建史文件"):
    resetFile()
    os.makeDir.all(home / ".nebflow")
    val legacy =
      """{"version":1,"tasks":[{"id":"7","title":"旧条目","status":"open","assignee":"dispatcher","nodeId":null,""" +
        """"note":"旧 note 正文","blocks":[],"createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z","closedAt":null}]}"""
    os.write(file, legacy)
    val before = sha256(file)
    val e = store.entriesSync().head
    assertEquals(e.id, "7")
    assertEquals(e.links, Nil, "新字段回默认")
    assert(store.listSync().toOption.get.contains("旧条目"))
    assert(store.showSync("7").toOption.get.contains("旧 note 正文"))
    assertEquals(sha256(file), before, "读路径零写盘（读动作绝不重写旧文件）")
    assert(!os.exists(hist.file), "读动作不建史文件（读不写盘）")

  test("事件构成占比读数：note/log 主线 vs 状态类（状态类不得主导；读数随测试输出存档）"):
    resetFile()
    // 贴近真实编排的剧本：8 个任务 × (create[状态] + status 迁移[状态] + 2 次 note 覆盖[note] + 1 次 log[note] + close+outcome[note])
    for i <- 1 to 8 do
      assert(create(s"任务$i", note = Some(s"任务$i 第一版：" + "背景" * 30)).isRight)
      assert(update(i.toString, status = Some("in_progress")).isRight)
      assert(update(i.toString, note = Some(s"任务$i 第二版：" + "做法" * 40)).isRight)
      assert(store.logSync(i.toString, s"任务$i 补充：" + "记录" * 60, actor = TaskBoardHistory.Actors.Node).isRight)
      assert(update(i.toString, note = Some(s"任务$i 第三版：" + "收束" * 20)).isRight)
      assert(store.closeSync(i.toString, Some(s"任务$i 结果：" + "交付" * 20)).isRight)
    val evs = historyEvents()
    val notes = evs.filter(TaskBoardHistory.isNoteChange)
    val states = evs.filterNot(TaskBoardHistory.isNoteChange)
    def bytes(xs: List[TaskBoardEvent]): Int = xs.map(_.asJson.noSpaces.getBytes("UTF-8").length + 1).sum
    val nb = bytes(notes); val sb = bytes(states)
    println(f"[composition] events: note/log=${notes.size} state=${states.size} total=${evs.size} " +
      f"(${100.0 * notes.size / evs.size}%.1f%% lines); bytes: note/log=$nb state=$sb total=${nb + sb} " +
      f"(${100.0 * nb / (nb + sb)}%.1f%% bytes); file=${os.size(hist.file)}B lines=${historyLines().size}")
    assert(states.nonEmpty && notes.nonEmpty)
    assert(nb > sb, s"状态类不得主导（字节 note=$nb state=$sb）⇒ 否则须给精简方案")
    assert(sb * 3 < nb, s"状态类占比应远低于主线（state=$sb vs note=$nb）")

end TaskBoardStoreSpec
