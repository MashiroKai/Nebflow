package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.AgentDef
import nebflow.core.PathUtil

import java.nio.file.Files

/**
 * TaskList 变更史独立落盘 spec（升级批 D1/D2/D6，R7 主用例）。
 *
 * 覆盖：
 *  - **独立落盘**：每次写追加一行 `tasks-history.jsonl`；`tasks.json` 不承载史；
 *    读动作（list/show）零写盘；
 *  - **actor 引擎侧派生**（不信客户端）：Nebula → `nebula`，dispatcher / 节点 /
 *    无身份各自的取值；客户端传 `actor`/`history` 一律忽略；
 *  - **单条上限**：`text` 2,001 字符拒（`TASKLIST_PARAM` + 修法），2,000 通过；
 *  - **坏行容错**：半行跳过并计数（`show` 不崩、日志 WARN）；
 *  - **轮转（D6）**：活动文件越 5 MiB → 下一次写惰性归档为 `.1`（**覆盖上一代**）
 *    + 新活动文件首行 `rotate` 事件；磁盘硬顶 ≈ 10 MiB（只两代）；
 *  - **不随 prune 消失**：prune 只动 `store.tasks`，史零接触（+1 条 `prune` 事件）；
 *    任务被清掉后 `show <id>` 仍可读其时间线（`[gone]` 降级，主库字段如实不可得）；
 *  - **id 复用面**：id 水位（`TaskListData.nextId`）单调 ⇒ prune 后新任务不复用旧 id
 *    （否则两代任务的史会混成一条时间线）；旧库缺 `nextId` 键零迁移读入；
 *  - **手动清理无感**：`rm` 史文件 → 下次写自建；`tasks.json` 零影响；
 *  - **路径与隔离**：`TaskListHistory.activePath` = `dataRoot/tasks-history.jsonl`
 *    （换根即换文件）。
 *
 * 隔离：`PathUtil.setDataRoot(temp)`（TaskListToolSpec 同款配方）；真实
 * `~/.nebflow/` 数据面零触碰。
 */
class TaskListHistorySpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-tasklist-history"))
  var prevRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def tasksFile: os.Path = home / "tasks.json"
  private def histFile: os.Path = home / "tasks-history.jsonl"
  private def archFile: os.Path = home / "tasks-history.1.jsonl"

  private def reset(): Unit =
    List(tasksFile, histFile, archFile).foreach(p => if os.exists(p) then os.remove(p))
    os.list(home).filter(_.last.startsWith("tasks.json.corrupt")).foreach(os.remove(_))

  private def nebulaCtx: ToolContext =
    ToolContext(projectRoot = "/tmp", agentDef = Some(AgentDef(name = "Nebula", description = "")))

  private def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  private def call(fields: (String, Json)*): Either[ToolError, String] =
    TaskListTool.call(obj(fields*), nebulaCtx).unsafeRunSync()

  private def create(title: String): Either[ToolError, String] =
    call("action" -> Json.fromString("create"), "title" -> Json.fromString(title))

  private def log(id: String, text: String): Either[ToolError, String] =
    call("action" -> Json.fromString("log"), "id" -> Json.fromString(id), "text" -> Json.fromString(text))

  private def show(id: String): Either[ToolError, String] =
    call("action" -> Json.fromString("show"), "id" -> Json.fromString(id))

  private def tasks: List[TaskListEntry] = decode[TaskListData](os.read(tasksFile)).toOption.get.tasks

  /** 史文件逐行解码（半行等不可解析行按 None 保留位置）。 */
  private def historyLines: List[Option[TaskListEvent]] =
    if !os.exists(histFile) then Nil
    else os.read(histFile).split("\n", -1).toList.filter(_.trim.nonEmpty).map(l => decode[TaskListEvent](l).toOption)

  private def events: List[TaskListEvent] = historyLines.flatten

  private def sha(p: os.Path): String =
    os.proc("shasum", "-a", "256", p.toString).call().out.text().trim.split(" ").head

  // ===== ① 独立落盘 + 读动作零写 =====

  test("独立落盘：create/update/close/log 各追加一行；tasks.json 不含史文本；list/show 零写盘"):
    reset()
    assert(create("任务A").isRight)
    assert(call("action" -> Json.fromString("update"), "id" -> Json.fromString("1"),
      "status" -> Json.fromString("in_progress"), "note" -> Json.fromString("当前态摘要")).isRight)
    assert(call("action" -> Json.fromString("close"), "id" -> Json.fromString("1"),
      "note" -> Json.fromString("收口")).isRight)
    assert(log("1", "补记一条").isRight)

    assert(os.exists(histFile), "史文件必须落盘")
    val evs = events
    // create（无 note）+ update（status + note 各一条）+ close（close + [done] outcome 的 note 内容行）+ log
    assertEquals(evs.map(_.kind), List("create", "update", "update", "close", "update", "log"), "逐字段一行")
    assertEquals(evs.count(TaskListHistory.isNoteEvent), 3, "note/log 内容行 = update note + log + close 的 outcome")
    assert(evs.forall(_.at.endsWith("Z")), s"at 必须 ISO-8601 UTC: ${evs.map(_.at)}")
    assert(evs.forall(_.actor == "nebula"), s"actor 引擎侧派生: ${evs.map(_.actor)}")
    // note 覆盖的 from/to 是**两侧全文**（可还原：上一条的 to == 下一条的 from）
    val noteEvs = evs.filter(TaskListHistory.isNoteEvent).filter(_.kind == "update")
    assertEquals(noteEvs.size, 2, "update note + close 的 outcome 追加")
    assertEquals(noteEvs.head.to, Some(Json.fromString("当前态摘要")), "首版全文入档")
    assertEquals(noteEvs(1).from, Some(Json.fromString("当前态摘要")), "被覆盖前那一版仍可读出")
    assertEquals(noteEvs(1).to, Some(Json.fromString("当前态摘要\n[done] 收口")), "覆盖后全文入档")

    // 主库不承载史：log 正文不出现在 tasks.json
    val raw = os.read(tasksFile)
    assert(!raw.contains("补记一条"), "任务体不承载变更史")
    assert(!raw.contains("tasks-history"), "tasks.json 不引用史文件")

    // 读动作零写盘：史文件指纹不变
    val before = sha(histFile)
    assert(TaskListTool.call(obj("action" -> Json.fromString("list")), nebulaCtx).unsafeRunSync().isRight)
    assert(show("1").isRight)
    assertEquals(sha(histFile), before, "list/show 不得写史")

    // 史文件缺席时读动作也不得创建它
    reset()
    assert(create("新库").isRight)
    os.remove(histFile)
    assert(show("1").isRight)
    assert(TaskListTool.call(obj("action" -> Json.fromString("list")), nebulaCtx).unsafeRunSync().isRight)
    assert(!os.exists(histFile), "读路径不得触发轮转/建文件（读不写盘）")

  test("路径与隔离：activePath = dataRoot/tasks-history.jsonl；换根即换文件"):
    assertEquals(TaskListHistory.activePath, home / "tasks-history.jsonl")
    assertEquals(TaskListHistory.archivePath, home / "tasks-history.1.jsonl")
    assertEquals(TaskListHistory.ActiveFileName, "tasks-history.jsonl")
    assertEquals(TaskListHistory.DiskHardCapBytes, 10L * 1024 * 1024)

  // ===== ② actor 派生（D5）=====

  test("actor 引擎侧派生：Nebula → nebula；dispatcher / 节点 / 无身份各自取值"):
    val nebula = ToolContext(projectRoot = "/tmp", agentDef = Some(AgentDef(name = "Nebula", description = "")))
    val lower  = ToolContext(projectRoot = "/tmp", agentDef = Some(AgentDef(name = "General", description = "")))
    val disp   = ToolContext(projectRoot = "/tmp", isDispatcher = true)
    val node   = ToolContext(projectRoot = "/tmp", flowNodeId = Some("n-abc"))
    val none   = ToolContext(projectRoot = "/tmp")
    assertEquals(TaskListHistory.actorOf(nebula), "nebula")
    assertEquals(TaskListHistory.actorOf(lower), "general")
    assertEquals(TaskListHistory.actorOf(disp), "dispatcher")
    assertEquals(TaskListHistory.actorOf(node), "node")
    assertEquals(TaskListHistory.actorOf(none), "unknown")
    // 分发器标记优先于节点 id（形态同 TaskBoardTool.BoardCaller.fromContext）
    assertEquals(TaskListHistory.actorOf(disp.copy(flowNodeId = Some("n-abc"))), "dispatcher")

  test("客户端传 actor/history 字段一律忽略（引擎侧派生不可被改写）"):
    reset()
    assert(call(
      "action" -> Json.fromString("create"),
      "title" -> Json.fromString("伪造身份"),
      "actor" -> Json.fromString("author"),
      "history" -> Json.arr(Json.fromString("fake-event"))).isRight)
    assertEquals(events.map(_.actor), List("nebula"), "客户端 actor 被忽略")
    assert(!os.read(histFile).contains("fake-event"), "客户端 history 不得进史文件")
    // 工具层完全不带这两个参数时行为逐字一致（未知键容忍；at 为时钟值故比对时归一）
    val withFake = events.map(_.copy(at = ""))
    reset()
    assert(create("伪造身份").isRight)
    assertEquals(events.map(_.copy(at = "")), withFake, "携带伪造字段与不携带的结果逐字一致")

  // ===== ③ 单条上限（D3/R5）=====

  test("单条上限：text 2,001 字符拒（TASKLIST_PARAM + 修法），2,000 通过；note 同口径"):
    reset()
    create("任务A")
    val over = "汉" * 2001
    val r = log("1", over)
    assert(r.isLeft, "超 2,000 字符必须拒绝")
    val msg = r.swap.toOption.get.message
    assert(msg.contains("TASKLIST_PARAM"), msg)
    assert(msg.contains("action=log"), s"错误必须给修法: $msg")
    // 被拒不落史（无噪声行）
    assertEquals(events.count(_.kind == "log"), 0, "被拒操作不写史")

    assert(log("1", "汉" * 2000).isRight, "2,000 字符通过")
    assertEquals(events.count(_.kind == "log"), 1)
    val longNote = call("action" -> Json.fromString("update"), "id" -> Json.fromString("1"),
      "note" -> Json.fromString(over))
    assert(longNote.isLeft && longNote.swap.toOption.get.message.contains("TASKLIST_PARAM"), longNote)
    // close 的 outcome note 同口径
    val longClose = call("action" -> Json.fromString("close"), "id" -> Json.fromString("1"),
      "note" -> Json.fromString(over))
    assert(longClose.isLeft && longClose.swap.toOption.get.message.contains("TASKLIST_PARAM"), longClose)

  // ===== ④ 损坏半行容错 =====

  test("坏行容错：半行跳过并计数，show 不崩（既有行照常读出）"):
    reset()
    create("任务A")
    log("1", "有效补记")
    os.write.append(histFile, "{\"at\":\"2026-09-11T00:00:00") // crash 半行（无收尾括号）
    os.write.append(histFile, "\nnot json at all\n")
    os.write.append(histFile, historyLines(1).get.asJson.noSpaces + "\n") // 再补一条有效行（等价 log）

    val r = show("1")
    assert(r.isRight, "坏行不得让 show 崩")
    val out = r.toOption.get
    assert(out.contains("有效补记"), out)
    assert(out.contains("history: 2 unreadable line(s) skipped"), s"坏行必须可见计数: $out")
    assertEquals(events.map(_.kind), List("create", "log", "log"), "可解析行仍可读（半行不影响后续）")

  // ===== ⑤ 轮转（D6）=====

  test("轮转：活动文件越 5 MiB → 下一次写归档为 .1（覆盖上一代）+ 首行 rotate 事件 + 硬顶 ≤10 MiB"):
    reset()
    // 造一个 >5 MiB 的活动文件（每行 ~1 KB 的合法事件行）
    val filler = TaskListEvent(at = "2026-09-11T00:00:00Z", actor = "system", kind = "log",
      id = Some("999"), text = Some("x" * 900)).asJson.noSpaces
    val n = 6000
    os.write.over(histFile, (List.fill(n)(filler).mkString("\n")) + "\n", createFolders = true)
    val beforeBytes = os.size(histFile)
    assert(beforeBytes > TaskListHistory.MaxActiveBytes, s"前置条件：$beforeBytes > 5 MiB")

    assert(create("触发轮转").isRight)
    assert(os.exists(archFile), "越阈 → 归档为 .1")
    assertEquals(os.size(archFile), beforeBytes, "归档 = 轮转前的活动文件原样")
    val first = decode[TaskListEvent](os.read(histFile).linesIterator.next()).toOption
    assertEquals(first.map(_.kind), Some("rotate"), "新活动文件首行 = rotate 事件")
    assert(first.get.detail.exists(d => d.contains(s"lines=$n") && d.contains(s"bytes=$beforeBytes")), first)
    assertEquals(first.map(_.actor), Some("system"))
    assert(events.exists(_.kind == "create"), "轮转后本次写仍落新活动文件")
    // 硬顶：两代合计 ≤ 10 MiB
    val total = os.size(histFile) + os.size(archFile)
    assert(total <= TaskListHistory.DiskHardCapBytes, s"$total ≤ 10 MiB")

    // 覆盖上一代：再越一次阈 → .1 被新归档替换（不存在第三代）
    os.write.append(histFile, (List.fill(n)(filler).mkString("\n")) + "\n")
    val expectedArchive = os.size(histFile)
    assert(create("二次轮转").isRight)
    assertEquals(os.size(archFile), expectedArchive, ".1 覆盖为「上一代」")
    assertEquals(os.list(home).count(_.last.startsWith("tasks-history")), 2, "只保留活动 + .1 两代")

  // ===== ⑥ 不随 prune 消失（作者裁定：可达）=====

  test("prune 后史仍在：任务条目被清 → 史行数不减（+1 条 prune 事件）→ show 给 [gone] 降级时间线"):
    reset()
    create("将被清理")                                  // #1
    assert(call("action" -> Json.fromString("close"), "id" -> Json.fromString("1"),
      "note" -> Json.fromString("收口备注")).isRight)
    create("保留项")                                    // #2（留着，避免 prune 后 id 复用）
    val linesBefore = historyLines.size
    val matchedBefore = TaskListHistory.readFor("1").matched
    assert(matchedBefore >= 2, s"create+close 应有 ≥2 条: $matchedBefore")

    // 手写 40 天前的 closedAt（真实数据面零触碰，只动 temp 副本）
    val store = decode[TaskListData](os.read(tasksFile)).toOption.get
    val old = java.time.Instant.now().minusSeconds(40L * 24 * 3600).toString
    val patched = store.copy(tasks = store.tasks.map(t => if t.id == "1" then t.copy(closedAt = Some(old)) else t))
    os.write.over(tasksFile, patched.asJson.noSpaces)

    assert(create("触发 prune").isRight)
    assert(!tasks.exists(_.title == "将被清理"),
      s"超期 done 条目被 prune；实际：${tasks.map(t => (t.id, t.title, t.status, t.closedAt))}")
    assert(tasks.exists(_.id == "2"), "未超期条目保留")
    assert(historyLines.size > linesBefore, "史行数不减（+1 条 prune 事件）")
    assert(events.exists(e => e.kind == "prune" && e.id.contains("1")), "prune 留痕（actor=system）")
    assertEquals(TaskListHistory.readFor("1").matched, matchedBefore + 1, "prune 事件归入该 id 时间线")

    // 任务没了、史还在 → show 降级可达（禁报「不存在」退出）
    val r = show("1")
    assert(r.isRight, "历史可达：不得报 NO_ID 退出")
    val out = r.toOption.get
    assert(out.contains("#1 [gone]"), s"[gone] 标记必须存在: $out")
    assert(out.contains("cleared, unavailable"), s"主库字段如实不可得: $out")
    assert(out.contains("收口备注"), s"史时间线可达: $out")
    assert(out.contains("prune"), out)
    // 未命中史的未知 id 仍走既有 NO_ID 通道
    val miss = show("424242")
    assert(miss.isLeft && miss.swap.toOption.get.message.contains("TASKLIST_NO_ID"), miss)

  // ===== ⑦ id 复用面（分发器 17:58 口径：同构判定 + 最小修法 + 回归）=====

  test("id 复用回归：prune 后 id 不回收 → 新条目零混入、旧 id [gone]、随机 id 仍 NO_ID"):
    reset()
    create("第一代")                                     // #1
    assert(call("action" -> Json.fromString("close"), "id" -> Json.fromString("1")).isRight)
    log("1", "第一代的补记（不得出现在第二代）")
    // 40 天前 closedAt → 下一次 create 触发 prune
    val store = decode[TaskListData](os.read(tasksFile)).toOption.get
    val old = java.time.Instant.now().minusSeconds(40L * 24 * 3600).toString
    os.write.over(tasksFile,
      store.copy(tasks = store.tasks.map(t => if t.id == "1" then t.copy(closedAt = Some(old)) else t)).asJson.noSpaces)

    assert(create("第二代").isRight)
    assert(!tasks.exists(_.id == "1"), "第一代已被 prune")
    val secondId = tasks.find(_.title == "第二代").get.id
    assertEquals(secondId, "2", "id 水位单调：不复用被 prune 的 #1（b3 读数）")
    assertEquals(decode[TaskListData](os.read(tasksFile)).toOption.get.nextId, 2, "水位已回写（id 不回收）")

    // 新条目时间线零混入
    val second = show(secondId).toOption.get
    assert(second.contains("create: 第二代"), second)
    assert(!second.contains("第一代的补记"), s"新条目不得混入旧代事件: $second")
    // 旧 id 走 [gone] 降级（其史仍可查）
    val gone = show("1").toOption.get
    assert(gone.contains("#1 [gone]"), gone)
    assert(gone.contains("第一代的补记"), gone)
    // 库与史均无的随机 id 仍报不存在
    val miss = show("987654")
    assert(miss.isLeft && miss.swap.toOption.get.message.contains("TASKLIST_NO_ID"), miss)

  test("id 水位零迁移：旧库无 nextId 键 → 解码回 0；create 从 max+1 续接并回写水位"):
    reset()
    os.write(tasksFile, // 旧 9 键形态（顶层无 nextId）
      """{"version":1,"tasks":[
        |{"id":"7","title":"旧条目","status":"open","blocks":[],"createdAt":"2026-09-07T00:00:00.000Z","updatedAt":"2026-09-07T00:00:00.000Z","closedAt":null}
        |]}""".stripMargin, createFolders = true)
    val legacy = decode[TaskListData](os.read(tasksFile)).toOption.get
    assertEquals(legacy.nextId, 0, "缺键回默认（零迁移读入）")
    assertEquals(TaskListStore.nextNumId(legacy), 8, "续接 = max(存量 max, 水位) + 1")
    assert(create("续接任务").isRight)
    val after = decode[TaskListData](os.read(tasksFile)).toOption.get
    assertEquals(after.tasks.find(_.title == "续接任务").get.id, "8")
    assertEquals(after.nextId, 8, "水位回写")

  test("残余（如实申报，本批不修）：quarantine 重建空库 → 水位归 0 ⇒ 其后新建可复用损坏前 id"):
    reset()
    assert(create("损坏前").isRight)                       // #1
    assertEquals(decode[TaskListData](os.read(tasksFile)).toOption.get.nextId, 1)
    os.write.over(tasksFile, "{ broken json !!!")          // 模拟损坏
    assert(create("重建后").isRight)
    assertEquals(tasks.map(_.id), List("1"), "空库重建 ⇒ 水位归 0 ⇒ id 从 1 重新起算（已知残余窗口）")
    assertEquals(decode[TaskListData](os.read(tasksFile)).toOption.get.nextId, 1)
    assert(events.exists(_.kind == "quarantine"), "隔离本身有史留痕（可事后定位复用窗口）")

  // ===== ⑧ 手动清理无感 =====

  test("手动清理无感：rm 史文件 → 下次写自建；tasks.json 零影响"):
    reset()
    create("任务A")
    log("1", "会随手动清理消失的一条")
    assert(os.exists(histFile))
    os.remove(histFile)
    if os.exists(archFile) then os.remove(archFile)

    assert(log("1", "清理后补记").isRight)
    assert(os.exists(histFile), "工具自建史文件（无感）")
    assertEquals(events.map(_.kind), List("log"), "重建后只含清理之后的写入")
    val listed = TaskListTool.call(obj("action" -> Json.fromString("list")), nebulaCtx).unsafeRunSync()
    assert(listed.toOption.get.contains("#1 [open] 任务A"), "主数据零影响")

  // ===== ⑨ 事件类型构成占比读数（实测，供交付读数）=====

  test("构成占比读数（实测）：note/log 内容行 vs 状态类 —— 行数 / 字节 / 占比 / 均长"):
    reset()
    // 现实负载模型：20 个任务的 create→in_progress→close 循环 + 其中 5 条各 1 次 note 覆盖
    // + 3 条 log 补记（贴现状用法：状态迁移为主、note 补充为辅）
    (1 to 20).foreach { i =>
      val id = i.toString
      assert(create(s"批量任务$i").isRight)
      assert(call("action" -> Json.fromString("update"), "id" -> Json.fromString(id),
        "status" -> Json.fromString("in_progress")).isRight)
      if i <= 5 then
        assert(call("action" -> Json.fromString("update"), "id" -> Json.fromString(id),
          "note" -> Json.fromString(s"做法 $i：从方案 A 改为方案 B")).isRight)
      if i <= 3 then assert(log(id, s"补记 $i：补充信息若干（作者补记形态）").isRight)
      assert(call("action" -> Json.fromString("close"), "id" -> Json.fromString(id)).isRight)
    }

    val all = events
    val noteEvs = all.filter(TaskListHistory.isNoteEvent)
    val stateEvs = all.filterNot(TaskListHistory.isNoteEvent)
    def byteLen(l: List[TaskListEvent]): Int =
      l.map(e => e.asJson.noSpaces.getBytes("UTF-8").length + 1).sum
    val noteBytes = byteLen(noteEvs)
    val stateBytes = byteLen(stateEvs)
    val totalBytes = noteBytes + stateBytes
    val fileBytes = os.size(histFile).toInt
    def pct(a: Int, b: Int): String = f"${a * 100.0 / math.max(1, b)}%.1f%%"
    def mean(l: List[TaskListEvent], b: Int): String = f"${b.toDouble / math.max(1, l.size)}%.1f B"
    val kinds = all.groupBy(_.kind).view.mapValues(_.size).toList.sortBy(-_._2).map((k, n) => s"$k=$n").mkString(", ")
    println(
      s"""[tbu-list][composition]
         |file bytes=$fileBytes lines=${all.size}
         |note/log content lines=${noteEvs.size} (${pct(noteEvs.size, all.size)}) bytes=$noteBytes (${pct(noteBytes, totalBytes)}) mean=${mean(noteEvs, noteBytes)}
         |state/structural lines=${stateEvs.size} (${pct(stateEvs.size, all.size)}) bytes=$stateBytes (${pct(stateBytes, totalBytes)}) mean=${mean(stateEvs, stateBytes)}
         |kinds: $kinds""".stripMargin)

    assertEquals(totalBytes, fileBytes, "读数口径自校验：逐行 UTF-8 字节（含换行）合计 == 文件实际字节")

end TaskListHistorySpec
