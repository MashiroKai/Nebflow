package nebflow.core.tools

import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.service.{MemoryBudget, MemoryStore}

import java.nio.file.Files

/**
 * MemoryEditTool spec（阶段 2c §C.2，设计文档验收点 G.3-②「MemoryEdit 越权路径
 * 拒绝」+ 接口语义逐条断言）。
 *
 * 覆盖：
 *  - 四 action（append/update/remove/replace_section）正反向语义
 *  - target 白名单：仅 user/agent 两个文件；schema 无路径参数（越权路径在
 *    参数面上不存在——H-1①「工具内建路径校验」的结构化形态）；未知 target 拒绝
 *  - 无命中 → 结构化报错（MEMORYEDIT_NO_MATCH / NO_SECTION / PARAM / TARGET）
 *    并列出既有条目前缀（可行动自纠）
 *  - 写路径 = MemoryStore 同一写函数（saveUserMemory/saveAgentMemory）——写后
 *    mtime 缓存失效，loadUserMemory 立即可见（下一 lifecycle 节点生效语义的
 *    数据面基础）
 *
 * dataRoot 经 PathUtil.setDataRoot 重定向到临时目录（DeviceIdentitySpec 先例）。
 * 注意：MemoryStore 的 MtimeCache 在首次使用时把当时的 dataRoot 路径钉进缓存
 * 对象——因此本 suite 全程共用【一个】临时 home（beforeAll 建立），每测重写
 * 文件后显式 invalidate（公开的 invalidateUserCache/invalidateAgentCache）。
 */
class MemoryEditToolSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-2c-memoryedit"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  /** 每测重置两文件（本测要用的内容由测试自己 seed）。 */
  private def seedUser(content: String): Unit =
    os.write.over(home / "User.md", content, createFolders = true)
    MemoryStore.invalidateUserCache()

  private def seedAgent(content: String): Unit =
    os.write.over(home / "agents" / "Nebula" / "memory.md", content, createFolders = true)
    MemoryStore.invalidateAgentCache("Nebula")

  private def call(params: (String, Json)*): Either[ToolError, String] =
    val input = JsonObject.fromIterable(params.map((k, v) => k -> v))
    MemoryEditTool.call(input, ToolContext(projectRoot = home.toString)).unsafeRunSync()

  private def userFile: os.Path  = home / "User.md"
  private def agentFile: os.Path = home / "agents" / "Nebula" / "memory.md"

  // ===== 四 action =====

  test("append without section → file end (user target)"):
    seedUser("# User\n\n- 既有条目甲")
    val res = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 用户偏好深色主题（→pref-dark）".asJson)
    assert(res.isRight)
    val content = os.read(userFile)
    assert(content.contains("- 既有条目甲"))
    assert(content.contains("- 用户偏好深色主题（→pref-dark）"))
    assert(content.trim.endsWith("- 用户偏好深色主题（→pref-dark）"), "appended at file end")
    assert(res.toOption.get.contains("next lifecycle"), "result carries生效提示")

  test("append with section → section end; missing section → NO_SECTION error listing sections"):
    seedAgent("## Routing\n\n- 路由条目一\n\n## Lessons\n\n- 教训条目一")
    val res = call("target" -> "agent".asJson, "action" -> "append".asJson,
      "section" -> "Routing".asJson, "content" -> "- 新路由条目".asJson)
    assert(res.isRight)
    val content = os.read(agentFile)
    val routing = content.slice(content.indexOf("## Routing"), content.indexOf("## Lessons"))
    assert(routing.contains("- 路由条目一") && routing.contains("- 新路由条目"))
    val lessons = content.slice(content.indexOf("## Lessons"), content.length)
    assert(!lessons.contains("- 新路由条目"), "not leaked into next section")

    val missing = call("target" -> "agent".asJson, "action" -> "append".asJson,
      "section" -> "NoSuchSection".asJson, "content" -> "- x".asJson)
    val msg = missing.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_NO_SECTION"))
    assert(msg.contains("Routing") && msg.contains("Lessons"), "error lists existing sections")

  test("update locates FIRST entry containing match and replaces it"):
    seedAgent("- 旧事实 alpha v1\n- 中立条目\n- 另一 alpha v2")
    val res = call("target" -> "agent".asJson, "action" -> "update".asJson,
      "match" -> "alpha".asJson, "content" -> "- alpha 已更新 v3".asJson)
    assert(res.isRight)
    val content = os.read(agentFile)
    assert(content.contains("- alpha 已更新 v3"), "first hit replaced")
    assert(content.contains("- 另一 alpha v2"), "second hit untouched")
    assert(!content.contains("alpha v1"), "old entry gone")
    assert(res.toOption.get.contains("- 旧事实 alpha v1"), "diff summary shows old line")

  test("update scoped by section does not cross sections; no-match error lists entry prefixes"):
    seedUser("## Prefs\n\n- 偏好甲\n\n## Env\n\n- 环境条目含 keyword")
    val scoped = call("target" -> "user".asJson, "action" -> "update".asJson,
      "section" -> "Prefs".asJson, "match" -> "keyword".asJson, "content" -> "- x".asJson)
    val msg = scoped.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_NO_MATCH"), "keyword in Env not visible from Prefs scope")
    assert(msg.contains("偏好甲"), "error lists existing entry prefixes of the section (self-correction)")

    val global = call("target" -> "user".asJson, "action" -> "update".asJson,
      "match" -> "keyword".asJson, "content" -> "- 环境条目已更新".asJson)
    assert(global.isRight, "without section scope the file-wide first hit is found")
    assert(os.read(userFile).contains("- 环境条目已更新"))

  test("remove deletes the located entry only"):
    seedAgent("- 要删的条目 unique-xyz\n- 保留条目")
    val res = call("target" -> "agent".asJson, "action" -> "remove".asJson,
      "match" -> "unique-xyz".asJson)
    assert(res.isRight)
    val content = os.read(agentFile)
    assert(!content.contains("unique-xyz"))
    assert(content.contains("- 保留条目"))

  test("replace_section swaps the whole section body (bulk cleanup path)"):
    seedUser("## Stale\n\n- 旧一\n- 旧二\n- 旧三\n\n## Keep\n\n- 保留我")
    val res = call("target" -> "user".asJson, "action" -> "replace_section".asJson,
      "section" -> "## Stale".asJson, "content" -> "- 新唯一条目".asJson)
    assert(res.isRight)
    val content = os.read(userFile)
    assert(content.contains("## Stale"), "heading preserved")
    assert(content.contains("- 新唯一条目"))
    assert(!content.contains("- 旧一") && !content.contains("- 旧二") && !content.contains("- 旧三"))
    assert(content.contains("- 保留我"), "next section untouched")
    assert(res.toOption.get.contains("3"), "summary counts replaced entries")

  // ===== 白名单（H-1①：工具自身即路径校验层）=====

  test("schema has NO path-like parameter — arbitrary-path writes are structurally impossible"):
    val props = MemoryEditTool.inputSchema("properties").flatMap(_.asObject).get
    val keys = props.keys.toSet
    assertEquals(keys, Set("target", "action", "section", "match", "content"),
      "whitelist surface: exactly the five semantic params, no path/file parameter")

  test("unknown or path-like target is rejected (whitelist-out)"):
    seedUser("- a")
    for bad <- Seq("both", "../../etc/passwd", "User.md", "/Users/dev/.nebflow/auth.json", "") do
      val res = call("target" -> bad.asJson, "action" -> "append".asJson, "content" -> "- x".asJson)
      val msg = res.left.toOption.get.message
      assert(msg.contains("MEMORYEDIT_TARGET"), s"target '$bad' must be rejected")
      assert(msg.contains("user") && msg.contains("agent"), "error names the only legal targets")
    assert(!os.exists(home / "etc"), "no escaped write surface")

  test("agent target writes exactly to ~/.nebflow/agents/Nebula/memory.md (MemoryStore path)"):
    os.remove.all(userFile) // 共享 home：先清掉他测留下的 User.md，验证 agent 调用不波及
    MemoryStore.invalidateUserCache()
    val res = call("target" -> "agent".asJson, "action" -> "append".asJson,
      "content" -> "- 路由经验条目".asJson)
    assert(res.isRight)
    assert(os.exists(agentFile), "file created at the whitelisted agent path")
    assertEquals(agentFile, MemoryStore.agentMemoryPath("Nebula"), "path == MemoryStore.agentMemoryPath(Nebula)")
    assert(os.read(agentFile).contains("- 路由经验条目"))
    assert(!os.exists(userFile), "user file untouched by agent-target call")

  // ===== 参数校验（结构化报错形态）=====

  test("missing required params → MEMORYEDIT_PARAM structured errors"):
    seedUser("- a")
    val noContent = call("target" -> "user".asJson, "action" -> "append".asJson)
    assert(noContent.left.toOption.get.message.contains("MEMORYEDIT_PARAM"))
    val noMatch = call("target" -> "user".asJson, "action" -> "update".asJson,
      "content" -> "- x".asJson)
    assert(noMatch.left.toOption.get.message.contains("MEMORYEDIT_PARAM"))
    val noMatchRemove = call("target" -> "user".asJson, "action" -> "remove".asJson)
    assert(noMatchRemove.left.toOption.get.message.contains("MEMORYEDIT_PARAM"))
    val noSection = call("target" -> "user".asJson, "action" -> "replace_section".asJson,
      "content" -> "- x".asJson)
    assert(noSection.left.toOption.get.message.contains("MEMORYEDIT_PARAM"))
    val unknownAction = call("target" -> "user".asJson, "action" -> "rewrite_all".asJson,
      "content" -> "- x".asJson)
    assert(unknownAction.left.toOption.get.message.contains("MEMORYEDIT_ACTION"))

  // ===== 条目模型与边界 =====

  test("entry model: only '- ' lines are entries — match inside headings/prose is a NO_MATCH"):
    seedUser("## alpha-heading-note\n\n正文段落提到 alpha 但不是条目\n- 真条目")
    val res = call("target" -> "user".asJson, "action" -> "remove".asJson,
      "match" -> "alpha-heading-note".asJson)
    val msg = res.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_NO_MATCH"), "headings/prose are not entries")
    assert(os.read(userFile).contains("## alpha-heading-note"), "file untouched")

  test("append creates the file when missing"):
    os.remove.all(home / "agents" / "Nebula")
    MemoryStore.invalidateAgentCache("Nebula")
    assert(!os.exists(agentFile))
    val res = call("target" -> "agent".asJson, "action" -> "append".asJson,
      "content" -> "- 首条记忆".asJson)
    assert(res.isRight)
    assert(os.read(agentFile).contains("- 首条记忆"))

  test("write path goes through MemoryStore — mtime cache invalidated, load sees the change"):
    seedUser("- 前置条目")
    assert(MemoryStore.loadUserMemory.exists(_.contains("- 前置条目")), "cache primed")
    val res = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 缓存可见性条目".asJson)
    assert(res.isRight)
    assertEquals(MemoryStore.loadUserMemory.map(_.contains("- 缓存可见性条目")), Some(true),
      "same write function → cache invalidated → next lifecycle injection sees it")

  // ===== 条目模型防漂移（2c QC nits 2026-09-04）=====

  test("append/update reject multi-line content (entry-model drift guard)"):
    seedUser("- 既有条目")
    val multi = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 第一行\n第二行不是条目".asJson)
    val msg = multi.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_ENTRY_FORMAT"), "multi-line content rejected at entry")
    assert(msg.contains("replace_section"), "error names the actionable alternative")
    assert(!os.read(userFile).contains("第一行"), "file untouched on rejection")

    val multiUpdate = call("target" -> "user".asJson, "action" -> "update".asJson,
      "match" -> "既有条目".asJson, "content" -> "- 替换行\n漂移行".asJson)
    assert(multiUpdate.left.toOption.get.message.contains("MEMORYEDIT_ENTRY_FORMAT"))
    assert(os.read(userFile).contains("- 既有条目"), "update rejection leaves file untouched")

  test("append/update accept a single '- ' entry; non-entry single line is rejected"):
    seedUser("- 既有条目")
    val okAppend = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "- 单行条目（→id-1 详情在 ~/.nebflow/memory/id-1.md）".asJson)
    assert(okAppend.isRight, "single '- ' entry passes")
    assert(os.read(userFile).contains("- 单行条目"))

    val prose = call("target" -> "user".asJson, "action" -> "append".asJson,
      "content" -> "正文段落，不是条目".asJson)
    assert(prose.left.toOption.get.message.contains("MEMORYEDIT_ENTRY_FORMAT"), "non '- ' single line rejected")

    val okUpdate = call("target" -> "user".asJson, "action" -> "update".asJson,
      "match" -> "既有条目".asJson, "content" -> "- 既有条目已更新".asJson)
    assert(okUpdate.isRight, "single-entry update passes")

  test("replace_section accepts multi-line body by design (guard does not apply)"):
    seedUser("## Bulk\n\n- 旧一\n\n## Tail\n\n- 尾条目")
    val res = call("target" -> "user".asJson, "action" -> "replace_section".asJson,
      "section" -> "Bulk".asJson, "content" -> "- 新一\n- 新二\n正文续行按区段语义允许".asJson)
    assert(res.isRight, "replace_section takes multi-line content")
    val content = os.read(userFile)
    assert(content.contains("- 新一") && content.contains("- 新二") && content.contains("正文续行按区段语义允许"))
    assert(content.contains("## Tail") && content.contains("- 尾条目"), "next section untouched")

  test("validation failure path performs zero filesystem reads (lazy target load)"):
    // User.md 位形设为目录：任何一次实际读都会抛（MtimeCache: os.exists=true → stat →
    // cache miss → os.read(目录) 抛）。lazy load 下失败路径根本不触 load——结构化报错
    // 照常返回；若回归为 eager load，本测会以异常形式爆红。
    os.remove.all(userFile)
    os.makeDir.all(userFile)
    try
      val badAction = call("target" -> "user".asJson, "action" -> "no_such_action".asJson)
      assert(badAction.left.toOption.get.message.contains("MEMORYEDIT_ACTION"),
        "unknown action fails structurally with zero file reads")
      val noParam = call("target" -> "user".asJson, "action" -> "append".asJson)
      assert(noParam.left.toOption.get.message.contains("MEMORYEDIT_PARAM"),
        "missing param fails structurally with zero file reads")
      val badEntry = call("target" -> "user".asJson, "action" -> "append".asJson,
        "content" -> "- a\nb".asJson)
      assert(badEntry.left.toOption.get.message.contains("MEMORYEDIT_ENTRY_FORMAT"),
        "entry-format failure fails structurally with zero file reads")
    finally
      os.remove.all(userFile)

  test("concurrent same-file MemoryEdit calls serialize (per-file mutex, no lost update)"):
    seedUser("- 基线条目\n")
    val thunks: Seq[() => Either[ToolError, String]] = (1 to 8).map { i => () =>
      call("target" -> "user".asJson, "action" -> "append".asJson,
        "content" -> s"- 并发条目 $i".asJson)
    }
    val ec = scala.concurrent.ExecutionContext.global
    val futures = thunks.map(t => scala.concurrent.Future(t())(ec))
    val done = futures.map(f => scala.concurrent.Await.result(f, scala.concurrent.duration.Duration("30s")))
    assert(done.forall(_.isRight), "every concurrent append succeeded")
    val content = os.read(userFile)
    val missing = (1 to 8).count(i => !content.contains(s"- 并发条目 $i"))
    assertEquals(missing, 0, "no lost update: all 8 entries present after concurrent RMW")

  // ===== 预算闸（§6.2-2.2，2026-09-05 批次二机制一）：三态 =====

  test("budget: within budget → append succeeds, no WARN (verdict Within)"):
    seedUser("x" * 1000) // 1KB，远低于 24KB 软线
    val res = call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 预算内条目".asJson)
    assert(res.isRight)
    assert(!res.toOption.get.contains("MEMORYEDIT_BUDGET_WARN"), "预算内不附 WARN")

  test("budget: over 80% soft line but under hard cap → write succeeds WITH WARN (放行不拦截)"):
    seedUser("x" * 41000) // 41,000B > 40,960B 软线，< 51,200B 硬顶
    val res = call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 软警条目".asJson)
    assert(res.isRight, "80% 以上、硬顶以内必须放行")
    val text = res.toOption.get
    assert(text.contains("MEMORYEDIT_BUDGET_WARN"), s"结果必须附软警 WARN: ${text.take(200)}")
    assert(text.contains("consolidation"), "WARN 指向整理行动")
    assert(os.read(userFile).contains("- 软警条目"), "放行 = 实际落盘")

  test("budget: over hard cap → append REJECTED (MEMORYEDIT_BUDGET) with top-3 sections + consolidation guidance; file untouched"):
    seedUser("x" * 51300) // 51,300B > 51,200B 硬顶
    val res = call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 超限条目".asJson)
    val msg = res.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_BUDGET"), s"超硬顶必须结构化拒绝: $msg")
    assert(msg.contains("Largest sections"), "拒绝消息附 top-3 最大节定位")
    assert(msg.contains("Consolidate first") || msg.contains("consolidate"), "拒绝消息附「先整理再写」指引")
    assert(!os.read(userFile).contains("- 超限条目"), "拒绝 = 零写入")

  test("budget: update path is gated too — growing an entry past the hard cap is rejected"):
    seedUser("x" * 50600 + "\n- 小条目") // 50,619B 贴着硬顶下方
    assert(os.read(userFile).getBytes.length < MemoryBudget.UserHardBytes)
    val res = call("target" -> "user".asJson, "action" -> "update".asJson,
      "match" -> "小条目".asJson, "content" -> ("- 替换" + "y" * 4096).asJson)
    val msg = res.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_BUDGET"), "update 推高越过硬顶同样拒绝")

  test("budget: agent target gated at its own 30KB hard cap"):
    seedAgent("x" * 30800) // 30,800B > 30,720B agent 硬顶
    val res = call("target" -> "agent".asJson, "action" -> "append".asJson, "content" -> "- agent 超限".asJson)
    assert(res.left.toOption.get.message.contains("MEMORYEDIT_BUDGET"), "agent 30KB 硬顶独立生效")

  test("budget: replace_section is EXEMPT — the consolidation channel stays open over budget"):
    seedUser("## Bulk\n\n" + "x" * 51300) // 已在硬顶之上（历史上超限的文件必须仍可整理）
    val res = call("target" -> "user".asJson, "action" -> "replace_section".asJson,
      "section" -> "## Bulk".asJson, "content" -> "- 整理后唯一条目".asJson)
    assert(res.isRight, "replace_section 不设闸——超限文件的收缩路径必须畅通")
    assert(os.read(userFile).contains("- 整理后唯一条目"))

  test("budget: sectionSizes/topSections — sizes are per-## section, sorted desc (error-message helper)"):
    val content = "# H\n\n前言三行\n\n## Alpha\n\n" + ("a" * 1000) + "\n\n## Beta\n\n" + ("b" * 2000) + "\n\n## Gamma\n\n" + ("c" * 500)
    val sizes = MemoryBudget.sectionSizes(content)
    assertEquals(sizes.head._1, "Beta", "最大节在前")
    assertEquals(sizes(1)._1, "Alpha")
    assertEquals(sizes(2)._1, "Gamma")
    val top = MemoryBudget.topSections(content)
    assert(top.contains("Beta") && top.contains("Alpha") && top.contains("Gamma"))
    assert(!top.contains("Delta"), "top-3 只列三节")

  // ===== project 目标（project-memory 批 2026-09-05 §2）：注册表路由 + 项目预算 =====

  /** 注册一个项目到临时 home 注册表（ProjectStore 的磁盘契约：projects/<name>/project.json），
    * workspace 独立子目录（与其它测试的 User.md/agents 互不串扰）。返回 workspace 路径。 */
  private def seedProject(name: String): os.Path =
    val ws = home / "ws" / name
    os.makeDir.all(ws / ".nebflow")
    val pdir = home / "projects" / name
    os.makeDir.all(pdir)
    os.write.over(
      pdir / "project.json",
      s"""{"name":"$name","workspace":"${ws.toString}","agentFile":"${(ws / "AGENTS.md").toString}","createdAt":1}""")
    ws

  test("project target resolves via registry and writes <workspace>/.nebflow/memory.md"):
    val ws = seedProject("pmem-basic")
    val memFile = ws / ".nebflow" / "memory.md"
    val res = call("target" -> "project:pmem-basic".asJson, "action" -> "append".asJson,
      "content" -> "- 项目状态：预算闸已落地（2026-09-05）".asJson)
    assert(res.isRight, s"expected ok, got ${res.left.toOption.map(_.message)}")
    assert(os.read(memFile).contains("- 项目状态：预算闸已落地（2026-09-05）"), "written to the project workspace memory file")

  test("project target: append/update/remove/replace_section all usable (same entry discipline)"):
    val ws = seedProject("pmem-actions")
    val memFile = ws / ".nebflow" / "memory.md"
    // 节 append 需既有节（memory-mech 语义：无节 → NO_SECTION）——先 seed 节标题
    os.write.over(memFile, "## 口径\n\n")
    assert(call("target" -> "project:pmem-actions".asJson, "action" -> "append".asJson,
      "section" -> "口径".asJson, "content" -> "- 口径甲".asJson).isRight)
    assert(call("target" -> "project:pmem-actions".asJson, "action" -> "append".asJson,
      "section" -> "口径".asJson, "content" -> "- 口径乙".asJson).isRight)
    assert(call("target" -> "project:pmem-actions".asJson, "action" -> "update".asJson,
      "match" -> "口径甲".asJson, "content" -> "- 口径甲 v2".asJson).isRight)
    assert(call("target" -> "project:pmem-actions".asJson, "action" -> "remove".asJson,
      "match" -> "口径乙".asJson).isRight)
    assert(call("target" -> "project:pmem-actions".asJson, "action" -> "replace_section".asJson,
      "section" -> "口径".asJson, "content" -> "- 口径定稿".asJson).isRight)
    val content = os.read(memFile)
    assert(content.contains("## 口径") && content.contains("- 口径定稿"))
    assert(!content.contains("口径乙") && !content.contains("口径甲 v2"))
    // 单条目纪律在 project 目标同样生效
    val drift = call("target" -> "project:pmem-actions".asJson, "action" -> "append".asJson,
      "content" -> "- 多行\n漂移行".asJson)
    assert(drift.left.toOption.get.message.contains("MEMORYEDIT_ENTRY_FORMAT"))

  test("project target: unknown project → MEMORYEDIT_TARGET with registry pointer"):
    val res = call("target" -> "project:no-such-project".asJson, "action" -> "append".asJson,
      "content" -> "- x".asJson)
    val msg = res.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_TARGET"))
    assert(msg.contains("no-such-project") && msg.contains("registry"), "error names the project + resolution source")

  test("project target: path-traversal / invalid names rejected before any filesystem probe"):
    for bad <- Seq("project:", "project:../escape", "project:a/b", "project:a\\b", "project:.", "project:..") do
      val res = call("target" -> bad.asJson, "action" -> "append".asJson, "content" -> "- x".asJson)
      val msg = res.left.toOption.get.message
      assert(msg.contains("MEMORYEDIT_TARGET"), s"target '$bad' must be rejected")
    assert(!os.exists(home / "escape"), "no escaped write surface")

  test("project budget (10KB/8KB): within → no WARN"):
    val ws = seedProject("pmem-budget-ok")
    os.write.over(ws / ".nebflow" / "memory.md", "x" * 1000)
    val res = call("target" -> "project:pmem-budget-ok".asJson, "action" -> "append".asJson,
      "content" -> "- 项目预算内条目".asJson)
    assert(res.isRight)
    assert(!res.toOption.get.contains("MEMORYEDIT_BUDGET_WARN"), "预算内不附 WARN")

  test("project budget: over 8KB soft line → write succeeds WITH WARN"):
    val ws = seedProject("pmem-budget-warn")
    os.write.over(ws / ".nebflow" / "memory.md", "x" * 8300) // 8,300B > 8,192B 软线，< 10,240B 硬顶
    val res = call("target" -> "project:pmem-budget-warn".asJson, "action" -> "append".asJson,
      "content" -> "- 项目软警条目".asJson)
    assert(res.isRight, "软警区放行")
    assert(res.toOption.get.contains("MEMORYEDIT_BUDGET_WARN"), "结果附软警 WARN")
    assert(os.read(ws / ".nebflow" / "memory.md").contains("- 项目软警条目"), "放行 = 实际落盘")

  test("project budget: over 10KB hard cap → REJECTED, file untouched (constants independent of global)"):
    val ws = seedProject("pmem-budget-hard")
    os.write.over(ws / ".nebflow" / "memory.md", "x" * 10300) // 10,300B > 10,240B 硬顶
    val res = call("target" -> "project:pmem-budget-hard".asJson, "action" -> "append".asJson,
      "content" -> "- 项目超限条目".asJson)
    val msg = res.left.toOption.get.message
    assert(msg.contains("MEMORYEDIT_BUDGET"), "超硬顶结构化拒绝")
    assert(msg.contains((ws / ".nebflow" / "memory.md").toString), "拒绝消息带项目记忆文件真实路径")
    assert(msg.contains("10240"), "按项目硬顶 10,240B 计算百分比（非全局 50KB/30KB）")
    assert(!os.read(ws / ".nebflow" / "memory.md").contains("- 项目超限条目"), "拒绝 = 零写入")

  test("project budget: replace_section stays open over budget (consolidation channel)"):
    val ws = seedProject("pmem-budget-fix")
    os.write.over(ws / ".nebflow" / "memory.md", "## Bulk\n\n" + "x" * 10300)
    val res = call("target" -> "project:pmem-budget-fix".asJson, "action" -> "replace_section".asJson,
      "section" -> "Bulk".asJson, "content" -> "- 整理后唯一条目".asJson)
    assert(res.isRight, "超限项目的收缩通道必须畅通")
    assert(os.read(ws / ".nebflow" / "memory.md").contains("- 整理后唯一条目"))

  // ---------------------------------------------------------------
  // 快照先行（dream-agent 批 2026-09-05）：任何写动作落盘前先有备份
  // ---------------------------------------------------------------

  private def latestBackupOf(file: os.Path): os.Path =
    val root = home / "memory-backups"
    val key = if file.last == "User.md" then "User.md" else "agents__Nebula__memory.md"
    val mine = os.list(root).filter(os.isDir(_)).filter(d => os.exists(d / key)).sortBy(_.last)
    mine.last / key

  test("snapshot: update 备份先于写——备份内容 == 写前真身，目标 == 写后内容"):
    seedAgent("- 旧条目（写前真身）\n")
    val res = call("target" -> "agent".asJson, "action" -> "update".asJson,
      "match" -> "旧条目".asJson, "content" -> "- 新条目（写后）".asJson)
    assert(res.isRight, s"update should succeed: $res")
    val backup = latestBackupOf(agentFile)
    assert(os.read(backup) == "- 旧条目（写前真身）\n", "backup must hold PRE-write bytes")
    assert(os.read(agentFile) == "- 新条目（写后）\n", "target holds post-write bytes")
    // 同一文件四动作各自快照（remove 也要有份）
    call("target" -> "agent".asJson, "action" -> "remove".asJson, "match" -> "新条目".asJson)
    val backup2 = latestBackupOf(agentFile)
    assert(os.read(backup2).contains("- 新条目（写后）"), "remove 的备份 = remove 前真身")

  test("snapshot: replace_section / append 同样先过快照"):
    seedUser("## Bulk\n\n- 旧 A\n- 旧 B\n")
    call("target" -> "user".asJson, "action" -> "replace_section".asJson,
      "section" -> "## Bulk".asJson, "content" -> "- 整理后条目".asJson)
    assert(os.exists(latestBackupOf(userFile)), "replace_section 留有备份")
    val before = os.read(userFile)
    call("target" -> "user".asJson, "action" -> "append".asJson, "content" -> "- 追加条目".asJson)
    assert(os.read(latestBackupOf(userFile)) == before, "append 的备份 = append 前真身")

  test("snapshot: fail-closed — 快照失败 → 拒写 + 目标文件原样（MEMORYEDIT_SNAPSHOT）"):
    seedUser("- 不可丢条目\n")
    // 把快照根位置变成【文件】→ makeDir.all 必败 → 闸门触发
    os.makeDir.all(home / "memory-backups")
    os.write.over(home / "memory-backups" / "blocker", "x") // 根内放文件不碍事；改用整体占位：
    os.remove.all(home / "memory-backups")
    os.write.over(home / "memory-backups", "not a directory", createFolders = true)
    val res = call("target" -> "user".asJson, "action" -> "remove".asJson, "match" -> "不可丢条目".asJson)
    assert(res.left.toOption.get.message.contains("MEMORYEDIT_SNAPSHOT"), "结构化快照失败码")
    assert(os.read(userFile).contains("- 不可丢条目"), "fail-closed：目标零写入")
end MemoryEditToolSpec
