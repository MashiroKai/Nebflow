package nebflow.core.ask

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.core.wiki.MdHtmlGate
import nebflow.shared.NebflowLogger

import java.nio.file.Files

/* ═══════════════════════════════════════════════════════════════════════════
 * md/html 类型硬约束闸 spec（第三批 A 组，调研稿 §7-A1/A3 + §9-7/§9-8 裁定；
 * 设计稿 docs/design/20261028_canvas-ask-mode-design.zh-CN.md §2「类型硬约束闸」）。
 *
 * 覆盖面：
 *   G1  四扩展名白名单单点（AskableExtensions 封闭枚举逐字钉死，§9-7）
 *   G2  isAskable 判据：四扩展名放行（含大小写变体）/ 其余一律拒（含无扩展
 *       名、伪扩展 .mdx、null、空串）
 *   G3  AskServices.agentDefFor 深防：存量非 md/html 绑定 spawn 面拒答
 *       （A3——askOpen 前闸之外的直发帧/旧绑定全量防线）
 *   G4  AskServices.assembleNow 深防：cachedPrompt 组装通路复问同判据
 *   G5  存量绑定不回退（§9-8 仅闸新问题、不清理、自然衰减）：预置非白名单
 *       绑定的 index.json 启动后绑定原样在场、读写操作零删除
 * ═══════════════════════════════════════════════════════════════════════════ */

final class AskGateSpec extends munit.FunSuite:

  // ── G1 白名单单点 ──────────────────────────────────────────────────────────

  test("G1 AskableExtensions = 四扩展名封闭枚举（§9-7 裁定逐字）") {
    assertEquals(AskSessions.AskableExtensions, Set(".md", ".markdown", ".html", ".htm"))
  }

  test("G1b 词条同闸：图谱文档面 ⊆ ask 白名单（词条作用域收窄的引擎侧不变式）") {
    // 词条定义块/词条消解只活在图谱扫描面（DocExtensions 文档）——本不变式保证
    // 词条功能作用域永不越出四扩展名闸。现状 DocExtensions 少 .htm（§9-6：html
    // 词条定义面 v1 不做）——是覆盖差而非闸漏，扩面属另行批次，此处只钉「不越界」。
    assert(
      nebflow.core.wiki.WikiIndex.DocExtensions.subsetOf(AskSessions.AskableExtensions),
      clue(nebflow.core.wiki.WikiIndex.DocExtensions)
    )
  }

  // ── G2 isAskable 判据 ──────────────────────────────────────────────────────

  test("G2a 四扩展名放行（小写/大写/混合大小写，大小写不敏感同 FileTypeRegistry 口径）") {
    val yes = List(
      "/vault/笔记.md", "/vault/笔记.MD", "/vault/笔记.Md",
      "/vault/a.markdown", "/vault/a.MARKDOWN",
      "/vault/page.html", "/vault/page.HTML",
      "/vault/page.htm", "/vault/page.HTM",
      "相对/路径.md", "/无目录.md"
    )
    yes.foreach(p => assert(AskSessions.isAskable(p), clue(p)))
  }

  test("G2b 白名单外一律拒（其余类型/伪扩展/无扩展名/空/null）") {
    val no = List(
      "/vault/notes.txt", "/vault/paper.pdf", "/vault/code.scala",
      "/vault/pic.png", "/vault/data.json", "/vault/noext",
      "/vault/doc.mdx",      // 伪扩展：endsWith(".md") 不得误放
      "/vault/xmd",          // 无点裸名不得误放
      "/vault/a.htm2",       // 前缀相似扩展不得误放
      ""
    )
    no.foreach(p => assert(!AskSessions.isAskable(p), clue(p)))
    assert(!AskSessions.isAskable(null))
  }

  test("G2c 带 ?query / #fragment 的路径先归一（第三批显式要求）") {
    // 白名单扩展名后挂 query/锚点，仍必须放行（旧 endsWith 实现会误拒）。
    val yes = List(
      "/vault/笔记.md?tab=1",
      "/vault/笔记.md#^anchor",
      "/vault/笔记.md?x=1#frag",
      "/vault/笔记.md#frag?notquery",   // # 先到 → 截在 #
      "/vault/page.html?project=alpha",
      "/workspace/docs/README.MD?v=2",
      "  /vault/笔记.markdown#frag  "   // 前后空白
    )
    yes.foreach(p => assert(MdHtmlGate.isMdHtml(p), clue(p)))
    // 白名单外后挂 query/锚点，仍必须拒。
    val no = List(
      "/vault/notes.txt?tab=1",
      "/vault/paper.pdf#page=3",
      "/vault/readme?tab=1",           // 无扩展名 + query 不得被 query 伪装成扩展名
      "/vault/noext#frag"
    )
    no.foreach(p => assert(!MdHtmlGate.isMdHtml(p), clue(p)))
    // 目录名里的点不算扩展名（旧 endsWith 不犯此错，新 extOf 必须同守）。
    assert(!MdHtmlGate.isMdHtml("/vault/a.b/note"), "/vault/a.b/note")
    assert(MdHtmlGate.isMdHtml("/vault/a.b/note.md"), "/vault/a.b/note.md")
    // extOf 归一读数。
    assertEquals(MdHtmlGate.extOf("/vault/笔记.MD?x=1"), ".md")
    assertEquals(MdHtmlGate.extOf("/vault/noext?tab=1"), "")
    assertEquals(MdHtmlGate.extOf(""), "")
  }

  test("G2d 拒绝原因统一措辞含扩展名（三处接入点同源，日志不刷屏）") {
    assertEquals(
      MdHtmlGate.rejectReason("/vault/notes.txt"),
      "not askable: not md/html (ext=.txt, allowed=.htm,.html,.markdown,.md)"
    )
    assert(MdHtmlGate.rejectReason("/vault/noext").contains("ext=<none>"))
    assert(MdHtmlGate.RejectPrefix == "not askable: not md/html")
  }

  // ── G3/G4 spawn 面深防（AskServices）───────────────────────────────────────

  /** 空实现源（组装腿不触盘/不触图谱/不触记忆）。 */
  private object NullSources extends AskContextSources:
    override def fileStat(absPath: String): IO[Option[(Long, Long)]] = IO.pure(None)
    override def fileText(absPath: String): IO[Option[(String, Long, Long)]] = IO.pure(None)
    override def graphNeighbors(absPath: String): IO[List[AskNeighbor]] = IO.pure(Nil)
    override def recallHits(query: String): IO[List[AskRecallHit]] = IO.pure(Nil)
    override def recallDbMtime: IO[Long] = IO.pure(0L)
    override def graphVersion: IO[String] = IO.pure("")

  private def servicesWith(dataRoot: os.Path): AskServices =
    AskServices(
      new AskSessionRegistry(dataRoot),
      NullSources,
      new AskPromptCache
    )

  private def tempRoot(): os.Path =
    os.Path(Files.createTempDirectory("ask-gate-spec"))

  test("G3 agentDefFor：非 md/html 存量绑定 spawn 面拒答（A3 全量防线）") {
    val svc = servicesWith(tempRoot())
    // 绕过 askOpen 前闸直接落绑定 = 「存量绑定 + 直发帧」场景（§9-8：绑定不清理）。
    val legacy = svc.registry.bind("/vault/旧笔记.txt", "旧笔记.txt").unsafeRunSync()
    val err = svc.agentDefFor(legacy.id).attempt.unsafeRunSync()
    assert(err.isLeft, clue(err))
    assert(err.swap.toOption.get.getMessage.contains("not askable"), clue(err))
  }

  test("G3b agentDefFor：md/html 绑定照常给 AgentDef（闸不误伤）") {
    val svc = servicesWith(tempRoot())
    val ok = svc.registry.bind("/vault/笔记.md", "笔记.md").unsafeRunSync()
    val defn = svc.agentDefFor(ok.id).unsafeRunSync()
    assertEquals(defn.name, "ask")
    assert(defn.description.contains("笔记.md"), clue(defn.description))
  }

  test("G4 assembleNow：缓存缺席直取组装通路复问同判据") {
    val svc = servicesWith(tempRoot())
    val legacy = svc.registry.bind("/vault/paper.pdf", "paper.pdf").unsafeRunSync()
    val err = svc.cachedPrompt(legacy.id, legacy).attempt.unsafeRunSync()
    assert(err.isLeft, clue(err))
    assert(err.swap.toOption.get.getMessage.contains("not askable"), clue(err))
    // 拒答后提示词缓存零写入（无半成品状态残留）。
    assertEquals(svc.prompts.get(legacy.id), None)
  }

  // ── G5 存量绑定不回退（仅闸新问题、不清理、自然衰减 §9-8）──────────────────

  test("G5 预置非白名单绑定的 index.json：加载后原样在场、拒答零删除零改写") {
    val root = tempRoot()
    os.makeDir.all(root / "ask")
    // 手工构造「闸前时代」的存量绑定表（.txt + .md 各一）。
    val legacyTxtId = AskSessions.sessionIdFor("/vault/旧笔记.txt")
    val legacyMdId  = AskSessions.sessionIdFor("/vault/笔记.md")
    val json =
      s"""{
         |  "bindings": [
         |    {"id": "$legacyTxtId", "file": "/vault/旧笔记.txt", "fileName": "旧笔记.txt", "createdAt": 1720000000000, "mainSessionId": "main-1"},
         |    {"id": "$legacyMdId", "file": "/vault/笔记.md", "fileName": "笔记.md", "createdAt": 1720000001000, "mainSessionId": ""}
         |  ]
         |}""".stripMargin
    os.write.over(root / "ask" / "index.json", json)

    val svc = servicesWith(root)
    val txt = svc.registry.byId(legacyTxtId).unsafeRunSync()
    assert(txt.isDefined, "存量 .txt 绑定必须原样加载（不清理）")
    assertEquals(txt.get.file, "/vault/旧笔记.txt")
    assertEquals(txt.get.mainSessionId, "main-1") // 转交目标也不动

    // 新问被拒（.txt），且拒答不得触碰绑定表。
    val err = svc.agentDefFor(legacyTxtId).attempt.unsafeRunSync()
    assert(err.isLeft, clue(err))
    val after = svc.registry.byId(legacyTxtId).unsafeRunSync()
    assert(after.isDefined, clue(after))
    assertEquals(after.get.file, "/vault/旧笔记.txt")

    // md 绑定照常可用（存量功能零回退）。
    assert(svc.agentDefFor(legacyMdId).unsafeRunSync().name == "ask")

    // 持久化文件未被拒答面改写（落盘字节仍是我们写入的那份）。
    val onDisk = os.read(root / "ask" / "index.json")
    assert(onDisk.contains("/vault/旧笔记.txt"), "存量绑定落盘形态不得变")
  }
end AskGateSpec
