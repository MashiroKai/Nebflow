package nebflow.core.tools

import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.service.MemoryStore

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
end MemoryEditToolSpec
