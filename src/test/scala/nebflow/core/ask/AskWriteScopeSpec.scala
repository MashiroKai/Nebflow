package nebflow.core.ask

import cats.effect.unsafe.implicits.global

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/* ═══════════════════════════════════════════════════════════════════════════
 * ask 写面收窄批（2026-11-18）· 越权矩阵 + 审计留痕 + 转交结构 红面 spec
 *
 * 作者令：「ask 身份对文件的编辑/写入只允许目标 = 该 ask 会话绑定的那份 canvas
 * 文档；其它任何路径一律拒绝。判定必须做路径规范化，不得被绕过：`..` 段、相对
 * 路径、绝对路径、符号链接（含链到别处的软链）、大小写差异、重复挂载/别名、同一
 * 文件的等价拼写 —— 归一后指向同一份文档的放行，指向别处的拒绝。拒绝必须显式
 * 可读：说明「本会话只能编辑它对应的文档」+ 给出转交主会话的建议。」
 *
 * 本 spec 用**真实文件系统上的临时沙箱**驱动（软链/别名挂载/大小写只有真 FS 能
 * 表达），逐格给读数；断言的是 AskPathBoundary（判决单点）与 AskEditReviews
 * （消费点）与 AskWriteAudit（留痕面）。
 * ═══════════════════════════════════════════════════════════════════════════ */
final class AskWriteScopeSpec extends munit.FunSuite:

  private val utf8 = StandardCharsets.UTF_8

  private def write(p: Path, text: String): Path =
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(utf8))

  private def read(p: Path): String = new String(Files.readAllBytes(p), utf8)

  /** 沙箱：root/notes.md（绑定文档）+ root/other.md（另一份）+ 别处的 elsewhere/other.md。 */
  private final case class Box(root: Path, bound: Path, sibling: Path, outside: Path)

  private def box(): Box =
    val root = Files.createTempDirectory("ask-scope-spec-")
    val bound = write(root.resolve("notes.md"), "# bound\n\noriginal\n")
    val sibling = write(root.resolve("other.md"), "# sibling\n\nuntouched\n")
    val outside = write(Files.createTempDirectory("ask-scope-outside-").resolve("other.md"), "# outside\n")
    Box(root, bound, sibling, outside)

  private def decisionOf(b: Box, spelling: String): AskPathBoundary.Decision =
    AskPathBoundary.decideSync(b.bound.toString, spelling)

  private def assertAllowed(b: Box, label: String, spelling: String): Unit =
    val d = decisionOf(b, spelling)
    assert(d.allowed, s"ALLOW-cell expected but refused: $label = `$spelling` → ${d.reason}")

  private def assertRefused(b: Box, label: String, spelling: String): Unit =
    val d = decisionOf(b, spelling)
    assert(d.refused, s"REFUSE-cell expected but allowed: $label = `$spelling`")
    assert(
      d.reason.contains(AskPathBoundary.ScopeRule),
      s"refusal must state the scope rule: $label → ${d.reason}"
    )
    assert(
      d.reason.contains(AskPathBoundary.HandoffHint),
      s"refusal must carry the handoff suggestion: $label → ${d.reason}"
    )

  // ── 矩阵 A：合法拼写（归一后 = 绑定文档）⇒ 放行 ──────────────────────────

  test("M-allow: every spelling that normalises to the bound document is allowed") {
    val b = box()
    assertAllowed(b, "A1 exact absolute", b.bound.toString)
    assertAllowed(b, "A2 dot segment", s"${b.root.toString}/./notes.md")
    assertAllowed(b, "A3 `..` round-trip", b.root.resolve("sub/../notes.md").toString)
    assertAllowed(b, "A4 relative basename (resolved against the bound dir)", "notes.md")
    assertAllowed(b, "A5 relative with `..`", "sub/../notes.md")
    assertAllowed(b, "A6 `./`-prefixed relative", "./notes.md")

    // A7 软链指向绑定文档（归一后同一份）
    val link = b.root.resolve("link-to-notes.md")
    Files.createSymbolicLink(link, b.bound)
    assertAllowed(b, "A7 symlink → bound document", link.toString)

    // A8 目录别名挂载（目录软链）下的同一份文档
    val alias = b.root.resolve("alias")
    Files.createSymbolicLink(alias, b.root)
    assertAllowed(b, "A8 alias-mount dir (symlinked directory)", alias.resolve("notes.md").toString)

    // A9 硬链（同 inode ⇒ 同一份文档）
    val hard = b.root.resolve("hard.md")
    try
      Files.createLink(hard, b.bound)
      assertAllowed(b, "A9 hard link (same inode)", hard.toString)
    catch case _: UnsupportedOperationException | _: java.io.IOException => () // FS 不支持则跳过该格

    // A10 大小写变体：仅当本卷大小写不敏感时它才指向**同一份**文档；否则它指向
    //     一份不存在的文件 ⇒ 必须拒绝（下一测试的 B 组同判据）。
    val upper = b.root.resolve("NOTES.MD")
    val caseInsensitiveVolume = Files.exists(upper) && Files.isSameFile(upper, b.bound)
    if caseInsensitiveVolume then assertAllowed(b, "A10 case variant (case-insensitive volume)", upper.toString)
    else assertRefused(b, "A10 case variant (case-sensitive volume → a different/none file)", upper.toString)
  }

  // ── 矩阵 B：越界（归一后 ≠ 绑定文档）⇒ 全拒，且理由可读 + 带转交建议 ──────

  test("M-deny: every spelling that normalises to another target is refused, readably") {
    val b = box()
    // B1 兄弟文件（绝对拼写）
    assertRefused(b, "B1 sibling file", b.sibling.toString)
    // B2 相对拼写指向兄弟文件
    assertRefused(b, "B2 sibling file (relative)", "other.md")
    // B3 `..` 段（归一后落到另一份文件 ⇒ 拒，不是「一律拒 `..`」——见 A3 的反证）
    assertRefused(b, "B3 `..` normalising onto a sibling", b.root.resolve("sub/../other.md").toString)
    // B4 绝对路径指向沙箱外的另一份文件
    assertRefused(b, "B4 absolute path outside the sandbox", b.outside.toString)
    // B5 软链指向别处
    val linkElsewhere = b.root.resolve("link-elsewhere.md")
    Files.createSymbolicLink(linkElsewhere, b.sibling)
    assertRefused(b, "B5 symlink → another file", linkElsewhere.toString)
    // B6 软链指向沙箱之外
    val linkOutside = b.root.resolve("link-outside.md")
    Files.createSymbolicLink(linkOutside, b.outside)
    assertRefused(b, "B6 symlink → outside file", linkOutside.toString)
    // B7 目录别名挂在别处
    val aliasElsewhere = b.root.resolve("alias-elsewhere")
    Files.createSymbolicLink(aliasElsewhere, b.outside.getParent)
    assertRefused(b, "B7 alias-mount of another directory", aliasElsewhere.resolve("other.md").toString)
    // B8 断链软链（不存在）
    val dangling = b.root.resolve("dangling.md")
    Files.createSymbolicLink(dangling, b.root.resolve("nope.md"))
    assertRefused(b, "B8 dangling symlink", dangling.toString)
    // B9 不存在的路径（ask 面永不创建文件）
    assertRefused(b, "B9 non-existent target", b.root.resolve("brand-new.md").toString)
    // B10 目录本身
    assertRefused(b, "B10 a directory", b.root.toString)
    // B11 空路径
    assertRefused(b, "B11 empty path", "   ")
    // B12 系统路径
    assertRefused(b, "B12 system path", "/etc/hosts")
  }

  // ── 消费点：AskEditReviews 真落盘只落绑定文档 ─────────────────────────────

  test("C: ask_write/ask_edit apply exactly to the bound document and never to another file") {
    val b = box()
    val auditFile = Files.createTempFile("ask-scope-audit-", ".jsonl")
    AskWriteAudit.injectTargetForTest(Some(auditFile))
    val sid = s"ask-${java.util.UUID.randomUUID().toString.take(8)}"
    val binding = AskBinding(id = sid, file = b.bound.toString, fileName = "notes.md", createdAt = 0L)
    try
      // C1 合法相对拼写 ⇒ 直接落盘（绑定文档内容变化）
      val ok = AskEditReviews
        .applyChange(sid, binding, "notes.md", "ask_write", _ => Right("# bound\n\nrewritten by ask\n"))
        .unsafeRunSync()
      assert(ok.isRight, s"in-scope rewrite must succeed: $ok")
      assert(read(b.bound).contains("rewritten by ask"), "the bound document must be the one written")

      // C2 越界拼写 ⇒ 拒 + 另一份文件一个字节都不动
      val before = read(b.sibling)
      val denied = AskEditReviews
        .applyChange(sid, binding, b.sibling.toString, "ask_write", _ => Right("HACKED\n"))
        .unsafeRunSync()
      assert(denied.isLeft, "out-of-scope rewrite must be refused")
      val why = denied.swap.getOrElse("")
      assert(why.contains(AskPathBoundary.ScopeRule), s"refusal must state the scope rule: $why")
      assert(why.contains(AskPathBoundary.HandoffHint), s"refusal must suggest the main-session handoff: $why")
      assertEquals(read(b.sibling), before, "the sibling file must be byte-identical after a refused write")

      // C3 软链指向别处 ⇒ 拒（不是「跟着软链写」）
      val linkElsewhere = b.root.resolve("link-elsewhere.md")
      Files.createSymbolicLink(linkElsewhere, b.sibling)
      val viaLink = AskEditReviews
        .applyChange(sid, binding, linkElsewhere.toString, "ask_edit", _ => Right("# link write\n"))
        .unsafeRunSync()
      assert(viaLink.isLeft, "writing through a symlink to another file must be refused")
      assertEquals(read(b.sibling), before, "the symlink target must stay untouched")

      // D 审计：每次判决一行（放行 ∧ 拒绝都在）
      val rows = AskWriteAudit.readAll(auditFile)
      assertEquals(rows.length, 3, s"expected one audit row per verdict: ${rows.mkString(" | ")}")
      assert(rows.head.contains("\"result\":\"allow\""), s"row 1 must be an allow: ${rows.head}")
      assert(rows.head.contains("\"identity\":\"ask-"), s"row 1 must carry the identity: ${rows.head}")
      assert(rows.head.contains("\"tool\":\"ask_write\""), s"row 1 must carry the tool: ${rows.head}")
      assert(rows.head.contains("\"ts\":"), s"row 1 must carry the time: ${rows.head}")
      assert(rows(1).contains("\"result\":\"deny\""), s"row 2 must be a deny: ${rows(1)}")
      assert(rows(1).contains("\"canonical\":\""), s"row 2 must carry the normalised path: ${rows(1)}")
      assert(rows(1).contains(AskPathBoundary.ScopeRule), s"row 2 must carry the readable reason: ${rows(1)}")
      assert(rows(2).contains("\"result\":\"deny\""), s"row 3 must be a deny: ${rows(2)}")
      assert(rows(2).contains("\"tool\":\"ask_edit\""), s"row 3 must carry the tool: ${rows(2)}")
    finally
      AskWriteAudit.injectTargetForTest(None)
      AskEditReviews.resetForTest(sid)
    end try
  }

  // ── 第二条通道（shell）不是写通道，但越权尝试必须留痕 ────────────────────

  /* Historical Ask-only Bash executor, no longer connected to live Ask sessions.
  test("E: a shell write attempt is refused and audited (Bash is not a second write channel)") {
    val auditFile = Files.createTempFile("ask-scope-audit-bash-", ".jsonl")
    AskWriteAudit.injectTargetForTest(Some(auditFile))
    try
      val ctx = nebflow.core.tools.ToolContext(
        projectRoot = "/tmp",
        sessionId = Some("ask-deadbeef")
      )
      val (msg, isError) = AskBashFace
        .runAskBash(io.circe.JsonObject("command" -> io.circe.Json.fromString("echo x > /tmp/ask-scope-hack.md")), ctx)
        .unsafeRunSync()
      assert(isError, s"a write primitive must be refused: $msg")
      assert(msg.startsWith("Ask Bash refused"), s"refusal must be explicit: $msg")
      val rows = AskWriteAudit.readAll(auditFile)
      assertEquals(rows.length, 1, s"the refused shell attempt must be audited: ${rows.mkString(" | ")}")
      assert(rows.head.contains("\"tool\":\"Bash\""), s"the audit row must name the Bash face: ${rows.head}")
      assert(
        rows.head.contains("\"identity\":\"ask-deadbeef\""),
        s"the audit row must carry the identity: ${rows.head}"
      )
      assert(rows.head.contains("\"result\":\"deny\""), s"the audit row must be a deny: ${rows.head}")
      assert(!Files.exists(Path.of("/tmp/ask-scope-hack.md")), "the shell must not have created the file")
    finally AskWriteAudit.injectTargetForTest(None)
    end try
  }

  // ── 第三条：提示词两条硬约束 + 清单派生（无手抄第二份）────────────────────

  test("F: the ask prompt derives the tool list from the registry and states both hard constraints") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "CONTEXT")
    // 清单派生：注册表里每个 ask 面工具都出现（名字来自 AllowedTools 单点）
    val map = nebflow.core.tools.ToolRegistry.TOOL_MAP
    AskSessions.AllowedTools.foreach { n =>
      assert(prompt.contains(s"- $n:"), s"the derived tool list must carry `$n` (registry-derived, not hand-written)")
    }
    assert(!map.contains("Write") || !prompt.contains("- Write:"), "the ask face must not advertise Write")
    assert(!prompt.contains("- Edit:"), "the ask face must not advertise Edit")
    // 硬约束 1：写面只有一个
    assert(prompt.contains("本会话只能编辑它对应的那份文档"), "constraint 1 (only the bound document) must be in the prompt")
    assert(prompt.contains("QUERY-ONLY, fail-closed"), "the Bash face must stay documented as query-only")
    // 硬约束 2：复杂任务结构化转交
    assert(prompt.contains("复杂任务转交主会话"), "constraint 2 (hand off complex tasks) must be in the prompt")
    assert(prompt.contains("send_message_to_main_chat"), "the handoff channel must be named")
    assert(prompt.contains("task = 做什么"), "the structured handoff fields must be named")
    assert(prompt.contains("expected = 期望产出"), "the structured handoff fields must be named")
  }

  */
  test("F: handoff is optional and no obsolete Ask-only restriction is advertised") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "CONTEXT")
    assert(prompt.contains("转交是可选的"))
    assert(!prompt.contains("本会话只能编辑它对应的那份文档"))
    assert(!prompt.contains("QUERY-ONLY, fail-closed"))
  }

  // ── 第四条：转交请求结构化 + 机器可读 + 面板可见帧 ────────────────────────

  test("G: the handoff request is structured, machine-readable and surfaces as a panel frame") {
    val req = nebflow.core.AskHandoffPort.Request(
      askSessionId = "ask-12345678",
      file = "/vault/notes.md",
      fileName = "notes.md",
      question = nebflow.core.AskHandoffPort.handoffSummary("把三个文件都改一遍", List("/a.md", "/b.md"), "跨文件", "改完的清单"),
      leg = "ask-tool",
      task = "把三个文件都改一遍",
      paths = List("/a.md", "/b.md"),
      reason = "跨文件",
      expected = "改完的清单"
    )
    val h = AskHandoffWire.of(req)
    val json = AskHandoffWire.json(h)
    val fields = List("task", "paths", "reason", "expected", "askSessionId", "file", "fileName", "leg")
    fields.foreach { f =>
      assert(json.hcursor.downField(f).focus.isDefined, s"machine-readable handoff must carry `$f`: ${json.noSpaces}")
    }
    assertEquals(json.hcursor.downField("paths").as[List[String]].toOption.getOrElse(Nil), List("/a.md", "/b.md"))
    val frame = AskHandoffWire.ackFrame("ask-12345678", ok = true, "", h)
    assertEquals(frame.hcursor.downField("type").as[String].toOption, Some("askHandoffAck"))
    assertEquals(frame.hcursor.downField("ok").as[Boolean].toOption, Some(true))
    assert(
      frame.hcursor.downField("summary").as[String].toOption.exists(_.contains("把三个文件都改一遍")),
      s"the panel summary must carry the task: ${frame.noSpaces}"
    )
    assert(
      frame.hcursor.downField("handoff").focus.isDefined,
      "the panel frame must carry the machine-readable handoff"
    )
    val body = AskHandoffWire.injectedBody(h, "ask:ask-12345678")
    List("做什么", "涉及路径", "为什么需要主会话", "期望产出").foreach { k =>
      assert(body.contains(k), s"the injected main-session body must carry `$k`: $body")
    }
    assert(body.contains("/a.md, /b.md"), s"the injected body must carry the paths: $body")
  }

end AskWriteScopeSpec
