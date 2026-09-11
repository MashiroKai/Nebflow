package nebflow.core.tools

import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.AgentDef
import nebflow.core.PathUtil
import nebflow.service.{MemoryBudget, MemoryStore}

import java.nio.file.Files

/**
 * MemoryEditTool spec（**队列记账语义**，2026-09-12 记忆改造批 / spec §5 R2 O-A）。
 *
 * 本文件的前身断言的是直写语义（四动作落到记忆文件 + 快照 + 预算闸）；那套语义已被
 * `MemoryEdit` 保名换语义推翻 ⇒ 旧断言**整体作废**，此处按新语义重写（不是「改测试
 * 过门」：被断言的行为本身是本批的交付对象）。
 *
 * 覆盖：
 *  - 四 action 一律**记账**：写队列 `note`（字段齐备）、记忆文件**零字节变化**、
 *    零快照目录、零预算闸（闸随写入权移交应用侧）
 *  - target 白名单与合法性（user/agent/project:<name> 同规；未知项目拒收；**不回落**）
 *  - 参数 / 动作名 / 条目格式结构化报错（MEMORYEDIT_PARAM / ACTION / ENTRY_FORMAT）
 *  - dream 受限准入原样保留（append 拒 + 零入队）
 *  - 返回值文本：`queued q-… (applied at next compaction)`，**不回显「已写入」**
 *  - 幂等（同 hash 在 pending ⇒ 返回既有 q-id，不新增行）
 *  - 变更史（history:queue 与 note 以 ref 对账）
 *
 * dataRoot 经 PathUtil.setDataRoot 重定向到临时目录（DeviceIdentitySpec 先例）。
 */
class MemoryEditToolSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memq-memoryedit"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def seedUser(content: String): Unit =
    os.write.over(home / "User.md", content, createFolders = true)
    MemoryStore.invalidateUserCache()

  private def seedAgent(content: String): Unit =
    os.write.over(home / "agents" / "Nebula" / "memory.md", content, createFolders = true)
    MemoryStore.invalidateAgentCache("Nebula")

  private def call(params: (String, Json)*): Either[ToolError, String] =
    val input = JsonObject.fromIterable(params.map((k, v) => k -> v))
    MemoryEditTool.call(input, ToolContext(projectRoot = home.toString)).unsafeRunSync()

  private def callAs(identity: String, params: (String, Json)*): Either[ToolError, String] =
    val input = JsonObject.fromIterable(params.map((k, v) => k -> v))
    MemoryEditTool.call(input, ToolContext(
      projectRoot = home.toString,
      agentDef = Some(AgentDef(name = identity, description = "")))).unsafeRunSync()

  private def userFile: os.Path  = home / "User.md"
  private def agentFile: os.Path = home / "agents" / "Nebula" / "memory.md"

  /** 队列清空（每测独立起点；两代文件一并清）。 */
  private def resetQueue(): Unit =
    os.remove.all(home / "memory")

  private def notes: Vector[MemoryQueue.Note] = MemoryQueue.readState().notes
  private def outcomes: Vector[MemoryQueue.Outcome] = MemoryQueue.readState().outcomes

  // ===== 记账 =====

  test("append → 一条 note 入队；记忆文件零字节变化、零快照目录"):
    resetQueue()
    seedUser("# User\n\n- 既有条目甲\n")
    val before = os.read(userFile)
    val res = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 用户偏好深色主题（→pref-dark）".asJson)
    assert(res.isRight, s"expect ok: ${res.left.toOption.map(_.message)}")
    assertEquals(os.read(userFile), before, "直写通道已关闭：记忆文件字节不变")
    assert(!os.exists(home / "memory-backups"), "记账不做写前快照（无落盘对象）")
    assertEquals(notes.size, 1)
    val n = notes.head
    assertEquals(n.target, "user")
    assertEquals(n.action, "append")
    assertEquals(n.content, Some("- 用户偏好深色主题（→pref-dark）"))
    assert(n.atMs > 0L, "atMs 必带（T2/T3 生命周期与陈旧度判定需要）")
    assert(n.id.startsWith(s"q-${n.atMs}-"), s"id 形态 q-<atMs>-<n>，得 ${n.id}")

  test("append 带 section / update / remove / replace_section 各自的字段落进 note"):
    resetQueue()
    assert(call("target" -> "agent".asJson, "action" -> "append".asJson,
      "section" -> "Routing".asJson, "content" -> "- 新路由条目".asJson).isRight)
    assert(call("target" -> "agent".asJson, "action" -> "update".asJson,
      "match" -> "alpha".asJson, "content" -> "- alpha 已更新".asJson).isRight)
    assert(call("target" -> "agent".asJson, "action" -> "remove".asJson,
      "match" -> "stale".asJson).isRight)
    assert(call("target" -> "agent".asJson, "action" -> "replace_section".asJson,
      "section" -> "## Bulk".asJson, "content" -> "- 新一\n- 新二".asJson).isRight)
    val ns = notes
    assertEquals(ns.size, 4)
    assertEquals(ns(0).section, Some("Routing"))
    assertEquals(ns(1).matchText, Some("alpha"))
    assertEquals(ns(2).action, "remove")
    assertEquals(ns(2).matchText, Some("stale"))
    assertEquals(ns(3).action, "replace_section")
    assertEquals(ns(3).section, Some("Bulk"), "section 归一（'## ' 前缀可选）")
    assertEquals(ns(3).content, Some("- 新一\n- 新二"), "replace_section 允许多行内容")
    assert(!os.exists(agentFile), "记账不创建记忆文件")

  test("返回值：queued q-… (applied at next compaction)，且不回显「已写入」"):
    resetQueue()
    val res = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 条目".asJson).toOption.get
    assert(res.contains("queued q-"), s"必须明示 queued q-id: $res")
    assert(res.contains("(applied at next compaction)"), "必须明示应用时机")
    assert(res.contains("NOT written yet"), "必须明示尚未落盘")
    assert(!res.contains("MemoryEdit ok:"), "不得沿用旧「已写入」回显形态")
    assert(!res.toLowerCase.contains("written to memory"), "不得回显已写入记忆")

  test("schema 无路径参数（白名单面 = 五个语义参数）"):
    val props = MemoryEditTool.inputSchema("properties").flatMap(_.asObject).get
    assertEquals(props.keys.toSet, Set("target", "action", "section", "match", "content"),
      "whitelist surface: exactly the five semantic params, no path/file parameter")

  // ===== target 值域 =====

  test("target 缺失/未知/path-like 一律拒收（MEMORYEDIT_TARGET），且**不回落 user**"):
    resetQueue()
    for bad <- Seq("", "both", "../../etc/passwd", "User.md", "/Users/x/.nebflow/auth.json") do
      val res = call("target" -> bad.asJson, "action" -> "append".asJson, "content" -> "- x".asJson)
      val msg = res.left.toOption.get.message
      assert(msg.contains("MEMORYEDIT_TARGET"), s"target '$bad' must be rejected: $msg")
      assert(msg.contains("user") && msg.contains("agent"), "error names the legal targets")
    assert(notes.isEmpty, "被拒请求零入队（不静默落到 user 层）")
    assert(!os.exists(home / "etc"), "no escaped write surface")
    val missing = call("action" -> "append".asJson, "content" -> "- x".asJson)
    val msg = missing.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_TARGET") && msg.contains("never defaulted"), "缺省 target 明确拒收")

  test("project target 经注册表解析 → note.target = project:<name>；未知项目拒收"):
    resetQueue()
    val ws = home / "ws" / "pmem-basic"
    os.makeDir.all(ws / ".nebflow")
    os.makeDir.all(home / "projects" / "pmem-basic")
    os.write.over(
      home / "projects" / "pmem-basic" / "project.json",
      s"""{"name":"pmem-basic","workspace":"${ws.toString}","agentFile":"${(ws / "AGENTS.md").toString}","createdAt":1}""")
    val res = call("target" -> "project:pmem-basic".asJson, "action" -> "append".asJson,
      "content" -> "- 项目状态：队列化已落地".asJson)
    assert(res.isRight, s"expect ok: ${res.left.toOption.map(_.message)}")
    assertEquals(notes.head.target, "project:pmem-basic")
    assert(!os.exists(ws / ".nebflow" / "memory.md"), "项目记忆文件同样零写入")

    val unknown = call("target" -> "project:no-such-project".asJson, "action" -> "append".asJson,
      "content" -> "- x".asJson)
    val msg = unknown.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_TARGET") && msg.contains("registry"), "未知项目拒收并指向注册表")

    for bad <- Seq("project:", "project:../escape", "project:a/b", "project:a\\b", "project:.", "project:..") do
      val r = call("target" -> bad.asJson, "action" -> "append".asJson, "content" -> "- x".asJson)
      assert(r.left.toOption.get.message.contains("MEMORYEDIT_TARGET"), s"target '$bad' must be rejected")
    assert(!os.exists(home / "escape"), "no escaped write surface")

  // ===== 参数 / 动作 / 条目格式 =====

  test("必填参数缺失 → MEMORYEDIT_PARAM（零入队）"):
    resetQueue()
    val cases = List(
      List("target" -> "user".asJson, "action" -> "append".asJson),
      List("target" -> "user".asJson, "action" -> "update".asJson, "content" -> "- x".asJson),
      List("target" -> "user".asJson, "action" -> "remove".asJson),
      List("target" -> "user".asJson, "action" -> "replace_section".asJson, "content" -> "- x".asJson)
    )
    cases.foreach { params =>
      val r = call(params*)
      assert(r.left.toOption.get.message.contains("MEMORYEDIT_PARAM"), s"params=$params")
    }
    assert(notes.isEmpty, "参数校验失败零入队")

  test("未知动作 → MEMORYEDIT_ACTION；不写任何行"):
    resetQueue()
    val r = call("target" -> "user".asJson, "action" -> "rewrite_all".asJson, "content" -> "- x".asJson)
    assert(r.left.toOption.get.message.contains("MEMORYEDIT_ACTION"))
    assert(notes.isEmpty)

  test("条目格式闸：append/update 多行或非 '- ' 开头 → MEMORYEDIT_ENTRY_FORMAT（零入队）"):
    resetQueue()
    val multi = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 第一行\n第二行不是条目".asJson)
    val msg = multi.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_ENTRY_FORMAT") && msg.contains("replace_section"), "给出可行动出路")
    val prose = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "正文段落，不是条目".asJson)
    assert(prose.left.toOption.get.message.contains("MEMORYEDIT_ENTRY_FORMAT"))
    val multiUpdate = call("target" -> "user".asJson, "action" -> "update".asJson,
      "match" -> "x".asJson, "content" -> "- 替换行\n漂移行".asJson)
    assert(multiUpdate.left.toOption.get.message.contains("MEMORYEDIT_ENTRY_FORMAT"))
    assert(notes.isEmpty, "格式闸在入队前拒收")
    // replace_section 按设计允许多行（闸不适用）
    assert(call("target" -> "user".asJson, "action" -> "replace_section".asJson,
      "section" -> "Bulk".asJson, "content" -> "- 新一\n续行".asJson).isRight)

  // ===== dream 受限准入（原样保留）=====

  test("dream append → DREAM_APPEND_DENIED，零入队；dream remove → 入队"):
    resetQueue()
    val denied = callAs("dream", "target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- dream 试图新增的记忆".asJson)
    val msg = denied.left.toOption.get.message
    assert(msg.contains("DREAM_APPEND_DENIED") && msg.contains("dream 禁写新记忆"), s"结构性错误码: $msg")
    assert(msg.contains("update") && msg.contains("replace_section"), "给出可行动出路（修订动作）")
    assert(notes.isEmpty, "被拒零入队")
    assert(callAs("dream", "target" -> "agent".asJson, "action" -> "remove".asJson,
      "match" -> "stale-xyz".asJson).isRight, "dream 修订动作放行")
    assertEquals(notes.size, 1)

  test("Nebula 全四动作照常（agentDef=Nebula 显式走一遍）"):
    resetQueue()
    assert(callAs("Nebula", "target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- Nebula 追加条目".asJson).isRight)
    assert(callAs("Nebula", "target" -> "user".asJson, "action" -> "update".asJson,
      "match" -> "旧条目".asJson, "content" -> "- 旧条目已更新".asJson).isRight)
    assert(callAs("Nebula", "target" -> "user".asJson, "action" -> "replace_section".asJson,
      "section" -> "Sec".asJson, "content" -> "- 区段定稿".asJson).isRight)
    assert(callAs("Nebula", "target" -> "user".asJson, "action" -> "remove".asJson,
      "match" -> "临时条目".asJson).isRight)
    assertEquals(notes.size, 4)

  // ===== 幂等 =====

  test("幂等：同 hash 在 pending ⇒ 返回既有 q-id，不新增行；hash 变化 ⇒ 新行"):
    resetQueue()
    val a = call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 同一条".asJson).toOption.get
    val b = call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 同一条".asJson).toOption.get
    assert(a.contains("queued q-") && b.contains("queued q-"))
    assertEquals(notes.size, 1, "同 hash 不重复入队")
    assert(b.contains("deduped"), "结果明示去重")
    val idA = notes.head.id
    assert(a.contains(idA) && b.contains(idA), "两次返回同一 q-id")
    assert(call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 另一条".asJson).isRight)
    assertEquals(notes.size, 2, "内容不同 ⇒ 新行")
    assert(call("target" -> "agent".asJson, "action" -> "append".asJson, "content" -> "- 同一条".asJson).isRight)
    assertEquals(notes.size, 3, "target 参与 hash（跨层不复用）")

  test("幂等只在 pending 内成立：写 outcome 后可再次入队"):
    resetQueue()
    call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 条目 X".asJson)
    val id = notes.head.id
    assert(MemoryQueue.recordOutcome(id, MemoryQueue.ResultApplied, "memory-consolidator", "done").isRight)
    assertEquals(MemoryQueue.pendingCount(), 0)
    assert(call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 条目 X".asJson).isRight)
    assertEquals(notes.size, 2, "已消费条目不再参与去重（新意图照收）")

  // ===== 变更史（IMPL-1 必含项）=====

  test("变更史：每次入队落一行 history:queue，与 note 以 ref 可对账"):
    resetQueue()
    val res = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 变更史条目".asJson).toOption.get
    val id = notes.head.id
    val evs = MemoryHistory.ofKind(MemoryHistory.KindQueue)
    assertEquals(evs.size, 1)
    assertEquals(evs.head.ref, Some(id), "history.ref == note.id")
    assertEquals(evs.head.content, Some("- 变更史条目"), "content 零改写逐字入档")
    assertEquals(evs.head.actor, MemoryHistory.ActorUnknown, "无身份 → unknown（不编造）")
    assert(res.contains("queued q-"))
    // 回写 outcome 后 consume 行落档，三者对账为空
    assert(MemoryQueue.recordOutcome(id, MemoryQueue.ResultApplied, "memory-consolidator", "applied").isRight)
    val st = MemoryQueue.readState()
    assertEquals(
      MemoryHistory.discrepancies(st.notes.map(_.id).toList, st.outcomeRefs),
      Nil,
      "history ↔ note/outcome 三者对账一致")
