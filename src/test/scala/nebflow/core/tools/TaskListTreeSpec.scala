package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.Files

/**
 * TaskList `parentId`（父子/包含边）与 `links` 自由锚 spec —— 升级批 R9 主用例。
 *
 * 语义（D4）：`parentId` = 包含（A 是 B 的子任务），`blocks` = 依赖（A 依赖 B）。
 * 两者**正交可共存**（A 是 B 的父 + B blocks A 合法）；父状态**永不派生**
 * （子全 done 不自动关父；关父不改写子）；父链深度 root=1，≤5 层。
 *
 * 覆盖：
 *  - 父链渲染（root first，悬空父 `[gone]`）+ 直接子（≤20 后 `(+N more)`）+ 后代计数；
 *  - 错误路径：自环 / 回指 → `TASKLIST_PARENT_CYCLE`，未知父 → `TASKLIST_PARENT_UNKNOWN`，
 *    第 6 层 → `TASKLIST_PARENT_DEPTH`（三者均为**行为闸**，须拒绝且不写盘）；
 *  - `parentId=""` 清除（update 空串语义）；
 *  - 状态不派生：close 父不级联 + 结果行给 open 子提示；子未闭环不给父打 done；
 *  - `list` 的 `⚠children-open` 标记（收口需显式 close 的可见信号）；
 *  - parent × blocks 正交：互不误报（parent 边不参与依赖环 DFS，反之亦然）；
 *  - `links`：自由字符串、零可达性校验、去重、≤20 条 / 单条 ≤300 字符上限；
 *  - prune 后的稀疏树：父被清掉 → 子仍可达且父链显示 `[gone]`。
 *
 * 隔离：`PathUtil.setDataRoot(temp)`（TaskListToolSpec 同款配方）；真实
 * `~/.nebflow/` 数据面零触碰。
 */
class TaskListTreeSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-tasklist-tree"))
  var prevRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def file: os.Path = home / "tasks.json"

  private def reset(): Unit =
    List(file, home / "tasks-history.jsonl", home / "tasks-history.1.jsonl")
      .foreach(p => if os.exists(p) then os.remove(p))
    os.list(home).filter(_.last.startsWith("tasks.json.corrupt")).foreach(os.remove(_))

  private def ctx: ToolContext = ToolContext(projectRoot = "/tmp")

  private def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  private def call(fields: (String, Json)*): Either[ToolError, String] =
    TaskListTool.call(obj(fields*), ctx).unsafeRunSync()

  private def create(title: String, extra: (String, Json)*): Either[ToolError, String] =
    call(Seq("action" -> Json.fromString("create"), "title" -> Json.fromString(title)) ++ extra*)

  private def update(id: String, extra: (String, Json)*): Either[ToolError, String] =
    call(Seq("action" -> Json.fromString("update"), "id" -> Json.fromString(id)) ++ extra*)

  private def show(id: String): Either[ToolError, String] =
    call("action" -> Json.fromString("show"), "id" -> Json.fromString(id))

  private def list(): Either[ToolError, String] =
    call("action" -> Json.fromString("list"))

  private def str(s: String): Json = Json.fromString(s)
  private def arr(xs: String*): Json = Json.arr(xs.map(Json.fromString)*)

  private def tasks: List[TaskListEntry] = decode[TaskListData](os.read(file)).toOption.get.tasks

  private def err(r: Either[ToolError, String]): String =
    r.swap.toOption.get.message

  // ===== ① 父链 / 直接子 / 后代计数 =====

  test("父链（root first）+ 直接子 + 后代计数：链上每层可见，子不展开整棵子树"):
    reset()
    assert(create("根", "parentId" -> str("")).isRight)                 // #1（空串 = 无父）
    assert(create("中层", "parentId" -> str("1")).isRight)              // #2
    assert(create("叶子", "parentId" -> str("2")).isRight)              // #3
    assert(create("叶子的子", "parentId" -> str("3")).isRight)          // #4

    val leaf = show("4").toOption.get
    assert(leaf.contains("parent chain (root first): #1[open] 根 → #2[open] 中层 → #3[open] 叶子"),
      s"父链 root first 且逐层可见: $leaf")
    assert(!leaf.contains("children ("), "叶子无子 ⇒ 无 children 行")

    val mid = show("2").toOption.get
    assert(mid.contains("children (1, 1 open): #3[open] 叶子"), mid)
    assert(mid.contains("subtree: 2 descendant(s) / 2 open"), s"后代计数（含孙）: $mid")
    assert(!mid.contains("#4[open] 叶子的子"), s"直接子不展开整棵子树: $mid")

    val root = show("1").toOption.get
    assert(root.contains("children (1, 1 open): #2[open] 中层"), root)
    assert(root.contains("subtree: 3 descendant(s) / 3 open"), root)

    // create 结果行带 parent 提示（可读性：确认挂上了）
    val r = create("回挂", "parentId" -> str("1"))
    assert(r.toOption.get.contains("(parent: #1)"), r.toOption.get)

  test("直接子 >20 → 只列前 20 + `(+N more)`（不静默截断）"):
    reset()
    assert(create("多子之父").isRight) // #1
    (2 to 23).foreach(i => assert(create(s"子$i", "parentId" -> str("1")).isRight))
    val out = show("1").toOption.get
    assert(out.contains("children (22, 22 open):"), out)
    assert(out.contains("(+2 more)"), s"超 20 必须明示剩余计数: $out")
    assert(out.contains("subtree: 22 descendant(s) / 22 open"), out)

  // ===== ② 错误路径（行为闸）=====

  test("自环与回指 → TASKLIST_PARENT_CYCLE（拒绝且不写盘）"):
    reset()
    assert(create("甲").isRight)  // #1
    assert(create("乙").isRight)  // #2
    val selfRef = update("1", "parentId" -> str("1"))
    assert(selfRef.isLeft && err(selfRef).contains("TASKLIST_PARENT_CYCLE"), selfRef)
    assert(update("2", "parentId" -> str("1")).isRight)
    val back = update("1", "parentId" -> str("2"))
    assert(back.isLeft && err(back).contains("TASKLIST_PARENT_CYCLE"), back)
    assertEquals(tasks.find(_.id == "1").get.parentId, None, "被拒更新不得写盘")

  test("未知父 id → TASKLIST_PARENT_UNKNOWN（create 与 update 同口径）"):
    reset()
    assert(create("孤儿", "parentId" -> str("9")).isLeft)
    assert(err(create("孤儿", "parentId" -> str("9"))).contains("TASKLIST_PARENT_UNKNOWN"))
    assert(create("甲").isRight)
    val r = update("1", "parentId" -> str("42"))
    assert(r.isLeft && err(r).contains("TASKLIST_PARENT_UNKNOWN"), r)
    assert(err(r).contains("#42"), s"错误须点出具体 id: ${err(r)}")

  test("父链深度 root=1、≤5 层；第 6 层 → TASKLIST_PARENT_DEPTH（附修法）"):
    reset()
    assert(create("L1").isRight)                                     // #1 depth1
    assert(create("L2", "parentId" -> str("1")).isRight)             // #2 depth2
    assert(create("L3", "parentId" -> str("2")).isRight)             // #3 depth3
    assert(create("L4", "parentId" -> str("3")).isRight)             // #4 depth4
    assert(create("L5", "parentId" -> str("4")).isRight)             // #5 depth5 —— 上限
    assertEquals(TaskListStore.parentDepthOf(tasks, "5"), 5, "root = 1 层")
    val r = create("L6", "parentId" -> str("5"))
    assert(r.isLeft && err(r).contains("TASKLIST_PARENT_DEPTH"), r)
    assert(err(r).contains("max 5"), s"错误须给上限: ${err(r)}")
    assert(err(r).contains("blocks"), s"错误须给修法出路（改用依赖）: ${err(r)}")
    // 同一闸在 update 上生效（斜接）
    assert(create("游离").isRight) // #6
    val u = update("6", "parentId" -> str("5"))
    assert(u.isLeft && err(u).contains("TASKLIST_PARENT_DEPTH"), u)

  test("parentId 空串 = 清除（update 语义）；缺省 = 保留"):
    reset()
    assert(create("父").isRight)
    assert(create("子", "parentId" -> str("1")).isRight)
    assert(update("2", "note" -> str("改备注（不该动 parentId）")).isRight)
    assertEquals(tasks.find(_.id == "2").get.parentId, Some("1"), "缺省保留父边")
    assert(update("2", "parentId" -> str("")).isRight)
    assertEquals(tasks.find(_.id == "2").get.parentId, None, "空串清除父边")
    val out = show("2").toOption.get
    assert(!out.contains("parent chain"), s"清除后无父链行: $out")

  // ===== ③ 状态不派生（9.5A）=====

  test("状态不派生：子全 done 不自动关父；关父不级联，但结果行给 open 子提示"):
    reset()
    assert(create("父").isRight)
    assert(create("子A", "parentId" -> str("1")).isRight)
    assert(create("子B", "parentId" -> str("1")).isRight)

    // 子全 done → 父仍 open（无派生）
    assert(call("action" -> str("close"), "id" -> str("2")).isRight)
    assert(call("action" -> str("close"), "id" -> str("3")).isRight)
    assertEquals(tasks.find(_.id == "1").get.status, "open", "子全 done 不自动关父")
    val out = show("1").toOption.get
    assert(out.contains("children (2, 0 open)"), out)
    assert(out.contains("subtree: 2 descendant(s) / 0 open"), out)
    assert(list().toOption.get.linesIterator.find(_.startsWith("#1 ")).get.contains("⚠children-open") == false,
      "子全 done ⇒ 无 ⚠children-open")

    // 反向：父 close 不级联，open 子仍在，结果行明示
    reset()
    assert(create("父").isRight)
    assert(create("子", "parentId" -> str("1")).isRight)
    val closed = call("action" -> str("close"), "id" -> str("1"))
    assert(closed.isRight, closed)
    assert(closed.toOption.get.contains("open child(ren) remain — not cascaded"), closed.toOption.get)
    assert(closed.toOption.get.contains("#2[open] 子"), closed.toOption.get)
    assertEquals(tasks.find(_.id == "2").get.status, "open", "关父不改写子")

  test("list 的 ⚠children-open：存在 open 子且自身未闭环时打标；子闭环后消失"):
    reset()
    assert(create("父").isRight)
    assert(create("子", "parentId" -> str("1")).isRight)
    val line1 = list().toOption.get.linesIterator.find(_.startsWith("#1 ")).get
    assert(line1.contains("⚠children-open"), line1)
    val line2 = list().toOption.get.linesIterator.find(_.startsWith("#2 ")).get
    assert(!line2.contains("⚠children-open"), "子无子 ⇒ 无标记")
    assert(call("action" -> str("close"), "id" -> str("2")).isRight)
    val line1b = list().toOption.get.linesIterator.find(_.startsWith("#1 ")).get
    assert(!line1b.contains("⚠children-open"), s"子闭环后标记消失: $line1b")

  // ===== ④ parent × blocks 正交 =====

  test("parent 与 blocks 正交：可共存；依赖 DFS 不吃 parent 边，包含环检测也不吃依赖边"):
    reset()
    assert(create("父A").isRight)                             // #1 —— 包含边 #1→#2
    assert(create("子B", "parentId" -> str("1")).isRight)     // #2 —— 包含边 #2→#3
    assert(create("孙C", "parentId" -> str("2")).isRight)     // #3
    // 共存：B 依赖 A（依赖边 #2→#1，与包含边 #1→#2 反向）—— 合法
    assert(update("2", "blocks" -> arr("1")).isRight, "子依赖父合法（两条边集语义正交）")
    assertEquals(tasks.find(_.id == "2").get.parentId, Some("1"))
    assertEquals(tasks.find(_.id == "2").get.blocks, List("1"))
    // 依赖闸照常生效：B 依赖未闭环的 A ⇒ B 不得开工（parent 关系不豁免依赖闸）
    val start = update("2", "status" -> str("in_progress"))
    assert(start.isLeft && err(start).contains("TASKLIST_BLOCKED"), start)
    // ② 依赖 DFS 不吃 parent 边：#3 依赖 #1（依赖边 #3→#1）。若 DFS 把包含边
    //    #1→#2→#3 一并算进去，这里会被**误报**成环 —— 必须放行。
    assert(update("3", "blocks" -> arr("1")).isRight,
      "依赖 #3→#1 与包含链 #1→#2→#3 反向共存 ⇒ parent 边未混入依赖 DFS")
    // ③ 反之亦然：包含环检测不被依赖边影响（#1 挂到 #3 下 = 包含成环 → 仍拒）
    val cyc = update("1", "parentId" -> str("3"))
    assert(cyc.isLeft && err(cyc).contains("TASKLIST_PARENT_CYCLE"), cyc)
    // ④ 对照：同一对 id 走依赖反方向 → 真依赖环（1→3 与既有 3→1 闭环）→ TASKLIST_CYCLE
    val depCyc = update("1", "blocks" -> arr("3"))
    assert(depCyc.isLeft && err(depCyc).contains("TASKLIST_CYCLE"), depCyc)
    assert(depCyc.swap.toOption.get.message.contains("TASKLIST_CYCLE") &&
      !depCyc.swap.toOption.get.message.contains("TASKLIST_PARENT_CYCLE"),
      "依赖环与包含环的码必须各归各（不混报）")

  // ===== ⑤ links（自由锚，零可达性校验）=====

  test("links：自由字符串、不校验可达性、去重、空串剔除、全量替换（[] 清空）"):
    reset()
    val ghosts = List("no/such/path.md", "deadbeef", "https://example.invalid/x")
    assert(create("带锚任务", "links" -> Json.arr(ghosts.map(Json.fromString)*)).isRight)
    assertEquals(tasks.head.links, ghosts, "不存在的路径/hash 照收（不可达性不校验）")
    var out = show("1").toOption.get
    assert(out.contains("links (3): no/such/path.md, deadbeef, https://example.invalid/x"), out)

    // 去重 + trim + 空串剔除
    assert(update("1", "links" -> arr(" a ", "a", "", "  ", "b")).isRight)
    assertEquals(tasks.head.links, List("a", "b"))
    assert(update("1", "links" -> Json.arr()).isRight)
    assertEquals(tasks.head.links, Nil, "[] 全量清空")
    out = show("1").toOption.get
    assert(!out.contains("links ("), s"清空后不渲染 links 行: $out")

  test("links 上限：>20 条拒；单条 >300 字符拒（均 TASKLIST_PARAM + 修法）"):
    reset()
    assert(create("任务").isRight)
    val tooMany = update("1", "links" -> Json.arr((1 to 21).map(i => Json.fromString(s"l$i"))*))
    assert(tooMany.isLeft && err(tooMany).contains("TASKLIST_PARAM"), tooMany)
    assert(err(tooMany).contains("max 20"), err(tooMany))
    assertEquals(tasks.head.links, Nil, "被拒不写盘")
    val tooLong = update("1", "links" -> arr("x" * 301))
    assert(tooLong.isLeft && err(tooLong).contains("TASKLIST_PARAM"), tooLong)
    assert(err(tooLong).contains("max 300"), err(tooLong))
    assert(update("1", "links" -> arr("x" * 300)).isRight, "300 恰为上限，通过")

  test("log 可带 links：并入该条史事件（不改任务体）"):
    reset()
    assert(create("任务").isRight)
    assert(call("action" -> str("log"), "id" -> str("1"), "text" -> str("补记"),
      "links" -> arr("commit abc1234")).isRight)
    assertEquals(tasks.head.links, Nil, "log 的 links 不进任务体")
    val out = show("1").toOption.get
    assert(out.contains("[links: commit abc1234]"), out)
    assert(out.contains("补记"), out)

  // ===== ⑥ prune 后的稀疏树 =====

  test("prune 稀疏树：父被清掉后子仍可达，父链按 `[gone]` 渲染（禁回填）"):
    reset()
    assert(create("将清父").isRight)                        // #1
    assert(create("留子", "parentId" -> str("1")).isRight)   // #2
    assert(call("action" -> str("close"), "id" -> str("1")).isRight)
    assert(call("action" -> str("close"), "id" -> str("2")).isRight)
    // 手写 40 天前 closedAt（只动 temp 数据根）
    val store = decode[TaskListData](os.read(file)).toOption.get
    val old = java.time.Instant.now().minusSeconds(40L * 24 * 3600).toString
    os.write.over(file,
      store.copy(tasks = store.tasks.map(t => if t.id == "1" then t.copy(closedAt = Some(old)) else t)).asJson.noSpaces)
    assert(create("触发 prune").isRight)
    assert(!tasks.exists(_.id == "1"), "父被 prune")

    val child = show("2").toOption.get
    assert(child.contains("parent chain (root first): #1[gone]"), s"悬空父按 [gone] 渲染、禁回填: $child")
    assert(!child.contains("将清父"), s"不得编造已清理条目的字段: $child")
    // 父走 [gone] 降级通道（其史 = create/close/prune 仍在该 id 名下，可达）
    val gone = show("1").toOption.get
    assert(gone.startsWith("TaskList #1 [gone] (pruned — no longer in tasks.json)"), gone)
    assert(gone.contains("cleared, unavailable"), gone)
    assert(gone.contains("prune"), gone)
    assert(gone.contains("将清父"), s"prune 事件 detail 带标题（可事后定位被清条目）: $gone")

end TaskListTreeSpec
