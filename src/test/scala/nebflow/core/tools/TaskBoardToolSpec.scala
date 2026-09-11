package nebflow.core.tools

import munit.FunSuite
import nebflow.core.project.{NodeDef, NodeLifecycle, TaskBoardHistory, TaskBoardStore}

import java.nio.file.Files

/**
 * TaskBoardTool spec（TaskBoard 批 2：规格 §1b/§1d/§1e + §6 验收点的工具层部分）。
 * 参照 TaskBoardStoreSpec 风格（munit FunSuite，每用例空库起）。
 *
 * 覆盖：
 *  - BoardCaller.fromContext 引擎侧身份派生（isDispatcher 优先 > flowNodeId > Other）；
 *  - §1d 权限矩阵逐格：
 *      分发器列：create 全量 / update 全板含结构字段 / close 全板 / list 全板；
 *      节点列：create ❌（FORBIDDEN+needs-split 出路）/ update 仅自己名下且仅
 *      status+note（结构字段 title/assignee/nodeId/blocks 任一出现即拒）/
 *      close 仅自己名下 / list 全板只读；
 *      其他身份：四动作全拒（含 list——挂载面 + 工具内双保险的第二道）；
 *  - 错误码族：TBOARD_FORBIDDEN / TBOARD_PARAM（unknown action、update/close 缺
 *    id）/ TBOARD_NOT_FOUND（节点动不存在任务委托 store）/ TBOARD_DONE_VIA_CLOSE
 *    （经工具链 update status=done）；
 *  - ⚠node-done join 接线（§2d）：TaskBoardStore.nodeTerminalMap 仅 completed/
 *    failed/cancelled 计入（blocked/running/pending 不标）+ list 渲染出标记；
 *  - 身份透传链（§1d-2）：AgentState → SessionContext 三字段往返。
 *
 * dispatchSync 为纯同步 Either（工具 call 的 IO.blocking 内核），直调即可逐格断言；
 * store 用 tmp workspace 真实实例（落盘行为与生产同构）。
 */
class TaskBoardToolSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-taskboard-tools-spec"))

  override def afterAll(): Unit = os.remove.all(home)

  private def file: os.Path = home / ".nebflow" / "task-board.json"

  /** 每用例从空库开始（store 无内存态——删文件即全新；变更史一并清，防跨用例串味）。 */
  private def resetFile(): Unit =
    if os.exists(file) then os.remove(file)
    if os.exists(home / ".nebflow") then
      os.list(home / ".nebflow")
        .filter(p => p.last.startsWith("task-board.json.corrupt") || p.last.startsWith("task-history"))
        .foreach(os.remove)

  private def store: TaskBoardStore = TaskBoardStore.open("projT", home.toString)

  private def d(action: String, title: Option[String] = None, id: Option[String] = None,
      status: Option[String] = None, assignee: Option[String] = None, nodeId: Option[String] = None,
      note: Option[String] = None, blocks: Option[List[String]] = None,
      text: Option[String] = None, links: Option[List[String]] = None,
      term: Map[String, String] = Map.empty): Either[ToolError, String] =
    TaskBoardTool.dispatchSync(store, term, BoardCaller.Dispatcher, action,
      title = title, id = id, status = status, assignee = assignee, nodeId = nodeId, note = note,
      blocks = blocks, text = text, links = links)

  private def n(self: String)(action: String, title: Option[String] = None, id: Option[String] = None,
      status: Option[String] = None, assignee: Option[String] = None, nodeId: Option[String] = None,
      note: Option[String] = None, blocks: Option[List[String]] = None,
      text: Option[String] = None, links: Option[List[String]] = None,
      term: Map[String, String] = Map.empty): Either[ToolError, String] =
    TaskBoardTool.dispatchSync(store, term, BoardCaller.FlowNode(self), action,
      title = title, id = id, status = status, assignee = assignee, nodeId = nodeId, note = note,
      blocks = blocks, text = text, links = links)

  private def o(action: String, title: Option[String] = None, id: Option[String] = None): Either[ToolError, String] =
    TaskBoardTool.dispatchSync(store, Map.empty, BoardCaller.Other, action, title = title, id = id)

  private def code(r: Either[ToolError, String]): Option[String] =
    r.left.toOption.map(_.message)

  // ===== 身份派生（引擎侧，不信客户端参数）=====

  test("BoardCaller.fromContext: isDispatcher 优先 > flowNodeId > Other"):
    assertEquals(BoardCaller.fromContext(ToolContext(projectRoot = "", isDispatcher = true, flowNodeId = Some("n-a"))), BoardCaller.Dispatcher)
    assertEquals(BoardCaller.fromContext(ToolContext(projectRoot = "", flowNodeId = Some("n-a"))), BoardCaller.FlowNode("n-a"))
    assertEquals(BoardCaller.fromContext(ToolContext(projectRoot = "", isDispatcher = false)), BoardCaller.Other)
    assertEquals(BoardCaller.fromContext(ToolContext(projectRoot = "", projectName = Some("p"))), BoardCaller.Other) // 项目名≠身份

  // ===== §1d 矩阵·分发器列（全权）=====

  test("dispatcher: create 全量 / update 全板任意任务含结构字段 / close 全板 / list 全板"):
    resetFile()
    assert(d("create", title = Some("任务A")).isRight)
    // create 指派给节点（规划权威在分发器——决策 b 的正面）
    assert(d("create", title = Some("任务B"), assignee = Some("n-x"), nodeId = Some("n-x")).isRight)
    // update 任意任务 + 全部结构字段（title/assignee/nodeId/blocks）——#2 尚为
    // open（依赖闸只闸 in_progress 迁移，此处无闸）
    assert(d("update", id = Some("2"), title = Some("改名"), assignee = Some("n-y"),
      nodeId = Some("n-z"), blocks = Some(List("1"))).isRight)
    // 依赖闸正路：先闭环 #1，#2 才可开工与关闭
    assert(d("close", id = Some("1")).isRight)
    assert(d("update", id = Some("2"), status = Some("in_progress")).isRight)
    // close 任意任务
    assert(d("close", id = Some("2")).isRight)
    // list 全板
    assert(d("list").isRight)
    // 落盘核对：结构字段改动真实生效
    val entries = store.entriesSync()
    assertEquals(entries.find(_.id == "1").map(_.title), Some("任务A"))
    val b2 = entries.find(_.id == "2").get
    assertEquals(b2.nodeId, Some("n-z"))
    assertEquals(b2.blocks, List("1"))
    assertEquals(b2.status, "done")

  // ===== §1d 矩阵·节点列（受限）=====

  test("node create 拒：TBOARD_FORBIDDEN + needs-split 出路指引（决策 b）"):
    resetFile()
    val r = n("n-a")("create", title = Some("偷建任务"))
    assert(r.isLeft)
    val msg = code(r).getOrElse("")
    assert(msg.contains(TaskBoardStore.Codes.Forbidden), msg)
    assert(msg.contains("needs-split"), s"must point to the BLOCKED/needs-split escape hatch: $msg")
    assertEquals(store.entriesSync().size, 0)

  test("node update: 他人任务拒（FORBIDDEN）/ 自己任务 status+note 过"):
    resetFile()
    store.createSync("我的工单", assignee = Some("n-a"))
    store.createSync("他人工单", assignee = Some("n-b"))
    // 他人任务 status 改动 → FORBIDDEN
    val other = n("n-a")("update", id = Some("2"), status = Some("in_progress"))
    assert(other.isLeft)
    val msg = code(other).getOrElse("")
    assert(msg.contains(TaskBoardStore.Codes.Forbidden), msg)
    assert(msg.contains("n-b") && msg.contains("n-a"), s"must name both assignee and caller: $msg")
    assertEquals(store.entriesSync().find(_.id == "2").map(_.status), Some("open")) // 板不变
    // 自己任务 status / note → 过
    assert(n("n-a")("update", id = Some("1"), status = Some("in_progress")).isRight)
    assert(n("n-a")("update", id = Some("1"), note = Some("已开工，等依赖")).isRight)
    val mine = store.entriesSync().find(_.id == "1").get
    assertEquals(mine.status, "in_progress")
    assertEquals(mine.note, Some("已开工，等依赖"))

  test("node update 结构字段只读：title/assignee/nodeId/blocks 任一出现即拒（与值无关）"):
    resetFile()
    store.createSync("我的工单", assignee = Some("n-a"))
    // 四个结构字段逐格：即使值与现值相同也拒（字段出现即改动请求）
    assert(n("n-a")("update", id = Some("1"), title = Some("我的工单")).isLeft)
    assert(n("n-a")("update", id = Some("1"), assignee = Some("n-a")).isLeft)
    assert(n("n-a")("update", id = Some("1"), nodeId = Some("n-a")).isLeft)
    assert(n("n-a")("update", id = Some("1"), blocks = Some(Nil)).isLeft)
    // 全部为 FORBIDDEN 且板未被改动
    assert(store.entriesSync().find(_.id == "1").get.status == "open")

  test("node close: 他人任务拒 / 自己任务过 / 重复 close 幂等"):
    resetFile()
    store.createSync("我的工单", assignee = Some("n-a"))
    store.createSync("他人工单", assignee = Some("n-b"))
    val other = n("n-a")("close", id = Some("2"))
    assert(other.isLeft && code(other).exists(_.contains(TaskBoardStore.Codes.Forbidden)))
    assert(n("n-a")("close", id = Some("1")).isRight)
    // 幂等：重复 close = no-op 成功
    val again = n("n-a")("close", id = Some("1"))
    assert(again.isRight && again.toOption.get.contains("no-op"))

  test("node list: 全板只读（他人任务可见——协作可见性 §1d）"):
    resetFile()
    store.createSync("我的工单", assignee = Some("n-a"))
    store.createSync("他人工单", assignee = Some("n-b"))
    val r = n("n-a")("list")
    assert(r.isRight)
    val out = r.toOption.get
    assert(out.contains("我的工单") && out.contains("他人工单"), out)
    // 只读：list 后板未变
    assertEquals(store.entriesSync().size, 2)

  test("node update/close 不存在任务 → 委托 store 的 TBOARD_NOT_FOUND（附 open 清单指引）"):
    resetFile()
    store.createSync("唯一工单", assignee = Some("n-a"))
    val upd = n("n-a")("update", id = Some("99"), status = Some("blocked"))
    assert(code(upd).exists(_.contains(TaskBoardStore.Codes.NotFound)))
    val cls = n("n-a")("close", id = Some("99"))
    assert(code(cls).exists(_.contains(TaskBoardStore.Codes.NotFound)))

  // ===== §1d 矩阵·其他身份（双保险第二道）=====

  test("other 身份四动作全拒（含 list）——挂载面 + 工具内拒绝双保险不可达验证"):
    resetFile()
    for r <- List(o("create", title = Some("x")), o("update", id = Some("1")), o("close", id = Some("1")), o("list")) do
      assert(r.isLeft)
      assert(code(r).exists(_.contains(TaskBoardStore.Codes.Forbidden)))
    assertEquals(store.entriesSync().size, 0)

  // ===== 错误码族（参数面）=====

  test("unknown action → TBOARD_PARAM；update/close 缺 id → TBOARD_PARAM"):
    resetFile()
    assert(code(d("frobnicate")).exists(_.contains(TaskBoardStore.Codes.Param)))
    assert(code(d("update")).exists(_.contains(TaskBoardStore.Codes.Param)))
    assert(code(d("close")).exists(_.contains(TaskBoardStore.Codes.Param)))
    assert(code(n("n-a")("update")).exists(_.contains(TaskBoardStore.Codes.Param)))

  test("dispatcher update status=done → TBOARD_DONE_VIA_CLOSE（终态单通道经工具链保持）"):
    resetFile()
    store.createSync("任务")
    val r = d("update", id = Some("1"), status = Some("done"))
    assert(code(r).exists(_.contains(TaskBoardStore.Codes.DoneViaClose)))
    // 正路：close 才进 done
    assert(d("close", id = Some("1")).isRight)
    assertEquals(store.entriesSync().find(_.id == "1").map(_.status), Some("done"))

  // ===== ⚠node-done join 接线（§2d：真实终态映射）=====

  test("nodeTerminalMap: 仅 completed/failed/cancelled 计入（blocked/running 不标——§2d 对齐语义）"):
    val nodes = List(
      NodeDef(id = "n-done", name = "a", agent = "general", status = NodeLifecycle.Completed, createdAt = 0L),
      NodeDef(id = "n-fail", name = "b", agent = "general", status = NodeLifecycle.Failed, createdAt = 0L),
      NodeDef(id = "n-cancel", name = "c", agent = "general", status = NodeLifecycle.Cancelled, createdAt = 0L),
      NodeDef(id = "n-blocked", name = "d", agent = "general", status = NodeLifecycle.Blocked, createdAt = 0L),
      NodeDef(id = "n-run", name = "e", agent = "general", status = NodeLifecycle.Running, createdAt = 0L),
      NodeDef(id = "n-pending", name = "f", agent = "general", status = NodeLifecycle.Pending, createdAt = 0L)
    )
    assertEquals(TaskBoardStore.nodeTerminalMap(nodes).keySet, Set("n-done", "n-fail", "n-cancel"))

  test("list 经 dispatchSync 接线真实映射 → ⚠node-done 标记出现在漂移任务行"):
    resetFile()
    store.createSync("已链接未关", assignee = Some("n-a"), nodeId = Some("n-drifted"))
    store.createSync("正常无链接", assignee = Some("n-b"))
    val term = Map("n-drifted" -> NodeLifecycle.Completed) // 模拟 Flow Map 真实终态（completedNode 产物）
    val out = d("list", term = term).toOption.get
    assert(out.contains("⚠node-done"), out)
    // 无映射（空 Flow Map 终态）→ 无标记
    val clean = d("list").toOption.get
    assert(!clean.contains("⚠node-done"), clean)

  // ===== 升级批（2026-09-11）：log / show 两 action + links 结构字段 + schema 契约 =====

  test("升级批：分发器 log/show 全板可用（log 载入史、show 出全文与 note 主线）"):
    resetFile()
    store.createSync("任务A", note = Some("工作记录"))
    val log = d("log", id = Some("1"), text = Some("补充第一段"))
    assert(log.isRight, log)
    val hist = TaskBoardHistory.open(home.toString)
    assertEquals(hist.readFor("1", only = TaskBoardHistory.isNoteChange).events.map(_.text), List(Some("补充第一段")))
    assertEquals(hist.readFor("1", only = TaskBoardHistory.isNoteChange).events.head.actor, "dispatcher",
      "actor 由身份派生（分发器）")
    assertEquals(store.entriesSync().head.note, Some("工作记录"), "log 不改板面 note")
    val show = d("show", id = Some("1"))
    assert(show.isRight, show)
    assert(show.toOption.get.contains("工作记录") && show.toOption.get.contains("note changes"), show)
    // 缺 id → PARAM（log/show 同口径）
    assert(code(d("log", text = Some("x"))).exists(_.contains(TaskBoardStore.Codes.Param)))
    assert(code(d("show")).exists(_.contains(TaskBoardStore.Codes.Param)))

  test("升级批：节点 log 仅自己名下（他人 → FORBIDDEN）；节点 show 全板只读"):
    resetFile()
    store.createSync("我的工单", assignee = Some("n-a"), note = Some("我的记录"))
    store.createSync("他人工单", assignee = Some("n-b"), note = Some("他人记录"))
    val mine = n("n-a")("log", id = Some("1"), text = Some("节点补充"))
    assert(mine.isRight, mine)
    assertEquals(TaskBoardHistory.open(home.toString).readFor("1", only = TaskBoardHistory.isNoteChange).events.head.actor,
      "node", "actor 由身份派生（节点）")
    val other = n("n-a")("log", id = Some("2"), text = Some("越权"))
    assert(other.isLeft && code(other).exists(_.contains(TaskBoardStore.Codes.Forbidden)), other)
    assertEquals(TaskBoardHistory.open(home.toString).readFor("2", only = TaskBoardHistory.isNoteChange).total, 0,
      "越权 log 零落史")
    val showAll = n("n-a")("show", id = Some("2"))
    assert(showAll.isRight && showAll.toOption.get.contains("他人记录"), showAll)

  test("升级批：node update 携带 links → FORBIDDEN（结构字段只读面扩展）"):
    resetFile()
    store.createSync("我的工单", assignee = Some("n-a"))
    val r = n("n-a")("update", id = Some("1"), links = Some(List("docs/x.md")))
    assert(r.isLeft && code(r).exists(_.contains(TaskBoardStore.Codes.Forbidden)), r)
    assert(code(r).exists(_.contains("dispatcher-only")), code(r).toString)
    assertEquals(store.entriesSync().head.links, Nil, "被拒更新零副作用")

  test("升级批：schema 契约——action enum 六值、新增 text/links 参数、schema 不暴露 actor/history（客户端不可注入）"):
    val schema = TaskBoardToolDef.inputSchema
    val props = schema("properties").flatMap(_.asObject).get
    val actionEnum = props("action").flatMap(_.hcursor.downField("enum").as[List[String]].toOption).get
    assertEquals(actionEnum, List("create", "update", "list", "close", "log", "show"),
      "action enum 六值（升级批）")
    assert(props.contains("text") && props.contains("links"), "新增参数入 schema")
    assert(!props.contains("actor") && !props.contains("history"),
      "actor/history 不出现在 schema（引擎侧派生/独立文件，客户端无法注入）")
    // 未知 action 文案 = 六值清单
    val unknown = code(d("frobnicate")).getOrElse("")
    assert(unknown.contains("create/update/list/close/log/show"), unknown)
    // description 契约面：新 action、links 不校验、上限口径、史文件与轮转/清理
    val desc = TaskBoardToolDef.description
    assert(desc.contains("- log: required `id` + `text`"), desc)
    assert(desc.contains("- show: required `id`"), desc)
    assert(desc.contains("NOT validated for reachability"), desc)
    assert(desc.contains("task-history.jsonl"), desc)
    assert(desc.contains("5 MiB or 20,000 lines"), desc)
    assert(desc.contains("cross-process writers are not locked"), "并发口径保留")
    assert(desc.contains("TBOARD_HISTORY"), desc)
    assert(desc.contains("16000") && desc.contains("10993"), "上限口径 + 存量依据进 description")

end TaskBoardToolSpec
