package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import nebflow.core.ask.{AskContextSources, AskGatewayServices, AskNeighbor, AskPromptCache, AskRecallHit, AskServices, AskSessionRegistry, AskSessions}

import java.nio.file.Files

/* ═══════════════════════════════════════════════════════════════════════════
 * read_file_content 只读工具 spec（2026-10-31 只读工具批；设计清单 =
 * docs/design/20261031_113700_ask-readonly-tool-design__delegate-nebflow-09ef7b2e
 * .zh-CN.md 逐字断言面）。
 *
 * 覆盖面：
 *   R1  身份双保险：非 ask 会话（None / 普通 sessionId）一律拒绝
 *   R2  ask 会话无绑定 → ASK_READ_NO_BINDING
 *   R3  happy path：行窗口缺省档（1..200）、JSON 结构化字段齐（totalLines/
 *       startLine/returnedLines/truncated/content）
 *   R4  行窗口参数：start_line/max_lines 翻页 + max_lines 硬顶 500
 *   R5  start_line 越界（> totalLines）→ 空窗口、truncated 语义不炸
 *   R6  文件体积顶：> 2MB 显式拒绝（ASK_READ_TOO_LARGE，不静默截断）
 *   R7  输出字符顶：单行巨型文件在窗口内截断（truncated:true + 标记）
 *   R8  扩展名双保险：绕过 askOpen 直落 .txt 绑定 → ASK_READ_NOT_ASKABLE
 *   R9  文件缺席 → ASK_READ_NOT_FOUND
 *   R10 白名单同源：AllowedTools 封闭枚举含本工具名（挂载面钉死）
 * ═══════════════════════════════════════════════════════════════════════════ */

final class ReadFileContentToolSpec extends munit.FunSuite:

  private object NullSources extends AskContextSources:
    override def fileStat(absPath: String): IO[Option[(Long, Long)]] = IO.pure(None)
    override def fileText(absPath: String): IO[Option[(String, Long, Long)]] = IO.pure(None)
    override def graphNeighbors(absPath: String): IO[List[AskNeighbor]] = IO.pure(Nil)
    override def recallHits(query: String): IO[List[AskRecallHit]] = IO.pure(Nil)
    override def recallDbMtime: IO[Long] = IO.pure(0L)
    override def graphVersion: IO[String] = IO.pure("")

  private def tempRoot(): os.Path =
    os.Path(Files.createTempDirectory("ask-read-tool-spec"))

  private def installRegistry(root: os.Path): AskSessionRegistry =
    val registry = new AskSessionRegistry(root)
    AskGatewayServices.install(AskServices(registry, NullSources, new AskPromptCache))
    registry

  override def afterAll(): Unit = AskGatewayServices.uninstall()

  private def ctx(sessionId: Option[String]) =
    ToolContext(projectRoot = os.pwd.toString, sessionId = sessionId)

  private def call(sessionId: Option[String])(input: (String, Json)*): IO[Either[ToolError, String]] =
    ReadFileContentTool.call(Json.obj(input.toList*).asObject.get, ctx(sessionId))

  private def bindMd(root: os.Path, registry: AskSessionRegistry, name: String = "笔记.md"): (String, os.Path) =
    val file = root / name
    val binding = registry.bind(file.toString, name).unsafeRunSync()
    (binding.id, file)

  // ── R1 身份双保险 ──────────────────────────────────────────────────────────

  test("R1 非 ask 会话一律拒绝（None 与 普通 sessionId 同拒，fail-closed）") {
    installRegistry(tempRoot())
    val none = call(None)().unsafeRunSync()
    assert(none.isLeft, clue(none))
    assert(none.swap.toOption.get.message.contains("ASK_READ_FORBIDDEN"), clue(none))
    val plain = call(Some("sess-1"))().unsafeRunSync()
    assert(plain.isLeft && plain.swap.toOption.get.message.contains("ASK_READ_FORBIDDEN"), clue(plain))
  }

  // ── R2 无绑定 ──────────────────────────────────────────────────────────────

  test("R2 ask 会话无绑定 → ASK_READ_NO_BINDING") {
    val registry = installRegistry(tempRoot())
    val orphanSid = AskSessions.sessionIdFor("/nope/未绑定.md") // 不在绑定表
    val res = call(Some(orphanSid))().unsafeRunSync()
    assert(res.isLeft && res.swap.toOption.get.message.contains("ASK_READ_NO_BINDING"), clue(res))
    assert(registry.byId(orphanSid).unsafeRunSync().isEmpty)
  }

  // ── R3 happy path：缺省行窗口 ───────────────────────────────────────────────

  test("R3 缺省窗口 = 1..200，JSON 字段齐全") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val (sid, file) = bindMd(root, registry)
    os.write(file, (1 to 300).map(i => s"第 $i 行").mkString("\n"))

    val res = call(Some(sid))().unsafeRunSync()
    assert(res.isRight, clue(res))
    val j = parse(res.toOption.get).toOption.get.hcursor
    assertEquals(j.downField("file").as[String].toOption.get, "笔记.md")
    assertEquals(j.downField("totalLines").as[Int].toOption.get, 300)
    assertEquals(j.downField("startLine").as[Int].toOption.get, 1)
    assertEquals(j.downField("returnedLines").as[Int].toOption.get, 200)
    assertEquals(j.downField("truncated").as[Boolean].toOption.get, false)
    val content = j.downField("content").as[String].toOption.get
    assert(content.startsWith("第 1 行") && content.endsWith("第 200 行"), clue(content.take(40)))
  }

  // ── R4 行窗口参数 ──────────────────────────────────────────────────────────

  test("R4 start_line/max_lines 翻页 + max_lines 硬顶 500") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val (sid, file) = bindMd(root, registry)
    os.write(file, (1 to 900).map(i => s"L$i").mkString("\n"))

    val page = call(Some(sid))("start_line" -> Json.fromInt(201), "max_lines" -> Json.fromInt(50)).unsafeRunSync()
    val j = parse(page.toOption.get).toOption.get.hcursor
    assertEquals(j.downField("startLine").as[Int].toOption.get, 201)
    assertEquals(j.downField("returnedLines").as[Int].toOption.get, 50)
    assert(j.downField("content").as[String].toOption.get.startsWith("L201"), clue(page))

    val capped = call(Some(sid))("max_lines" -> Json.fromInt(100000)).unsafeRunSync()
    assertEquals(parse(capped.toOption.get).toOption.get.hcursor.downField("returnedLines").as[Int].toOption.get, 500)
  }

  // ── R5 越界窗口 ────────────────────────────────────────────────────────────

  test("R5 start_line 越界 → 空窗口、truncated=true、零异常") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val (sid, file) = bindMd(root, registry)
    os.write(file, "只有一行")

    val res = call(Some(sid))("start_line" -> Json.fromInt(99)).unsafeRunSync()
    assert(res.isRight, clue(res))
    val j = parse(res.toOption.get).toOption.get.hcursor
    assertEquals(j.downField("returnedLines").as[Int].toOption.get, 0)
    assertEquals(j.downField("truncated").as[Boolean].toOption.get, true)
    assertEquals(j.downField("totalLines").as[Int].toOption.get, 1)
  }

  // ── R6 文件体积顶 ──────────────────────────────────────────────────────────

  test("R6 > 2MB 显式拒绝（ASK_READ_TOO_LARGE）") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val (sid, file) = bindMd(root, registry)
    os.write(file, "x" * (ReadFileContentTool.MaxFileBytes.toInt + 16))

    val res = call(Some(sid))().unsafeRunSync()
    assert(res.isLeft && res.swap.toOption.get.message.contains("ASK_READ_TOO_LARGE"), clue(res))
  }

  // ── R7 输出字符顶 ──────────────────────────────────────────────────────────

  test("R7 单行巨型文件在窗口内按 64KB 截断（truncated:true + 标记）") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val (sid, file) = bindMd(root, registry)
    os.write(file, "长" * (ReadFileContentTool.MaxOutputChars + 4096))

    val res = call(Some(sid))("max_lines" -> Json.fromInt(1)).unsafeRunSync()
    assert(res.isRight, clue(res))
    val j = parse(res.toOption.get).toOption.get.hcursor
    assertEquals(j.downField("truncated").as[Boolean].toOption.get, true)
    val content = j.downField("content").as[String].toOption.get
    assert(content.length <= ReadFileContentTool.MaxOutputChars + 80, clue(content.length))
    assert(content.contains("truncated at 64KB"), clue(content.take(30)))
  }

  // ── R8 扩展名双保险 ─────────────────────────────────────────────────────────

  test("R8 绕过 askOpen 直落 .txt 绑定 → ASK_READ_NOT_ASKABLE（绑定面不清理）") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val legacy = registry.bind("/vault/旧笔记.txt", "旧笔记.txt").unsafeRunSync()
    os.write.over(root / "ask" / "index.json", os.read(root / "ask" / "index.json")) // no-op，绑定已落盘

    val res = call(Some(legacy.id))().unsafeRunSync()
    assert(res.isLeft && res.swap.toOption.get.message.contains("ASK_READ_NOT_ASKABLE"), clue(res))
    assert(registry.byId(legacy.id).unsafeRunSync().isDefined, "拒读零删除（§9-8 仅闸）")
  }

  // ── R9 文件缺席 ────────────────────────────────────────────────────────────

  test("R9 绑定在、文件被删 → ASK_READ_NOT_FOUND") {
    val root = tempRoot()
    val registry = installRegistry(root)
    val (sid, file) = bindMd(root, registry)
    os.remove(file)

    val res = call(Some(sid))().unsafeRunSync()
    assert(res.isLeft && res.swap.toOption.get.message.contains("ASK_READ_NOT_FOUND"), clue(res))
  }

  // ── R10 白名单同源 ─────────────────────────────────────────────────────────

  test("R10 AllowedTools 封闭枚举含 read_file_content（挂载面钉死）") {
    assert(AskSessions.AllowedTools.contains(ReadFileContentTool.Name), clue(AskSessions.AllowedTools))
  }
end ReadFileContentToolSpec
