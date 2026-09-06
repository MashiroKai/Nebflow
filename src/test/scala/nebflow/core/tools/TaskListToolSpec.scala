package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import java.time.Instant

/**
 * TaskList 任务工具 spec（2026-09-06 TaskList 批：作者 00:07 提议 + 00:11
 * 首期无前端拍板）。
 *
 * 覆盖（任务书验收点）：
 *  - 四 action（create/update/list/close）单测：happy path + 错误路径；
 *  - status 非法迁移拒绝（done→open 等；→done 单通道 = close）；
 *  - blocks 依赖语义：被依赖条目未闭环时 in_progress/close 拒绝（依赖闸）、
 *    blocked 逃生通道、环检测（含自依赖）、未知依赖 id；
 *  - 损坏 tasks.json 容错：读路径空视图不崩、写路径隔离 quarantaine + 空库续写
 *    （可恢复策略 = 隔离保旧字节 + 空库，不静默覆盖——交付申报同款口径）；
 *  - 持久化：无内存态、操作整读整写同一文件 → 重启后自然持久；
 *  - done 条目 30 天惰性清理（闭环即删 T2 精神）；
 *  - openSummaryLine：open 存在 → 一行摘要；全 done/空/损坏 → 空串。
 *
 * 隔离：PathUtil.setDataRoot(temp)（MemorySnapshotSpec 同款 beforeAll/afterAll
 * 配方）；每用例开头 resetFile 保证互不串扰。变异验红锚点见注释（改坏迁移
 * 矩阵 / 依赖闸任一处，本文件即红）。
 */
class TaskListToolSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-tasklist-spec"))
  var prevRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def file: os.Path = home / "tasks.json"

  /** 每用例从空库开始（无内存态——删文件即全新 store）。 */
  private def resetFile(): Unit =
    if os.exists(file) then os.remove(file)

  private def ctx: ToolContext = ToolContext(projectRoot = "/tmp")

  private def call(obj: JsonObject): Either[ToolError, String] =
    TaskListTool.call(obj, ctx).unsafeRunSync()

  private def obj(fields: (String, Json)*): JsonObject =
    JsonObject.fromIterable(fields)

  private def create(title: String, extra: (String, Json)*): Either[ToolError, String] =
    call(obj(Seq("action" -> Json.fromString("create"), "title" -> Json.fromString(title)) ++ extra*))

  private def update(id: String, extra: (String, Json)*): Either[ToolError, String] =
    call(obj(Seq("action" -> Json.fromString("update"), "id" -> Json.fromString(id)) ++ extra*))

  private def close(id: String, note: Option[String] = None): Either[ToolError, String] =
    call(obj(
      Seq("action" -> Json.fromString("close"), "id" -> Json.fromString(id)) ++
        note.map(n => Seq("note" -> Json.fromString(n))).getOrElse(Seq.empty)*))

  private def list(project: Option[String] = None): Either[ToolError, String] =
    call(obj(
      Seq("action" -> Json.fromString("list")) ++
        project.map(p => Seq("project" -> Json.fromString(p))).getOrElse(Seq.empty)*))

  private def readDisk: Option[TaskListStore.Store] =
    if os.exists(file) then decode[TaskListStore.Store](os.read(file)).toOption else None

  // ===== ① 四 action happy path + 持久化 =====

  test("create→list→close 全链路：id 顺序、落盘合法 JSON、磁盘真身为持久源（重启=重读）"):
    resetFile()
    val r1 = create("设计方案")
    assert(r1.isRight, r1)
    assert(r1.toOption.get.contains("#1 [open]"), r1.toOption.get)
    val r2 = create("实现后端")
    assert(r2.toOption.get.contains("#2 [open]"), r2.toOption.get)

    // 落盘校验：tasks.json 存在且可解码、条目字段完整
    val disk = readDisk
    assert(disk.isDefined, "tasks.json 必须落盘为合法 JSON")
    assertEquals(disk.get.tasks.map(_.id), List("1", "2"), "id 顺序分配")
    assertEquals(disk.get.tasks.head.status, "open")

    // 持久化语义：store 无内存态，重读磁盘即「重启后」视图
    val listed = list(None)
    assert(listed.toOption.get.contains("2 entries"), listed.toOption.get)
    assert(listed.toOption.get.contains("#1 [open] 设计方案"))

    val closed = close("1", Some("方案已定稿 commit abc"))
    assert(closed.toOption.get.contains("→done"), closed.toOption.get)
    val disk2 = readDisk.get
    assertEquals(disk2.tasks.find(_.id == "1").get.status, "done")
    assert(disk2.tasks.find(_.id == "1").get.closedAt.isDefined, "close 记 closedAt")
    assert(disk2.tasks.find(_.id == "1").get.note.exists(_.contains("[done] 方案已定稿")),
      "close note 以 [done] 前缀追加，工作 note 保留")

  test("update：title/note 替换、project 空串清除、blocks 全量替换（[] 清空）"):
    resetFile()
    create("任务A")
    create("任务B")
    assert(update("1", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("2"))).isRight)
    assert(update("1", "note" -> io.circe.Json.fromString("工作备注"), "project" -> io.circe.Json.fromString("projX")).isRight)
    var disk = readDisk.get
    assertEquals(disk.tasks.find(_.id == "1").get.blocks, List("2"))
    assertEquals(disk.tasks.find(_.id == "1").get.project, Some("projX"))

    // note 替换语义（非追加——追加是 close 专属）
    assert(update("1", "note" -> io.circe.Json.fromString("新备注")).isRight)
    disk = readDisk.get
    assertEquals(disk.tasks.find(_.id == "1").get.note, Some("新备注"))

    // blocks 全量替换：[] 清空
    assert(update("1", "blocks" -> io.circe.Json.arr()).isRight)
    disk = readDisk.get
    assertEquals(disk.tasks.find(_.id == "1").get.blocks, Nil)

    // project 空串清除
    assert(update("1", "project" -> io.circe.Json.fromString("")).isRight)
    disk = readDisk.get
    assertEquals(disk.tasks.find(_.id == "1").get.project, None)

  // ===== ② 状态机：非法迁移拒绝（变异验红锚：改坏 isValidTransition 即红）=====

  test("非法迁移拒绝：done 终态（done→open 拒绝）、in_progress→open 拒绝、update 携带 done 指向 close"):
    resetFile()
    create("任务A")
    create("任务B")
    assert(close("1").isRight)

    // done → open：任务书明示必须拒绝
    val r = update("1", "status" -> io.circe.Json.fromString("open"))
    assert(r.isLeft, "done→open 必须拒绝")
    assert(r.swap.toOption.get.message.contains("TASKLIST_STATUS"), r)

    // done → in_progress / blocked 同拒
    assert(update("1", "status" -> io.circe.Json.fromString("in_progress")).isLeft)
    assert(update("1", "status" -> io.circe.Json.fromString("blocked")).isLeft)

    // in_progress → open 拒绝（TeamTask in_progress→pending 同款）
    assert(update("2", "status" -> io.circe.Json.fromString("in_progress")).isRight)
    val r2 = update("2", "status" -> io.circe.Json.fromString("open"))
    assert(r2.isLeft && r2.swap.toOption.get.message.contains("TASKLIST_STATUS"), r2)

    // →done 单通道：update status=done 结构化拒绝并指向 close
    val r3 = update("2", "status" -> io.circe.Json.fromString("done"))
    assert(r3.isLeft && r3.swap.toOption.get.message.contains("TASKLIST_DONE_VIA_CLOSE"), r3)
    // done 前置校验未写盘：#2 仍 in_progress
    assertEquals(readDisk.get.tasks.find(_.id == "2").get.status, "in_progress")

  test("合法迁移全通过 + 同态 no-op：open→in_progress/blocked、in_progress→blocked、blocked→open/in_progress"):
    resetFile()
    create("任务A")
    assert(update("1", "status" -> io.circe.Json.fromString("blocked")).isRight, "open→blocked")
    assert(update("1", "status" -> io.circe.Json.fromString("open")).isRight, "blocked→open")
    assert(update("1", "status" -> io.circe.Json.fromString("in_progress")).isRight, "open→in_progress")
    assert(update("1", "status" -> io.circe.Json.fromString("blocked")).isRight, "in_progress→blocked")
    assert(update("1", "status" -> io.circe.Json.fromString("in_progress")).isRight, "blocked→in_progress")
    assert(update("1", "status" -> io.circe.Json.fromString("in_progress")).isRight, "同态 no-op")
    assertEquals(readDisk.get.tasks.find(_.id == "1").get.status, "in_progress")

  test("未知 status / 未知 id / 空 title / 未知 action 结构化拒绝"):
    resetFile()
    create("任务A")
    assert(update("1", "status" -> io.circe.Json.fromString("paused")).isLeft)
    assert(update("99", "title" -> io.circe.Json.fromString("x")).isLeft)
    assert(create("  ").isLeft, "空 title 拒绝")
    assert(call(obj("action" -> io.circe.Json.fromString("destroy"))).isLeft)
    // 错误码可行动：NO_ID 错误列出 open 条目线索
    val r = update("42", "title" -> io.circe.Json.fromString("x"))
    assert(r.swap.toOption.get.message.contains("#1[open]"), "NO_ID 错误应列出现有条目")

  // ===== ③ blocks 依赖语义（变异验红锚：改坏依赖闸/checkDepsClosed 即红）=====

  test("依赖闸：被依赖条目未闭环时 in_progress/close 拒绝；闭环后放行；blocked 逃生通道"):
    resetFile()
    create("上游任务")   // #1
    create("下游任务", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("1")))  // #2 depends on #1

    // 未闭环 → in_progress 拒绝，错误列出未闭环依赖
    val r = update("2", "status" -> io.circe.Json.fromString("in_progress"))
    assert(r.isLeft, "依赖未闭环 → in_progress 必须拒绝")
    val msg = r.swap.toOption.get.message
    assert(msg.contains("TASKLIST_BLOCKED"), msg)
    assert(msg.contains("#1[open]"), s"错误应列出未闭环依赖: $msg")

    // close 同样受闸
    val rc = close("2")
    assert(rc.isLeft && rc.swap.toOption.get.message.contains("TASKLIST_BLOCKED"), rc)

    // 逃生通道：→blocked 不设闸（记录等待）
    assert(update("2", "status" -> io.circe.Json.fromString("blocked")).isRight, "→blocked 是逃生通道")

    // 依赖闭环（close #1）后：blocked→in_progress 放行、close 放行
    assert(close("1").isRight)
    assert(update("2", "status" -> io.circe.Json.fromString("in_progress")).isRight, "依赖闭环后可开工")
    assert(close("2").isRight, "依赖闭环后可 close")

  test("环检测：自依赖与两节点环均拒绝（TASKLIST_CYCLE），失败不写盘"):
    resetFile()
    create("任务A")
    create("任务B")
    val selfRef = update("1", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("1")))
    assert(selfRef.isLeft && selfRef.swap.toOption.get.message.contains("TASKLIST_CYCLE"), selfRef)
    assert(update("1", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("2"))).isRight)
    val cycle = update("2", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("1")))
    assert(cycle.isLeft && cycle.swap.toOption.get.message.contains("TASKLIST_CYCLE"), cycle)
    // 失败未写盘：#2 blocks 保持空
    assertEquals(readDisk.get.tasks.find(_.id == "2").get.blocks, Nil, "被拒更新不得写盘")

  test("未知依赖 id 拒绝（含 store 为空场景）；依赖已 done 条目合法（闸恒过）"):
    resetFile()
    val r = create("孤儿任务", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("9")))
    assert(r.isLeft && r.swap.toOption.get.message.contains("TASKLIST_BLOCK_UNKNOWN"), r)
    create("已完任务")
    assert(close("1").isRight)
    val r2 = create("后继任务", "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("1")))
    assert(r2.isRight, "依赖已 done 条目合法")
    assert(update("2", "status" -> io.circe.Json.fromString("in_progress")).isRight, "done 依赖闸恒过")

  // ===== ④ 损坏容错（可恢复策略：读=空视图，写=隔离+空库续写）=====

  test("损坏 tasks.json：list 不崩返回空视图；openSummaryLine 返回空串；读路径零写副作用"):
    resetFile()
    os.write(file, "{ this is not json !!!")
    val listed = list(None)
    assert(listed.isRight, "读路径不崩")
    assert(listed.toOption.get.contains("empty"), listed.toOption.get)
    assertEquals(TaskListStore.openSummaryLine(), "", "损坏 → 提醒消失")
    assertEquals(os.list(home).count(_.last.startsWith("tasks.json.corrupt")), 0, "读路径零写副作用")

  test("损坏 tasks.json：首个写操作隔离 quarantaine（旧字节保留）+ 空库续写"):
    resetFile()
    os.write(file, "{{{ broken")
    val r = create("损坏后续写")
    assert(r.isRight, "写路径不崩")
    assert(r.toOption.get.contains("quarantined"), s"申报隔离事实: ${r.toOption.get}")
    val quarantined = os.list(home).filter(_.last.startsWith("tasks.json.corrupt"))
    assertEquals(quarantined.size, 1, "恰好一个隔离文件")
    assertEquals(os.read(quarantined.head), "{{{ broken", "旧字节完整保留（可人工恢复）")
    // 新库只含新条目，id 从 1 重新起算
    val disk = readDisk.get
    assertEquals(disk.tasks.map(_.id), List("1"))
    assertEquals(disk.tasks.head.title, "损坏后续写")

  // ===== ⑤ 闭环即删：done 条目 30 天惰性清理 =====

  test("create 顺带清理 closedAt 超 30 天的 done 条目；解析失败保守保留"):
    resetFile()
    create("老任务")
    create("新任务")
    close("1")
    // 手写老 closedAt（40 天前）
    val old = Instant.parse(java.time.Instant.now().minusSeconds(40L * 24 * 3600).toString)
    val disk = readDisk.get
    val patched = disk.copy(tasks = disk.tasks.map(t =>
      if t.id == "1" then t.copy(closedAt = Some(old.toString)) else t))
    os.write.over(file, patched.asJson.noSpaces)

    val r = create("触发清理")
    assert(r.isRight)
    val disk2 = readDisk.get
    assert(!disk2.tasks.exists(_.id == "1"), "超期 done 条目被惰性清理")
    assertEquals(disk2.tasks.map(_.id), List("2", "3"), "未超期条目保留")

    // closedAt 解析失败 → 保守保留
    val disk3 = readDisk.get
    val patchedBad = disk3.copy(tasks = disk3.tasks.map(t =>
      if t.id == "2" && t.status != "done" then t else t))
    os.write.over(file, (patchedBad.copy(tasks =
      patchedBad.tasks :+ TaskListStore.Entry("99", "坏时间戳", "done", closedAt = Some("not-a-time")))).asJson.noSpaces)
    assert(create("再触发").isRight)
    assert(readDisk.get.tasks.exists(_.id == "99"), "解析失败的 done 条目保守保留")

  // ===== ⑥ openSummaryLine（生命周期提醒数据源）=====

  test("openSummaryLine：open 存在 → 一行含 id/status；全 done → 空串；>5 条折叠计数"):
    resetFile()
    assertEquals(TaskListStore.openSummaryLine(), "", "空库 → 空串")
    create("任务一")
    create("任务二")
    update("2", "status" -> io.circe.Json.fromString("in_progress"))
    val line = TaskListStore.openSummaryLine()
    assert(line.contains("[TaskList]"), line)
    assert(line.contains("2 open"), line)
    assert(line.contains("#1[open]"), line)
    assert(line.contains("#2[in_progress]"), line)
    assert(!line.contains("\n"), "必须是一行")

    // 全 done → 提醒消失
    close("1")
    close("2")
    assertEquals(TaskListStore.openSummaryLine(), "", "全 done → 提醒消失")

    // >5 条折叠：建 6 条 open（#3..#8）→ 首显 5 条 + (+1 more)
    (3 to 8).foreach(i => create(s"批量任务$i"))
    val line2 = TaskListStore.openSummaryLine()
    assert(line2.contains("6 open"), line2)
    assert(line2.contains("(+1 more)"), line2)

  test("list：project 精确过滤 + 依赖状态渲染（⚠deps-open 标记）"):
    resetFile()
    create("甲", "project" -> io.circe.Json.fromString("projA"))
    create("乙", "project" -> io.circe.Json.fromString("projB"))
    create("丙", "project" -> io.circe.Json.fromString("projA"),
      "blocks" -> io.circe.Json.arr(io.circe.Json.fromString("1")))
    val filtered = list(Some("projA"))
    assert(filtered.toOption.get.contains("#1"), filtered.toOption.get)
    assert(filtered.toOption.get.contains("#3"), filtered.toOption.get)
    assert(!filtered.toOption.get.contains("#2 "), s"projB 条目被过滤: ${filtered.toOption.get}")
    assert(filtered.toOption.get.contains("deps: #1[open] ⚠deps-open"), "未闭环依赖带警示标记")
    // 依赖闭环后警示消失
    assert(close("1").isRight)
    val after = list(Some("projA"))
    assert(!after.toOption.get.contains("⚠deps-open"), after.toOption.get)

  // ===== ⑦ 工具面隔离（硬约束）的注册表侧事实 =====

  test("TaskList 进注册表；schema 恰四 action"):
    assert(ToolRegistry.TOOL_MAP.contains("TaskList"), "registry 挂 TaskList（Nebula 注入源）")
    val schema = TaskListTool.inputSchema
    val actions = schema("properties").get.asObject.get("action").get
      .asObject.get("enum").get.asArray.get.map(j => j.asString.get).toList
    assertEquals(actions, List("create", "update", "list", "close"), "恰四 action")
    // id 在 update/close 的必填校验在工具层（schema required 仅 action——与
    // MemoryEditTool 同款：参数组合校验在 call 内做结构化报错）

end TaskListToolSpec
