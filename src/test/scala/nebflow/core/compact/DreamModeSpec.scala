package nebflow.core.compact

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.tools.MemoryQueue
import nebflow.service.MemoryStore

import java.nio.file.Files

/**
 * DreamMode 停用（落法 ii「连机制一起停」）后的新语义 spec。
 *
 * 原文件覆盖的是**合并核**（`t3Evolve` / `mergeFactsIntoSection` / `updateMemory` /
 * `promotedTexts` / sidecar 时钟）——那些 API 已随 DreamMode 机制整体删除 ⇒ 本文件
 * **按新语义改写**（不删文件、不放宽断言、不 skip），钉四条现在成立的事实：
 *
 *   ① 引擎不再拥有具名节：生产者（`NebulaMemoryHook.enqueueFacts`）入队的条目
 *      `section` 恒 `None`；
 *   ② 该条在**不含** `## Dream Extract` 的文件上照旧可落（文件尾追加）——「缺具名节
 *      ⇒ 永不能落」这一旧约束不再适用于生产者；
 *   ③ 反向钉：`## Dream Extract` **不再是可落节**——以它为落点的条目在无该节的文件上
 *      判 `would-retry`（零落笔 ⇒ 引擎**不创建**该节；即「系统默认创建文件
 *      时也不要有这个 section」的机械面）；
 *   ④ 仍在用（生产者/队列识别子）的两件纯函数 `parseFact` / `entryHash` 语义直测。
 *
 * dataRoot 经 `PathUtil.setDataRoot` 钉临时目录（NebulaMemoryHookRouteSpec / MemoryTrackSpec
 * 先例）⇒ 现场真实记忆文件与 `queue.jsonl` **零接触**。
 */
class DreamModeSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-dreammode-retired"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def reset(): Unit =
    os.remove.all(home / "memory")
    os.remove.all(home / "User.md")
    os.remove.all(home / "agents")
    MemoryStore.invalidateUserCache()
    MemoryStore.invalidateAgentCache("Nebula")

  private def noteOf(
      id: String,
      section: Option[String],
      content: Option[String]
  ): MemoryQueue.Note =
    MemoryQueue.Note(
      id, 1L, "2026-09-12T00:00:00Z", "user", "append", section, None, content,
      Some("s"), MemoryQueue.TriggerDream)

  private def stateOf(notes: Vector[MemoryQueue.Note]): MemoryQueue.State =
    MemoryQueue.State(notes, Vector.empty, Set.empty, 0, 0)

  private def targetFile(content: String): Map[String, MemoryQueue.TargetFile] =
    Map("user" -> MemoryQueue.TargetFile("/tmp/x/User.md", content))

  // ===== ① 仍在用的纯函数：fact 行解析 =====

  test("parseFact：`FACT n: [CATEGORY] text` 解析（类别大写化；空文本/非 FACT 行 ⇒ None）"):
    assertEquals(DreamMode.parseFact("FACT 1: [PATTERN] 先写 spec"), Some(("PATTERN", "先写 spec")))
    assertEquals(DreamMode.parseFact("  FACT 12 : [user_preference] 深色主题  "),
      Some(("USER_PREFERENCE", "深色主题")))
    assertEquals(DreamMode.parseFact("FACT 3: [DECISION]"), None, "空文本不解析")
    assertEquals(DreamMode.parseFact("不是 FACT 格式"), None)

  // ===== ② 仍在用的纯函数：条目识别子 =====

  test("entryHash：trim 归一化后取 sha256（同文本同哈希；异文本异哈希；跨调用稳定）"):
    assertEquals(DreamMode.entryHash("  abc  "), DreamMode.entryHash("abc"))
    assert(DreamMode.entryHash("abc") != DreamMode.entryHash("abd"))
    assertEquals(DreamMode.entryHash("abc").length, 64, "sha256 十六进制全长")

  // ===== ③ 生产者入队：无落点节（新语义）=====

  test("生产者：facts 入队条目 section 恒 None（引擎不再拥有具名节）"):
    reset()
    os.write.over(MemoryStore.userMemoryPath, "# User\n\n## 工作风格\n\n- 早睡早起\n", createFolders = true)
    NebulaMemoryHook
      .enqueueFacts(List("FACT 1: [PATTERN] 新事实甲", "FACT 2: [DECISION] 新裁定乙"), Some("sess-1"))
      .unsafeRunSync()
    val notes = MemoryQueue.readState().notes
    assertEquals(notes.size, 2)
    assertEquals(notes.map(_.section).distinct, Vector[Option[String]](None),
      "无具名节：`## Dream Extract` 已随 DreamMode 机制停用退役")

  test("无具名节的文件上照旧可落：两条 pending 判 would-apply（文件尾追加），零缺节重试族"):
    reset()
    os.write.over(MemoryStore.userMemoryPath, "# User\n\n## 工作风格\n\n- 早睡早起\n", createFolders = true)
    NebulaMemoryHook
      .enqueueFacts(List("FACT 1: [PATTERN] 新事实甲", "FACT 2: [DECISION] 新裁定乙"), Some("sess-1"))
      .unsafeRunSync()
    val path = MemoryStore.userMemoryPath
    val plan = MemoryQueue.plan(
      MemoryQueue.readState(),
      Map("user" -> MemoryQueue.TargetFile(path.toString, os.read(path))))
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 2,
      s"两条都可落（section=None ⇒ 文件尾追加）: ${plan.items}")
    assertEquals(plan.retryable, Vector.empty[String],
      "不落缺节重试族（引擎无具名节可缺）")

  // ===== ④ 反向钉：该节不再是可落节 ⇒ 引擎不创建它 =====

  test("退役面反向钉：以 `## Dream Extract` 为落点的 append 在无该节的文件上判 would-retry（零落笔 ⇒ 不创建该节）"):
    val note = noteOf("q-1", Some("## Dream Extract"), Some("- [PATTERN] 旧语义落点"))
    val plan = MemoryQueue.plan(stateOf(Vector(note)), targetFile("# User\n\n## 工作风格\n\n- 早睡早起\n"))
    assertEquals(plan.countOf(MemoryQueue.Bucket.WouldApply), 0,
      s"该节不再是可落节（无节 ⇒ 不落、不新建）: ${plan.items}")
    assertEquals(plan.retryable, Vector("q-1"),
      "落点节不存在 ⇒ 可重试族（保持 pending，零落笔）")
    assert(!plan.render().contains("## Dream Extract"),
      "计划输出不含该节名（引擎侧无该节概念）")

end DreamModeSpec
