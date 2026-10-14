package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * TaskList **零迁移**兼容 spec —— 升级批 R6。
 *
 * 硬约束：升级新增 `links` / `parentId` 两字段与顶层 `nextId` 水位，**旧 9 键
 * tasks.json 必须原样解码**（缺字段回默认），且**未知键容忍**（前向兼容：将来
 * 再加字段时旧二进制不炸）。零迁移 = 不写迁移脚本、不改存量文件字节。
 *
 * 覆盖：
 *  - 旧 9 键 fixture → 解码成功、缺 `links`/`parentId` 回默认、`nextId` 回 0；
 *  - 未知键（顶层 + 条目层 + 嵌套对象）容忍；未知 `status` 值原样保留（不规范化）；
 *  - 写回后旧字段逐字保留 + 新字段落盘（`version` 不变）；
 *  - **真实副本只读校验**（`NEBFLOW_TASKLIST_LEGACY` 门控）：对上游给的存量
 *    tasks.json **副本**做只读解码 + 指纹前后一致断言，证明读路径零写副作用。
 *    env 未设时 `assume` 跳过（不在活任务库上实验——补充 B 验收 (c)）。
 *
 * 隔离：`PathUtil.setDataRoot(temp)`；真实 `~/.nebflow/` 数据面零触碰
 * （只读 sha 复算是交付层的独立步骤，不在本 spec 内）。
 */
class TaskListLegacyCompatSpec extends FunSuite:

  var home: os.Path = os.Path(Files.createTempDirectory("nb-tasklist-legacy"))
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

  private def ctx: ToolContext = ToolContext(projectRoot = "/tmp")

  private def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  private def call(fields: (String, Json)*): Either[ToolError, String] =
    TaskListTool.call(obj(fields*), ctx).unsafeRunSync()

  private def str(s: String): Json = Json.fromString(s)

  /** 升级前（2026-09-06 首发批）的**逐字**条目形态：恰 9 键，无 links / parentId。 */
  // 🔴 时间炸弹拆除（2026-10-14 redfix 批，报告 #20）：夹具日期原为固定
  // 「2026-09-08」——升级批给 create 加了 **30 天 done-TTL prune**
  // （`TaskListTool.DoneTtlDays=30`，create 内 pruneDone 先于 id 分配）⇒ 乙条
  // （done，closedAt 固定）在 2026-10-08 起必被剪 ⇒ `nextNumId = max(1,0)+1 = 2`，
  // 落盘 List("1","2") 与旧断言 List("1","2","3") 永恒相违（**机制本体工作正常**，
  // 红纯由固定日期撞滚动窗——本批复算逐字吻合；🔴 勿当真红修机制）。
  // 修法 = 夹具日期改**相对 now**：甲条（open）= now-1d；done 条**双例**——
  // 乙（now-1d，剪窗内，存活）与丙（now-40d，窗过，**必被剪**）⇒ 同一 fixture
  // 同时钉住 prune 行为与 max+1 续接，且永不随时间推移再碎。
  private val nowMinus1d: String = java.time.Instant.now().minus(java.time.Duration.ofDays(1)).toString
  private val nowMinus40d: String = java.time.Instant.now().minus(java.time.Duration.ofDays(40)).toString

  private def legacyEntry: String =
    s"""{"id":"1","title":"旧条目甲","status":"open","project":"projOld","note":"旧备注正文",
       |"blocks":[],"createdAt":"$nowMinus1d","updatedAt":"$nowMinus1d","closedAt":null}""".stripMargin
      .replace("\n", "")

  private def legacyEntry2: String =
    s"""{"id":"2","title":"旧条目乙","status":"done","project":null,"note":null,
      |"blocks":["1"],"createdAt":"$nowMinus1d","updatedAt":"$nowMinus1d",
      |"closedAt":"$nowMinus1d"}""".stripMargin.replace("\n", "")

  /** 第三条：done 且 closedAt 在剪窗之外（now-40d）——create 时必被 prune（时间炸弹钉）。 */
  private def legacyEntry3: String =
    s"""{"id":"3","title":"旧条目丙-应被剪","status":"done","project":null,"note":null,
      |"blocks":[],"createdAt":"$nowMinus40d","updatedAt":"$nowMinus40d",
      |"closedAt":"$nowMinus40d"}""".stripMargin.replace("\n", "")

  /** 旧顶层：`version` + `tasks`，**无 `nextId`**。 */
  private def legacyDoc: String = s"""{"version":1,"tasks":[$legacyEntry,$legacyEntry2,$legacyEntry3]}"""

  private def sha(p: os.Path): String =
    os.proc("shasum", "-a", "256", p.toString).call().out.text().trim.split(" ").head

  // ===== ① 旧 9 键零迁移解码 =====

  test("旧 9 键 tasks.json 原样解码：缺 links/parentId 回默认、顶层缺 nextId 回 0"):
    reset()
    os.write(file, legacyDoc, createFolders = true)
    val d = decode[TaskListData](os.read(file)).toOption
    assert(d.isDefined, s"旧形态必须可解码（零迁移）: ${decode[TaskListData](os.read(file))}")
    val data = d.get
    assertEquals(data.version, 1, "version 不变")
    assertEquals(data.tasks.map(_.id), List("1", "2", "3"), "id 原样")
    assertEquals(data.tasks.map(_.title), List("旧条目甲", "旧条目乙", "旧条目丙-应被剪"), "title 原样")
    assertEquals(data.tasks.map(_.status), List("open", "done", "done"), "status 原样")
    assertEquals(data.tasks.head.note, Some("旧备注正文"), "note 原样")
    assertEquals(data.tasks(1).blocks, List("1"), "blocks 原样")
    assertEquals(data.tasks.head.createdAt, Some(nowMinus1d), "createdAt 原样")
    assertEquals(data.tasks(1).closedAt, Some(nowMinus1d), "closedAt 原样")

    // 新字段默认值（零迁移的核心断言）
    assert(data.tasks.forall(_.links == Nil), "缺 links ⇒ 回默认 Nil")
    assert(data.tasks.forall(_.parentId == None), "缺 parentId ⇒ 回默认 None")
    assertEquals(data.nextId, 0, "缺 nextId ⇒ 回默认 0（读入侧水位起点）")

  test("旧库读视图与工具读动作：零迁移下 list/show 正常（不写盘、不改存量字节）"):
    reset()
    os.write(file, legacyDoc, createFolders = true)
    val before = sha(file)
    val listed = call("action" -> str("list"))
    assert(listed.isRight && listed.toOption.get.contains("#1 [open] 旧条目甲"), listed)
    assert(listed.toOption.get.contains("3 entries (1 open)"), listed.toOption.get)
    val shown = call("action" -> str("show"), "id" -> str("1"))
    assert(shown.isRight, shown)
    assert(shown.toOption.get.contains("note (5 chars):\n旧备注正文"), shown.toOption.get)
    assertEquals(sha(file), before, "读动作零写盘（存量字节逐字不变）")
    assert(!os.exists(home / "tasks-history.jsonl"), "读动作不得建史文件")

  test("旧库首次写入即自愈：create 从 max(存量 max, 0)+1 续接并落 nextId 水位（30d done-TTL prune 同钉）"):
    reset()
    os.write(file, legacyDoc, createFolders = true)
    assert(call("action" -> str("create"), "title" -> str("新条目")).isRight)
    val after = decode[TaskListData](os.read(file)).toOption.get
    // prune 钉：丙条（closedAt = now-40d，窗过）必被剪；乙条（now-1d，窗内）存活。
    assert(
      !after.tasks.exists(_.title == "旧条目丙-应被剪"),
      s"closedAt 超 30 天 TTL 的 done 条必须被 create 前置 prune 剪掉: ${after.tasks.map(t => t.id -> t.title)}"
    )
    assertEquals(after.tasks.map(_.id).sorted, List("1", "2", "3"), "id 续接存量 max+1（剪后存量 max=2 ⇒ 新条目=3）")
    assertEquals(after.nextId, 3, "水位落盘（其后 id 不回收）")
    // 旧条目字段逐字未被动
    assertEquals(after.tasks.find(_.id == "1").get.title, "旧条目甲")
    assertEquals(after.tasks.find(_.id == "1").get.createdAt, Some(nowMinus1d))
    assertEquals(after.tasks.find(_.id == "2").get.closedAt, Some(nowMinus1d))

  // ===== ② 未知键容忍（前向兼容）=====

  test("未知键容忍：顶层 / 条目层 / 嵌套对象均可带未知键而不炸（值原样丢弃，已知字段照常读）"):
    reset()
    val doc =
      """{"version":1,"nextId":5,"futureTop":{"a":[1,2,3]},"another":"x",
        |"tasks":[{"id":"7","title":"未来条目","status":"in_progress","project":null,"note":null,
        |"blocks":[],"createdAt":null,"updatedAt":null,"closedAt":null,
        |"links":["p.md"],"parentId":null,"futureField":42,
        |"futureNested":{"deep":{"deeper":true}},"labels":["x","y"]}]}""".stripMargin.replace("\n", "")
    os.write(file, doc, createFolders = true)
    val d = decode[TaskListData](os.read(file))
    assert(d.isRight, s"未知键必须容忍: ${d.swap.toOption}")
    val data = d.toOption.get
    assertEquals(data.nextId, 5, "已知键照常读")
    assertEquals(data.tasks.head.id, "7")
    assertEquals(data.tasks.head.links, List("p.md"))
    assertEquals(data.tasks.head.parentId, None)
    assert(call("action" -> str("list")).toOption.get.contains("#7 [in_progress] 未来条目"), "工具侧读视图不受未知键影响")

  test("未知 status 值原样保留（读路径不做状态规范化）；未知 status 迁移按既有闸拒绝"):
    reset()
    val doc =
      """{"version":1,"tasks":[{"id":"1","title":"异形状态","status":"weird_state",
        |"blocks":[],"createdAt":null,"updatedAt":null,"closedAt":null}]}""".stripMargin.replace("\n", "")
    os.write(file, doc, createFolders = true)
    assertEquals(decode[TaskListData](os.read(file)).toOption.get.tasks.head.status, "weird_state", "读路径零规范化（存量零影响）")
    assert(call("action" -> str("list")).isRight, "list 不因异形状态崩")
    val u = call("action" -> str("update"), "id" -> str("1"), "status" -> str("open"))
    assert(u.isLeft && u.swap.toOption.get.message.contains("TASKLIST_STATUS"), u)

  // ===== ③ 真实副本只读校验（env 门控）=====

  test("真实存量副本只读校验（NEBFLOW_TASKLIST_LEGACY 门控）：可解码 + 指纹前后一致"):
    val legacyPath = sys.env.get("NEBFLOW_TASKLIST_LEGACY").map(_.trim).filter(_.nonEmpty)
    assume(legacyPath.isDefined, "NEBFLOW_TASKLIST_LEGACY 未设 —— 跳过（不在活任务库上实验；设为本机 tasks.json 的副本路径即可启用）")
    val p = os.Path(legacyPath.get)
    assume(os.exists(p), s"$p 不存在")
    val before = sha(p)
    val histSibling = p / os.up / "tasks-history.jsonl"
    val histBefore = os.exists(histSibling)
    val decoded = decode[TaskListData](os.read(p))
    assert(decoded.isRight, s"真实存量库必须可解码（零迁移）: ${decoded.swap.toOption}")
    val data = decoded.toOption.get
    assert(data.tasks.nonEmpty, "真实副本应含条目")
    assert(data.tasks.forall(t => t.id.nonEmpty && t.title.nonEmpty), "新旧条目共存均可读（id/title 非空）")
    println(
      s"[tbu-list][legacy] path=$p entries=${data.tasks.size} " +
        s"open=${data.tasks.count(_.status == "open")} " +
        s"done=${data.tasks.count(_.status == "done")} " +
        s"in_progress=${data.tasks.count(_.status == "in_progress")} " +
        s"bytes=${os.size(p)} nextId=${data.nextId} " +
        s"linksPresent=${data.tasks.count(_.links.nonEmpty)} parentPresent=${data.tasks.count(_.parentId.isDefined)}"
    )
    assertEquals(sha(p), before, "只读校验：副本指纹前后一致（读路径零写副作用）")
    assertEquals(os.exists(histSibling), histBefore, s"只读校验：不得在副本目录新建史文件（$histSibling）")

end TaskListLegacyCompatSpec
