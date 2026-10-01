package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.core.FilePolicyPort
import nebflow.gateway.NfFilePolicy

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * card-persist 批（作者原话：「每次 Card 工具写的都保存本地，这样修改的话可以直接用
 * edit 修改，然后让 Card 工具可以显示本地文件」）——**引擎面**判据钉。
 *
 * 本 spec 覆盖任务书 §三 的机械判据：
 *   ① 落盘=渲染源原文（sha256 相等；盘上文件不含 `/api/nf-file?path=` 与 `data:`）
 *   ② 改写闭环（`Edit` 盘上文件 ⇒ 以该路径再 Card ⇒ payload `html` 含改后标记）
 *   ④ XOR 拦截（`html` + `filePath` 同给 ⇒ `Left`，文案含 `mutually exclusive`）
 *   ⑤ 读盘判据（扩展名 / 2MB / 判据不可得 fail-closed / 两个可读根放行 / 根外拒）
 *   ⑥ 落盘失败降级（卡片照常渲染、`persist.ok=false`、无异常抛出）
 *   ⑦ dataRoot 隔离（落盘与设计提示词都跟随换根；real-HOME 零新增）
 *
 * 🔴 离线纪律：零实例、零网络、零 `:8080`、零 real-HOME 写入。每个用例把
 * `PathUtil.dataRoot` 换到临时目录并在收尾复原（形态先例 `CardModelFaceSpec:65-78`）。
 * 判据不可得（端口未接线）那条用「会抛异常的端口」钉同一段 catch —— `port` 未接线时
 * 抛的也是 `IllegalStateException`，与被测分支逐字同路。
 */
class CardPersistSpec extends FunSuite:

  // Phase 5 解耦接线:FileRefs 的端点判据窄端口(生产在 GatewayMain 装配;spec 自接线)。
  FilePolicyPort.install(NfFilePolicy)

  private val sentinel = "___CARD_HTML___"

  private var savedRoot: os.Path = scala.compiletime.uninitialized
  private var isolatedRoot: Path = null
  private var outsideRoot: Path = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = nebflow.shared.PathUtil.dataRoot
    isolatedRoot = Files.createTempDirectory("card-persist-")
    nebflow.shared.PathUtil.setDataRoot(os.Path(isolatedRoot, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    // A read-only fixture may still hold the directory; restore the mode first.
    val cards = isolatedRoot.resolve("cards")
    if Files.isDirectory(cards) then
      try Files.setPosixFilePermissions(cards, PosixFilePermissions.fromString("rwxr-xr-x"))
      catch case _: Throwable => ()
    nebflow.shared.PathUtil.setDataRoot(savedRoot)
    deleteTree(isolatedRoot)
    if outsideRoot != null then deleteTree(outsideRoot)
    outsideRoot = null
    super.afterEach(context)

  private def deleteTree(p: Path): Unit =
    if p != null && Files.exists(p) then
      Files
        .walk(p)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(x =>
          try Files.deleteIfExists(x)
          catch case _: Throwable => ()
        )

  private def ctx = ToolContext(projectRoot = os.pwd.toString)

  private def callCard(input: JsonObject): Either[ToolError, String] =
    CardTool.call(input, ctx).unsafeRunSync()

  private def payloadOf(result: String): Json =
    assert(result.startsWith(sentinel), s"payload must start with the sentinel: ${result.take(60)}")
    io.circe.parser
      .parse(result.substring(sentinel.length))
      .fold(e => fail(s"payload must be pure JSON: $e"), identity)

  private def sha256Of(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  private def sortedCards: List[Path] =
    val dir = isolatedRoot.resolve("cards")
    if !Files.isDirectory(dir) then Nil
    else
      val s = Files.list(dir)
      try s.iterator().asScala.toList.sortBy(_.getFileName.toString)
      finally s.close()

  /** The name discipline A2 pins: `yyyyMMdd_HHmmss_<slug>[-<n>].html`. */
  private val CardFileNameRe = """^\d{8}_\d{6}_[A-Za-z0-9._-]{1,60}(-\d+)?\.html$""".r

  private def writeSource(name: String, content: String): Path =
    val p = isolatedRoot.resolve(name)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  // ── ① 落盘 = 渲染源原文 ────────────────────────────────────────────────

  test("① the persisted file is the render source VERBATIM (sha256 equal, no rewrite)") {
    // The referenced image sits in data-root `plots/`, so the RENDER leg really
    // rewrites it (an out-of-root path would only be reported, not rewritten).
    val png = writeSource("plots/shot.png", "not-a-real-png")
    val html = s"""<div class="wrap"><h1>Report</h1><img src="$png"/><p>body</p></div>"""
    val result = callCard(JsonObject("html" -> Json.fromString(html), "title" -> Json.fromString("Report")))
    val payload = payloadOf(result.getOrElse(fail("expected Right")))

    val persisted = sortedCards
    assertEquals(persisted.size, 1, s"exactly one card file, got $persisted")
    val file = persisted.head
    assert(CardFileNameRe.matches(file.getFileName.toString), s"name discipline: ${file.getFileName}")

    val bytes = Files.readAllBytes(file)
    assertEquals(
      sha256Of(bytes),
      sha256Of(html.getBytes(StandardCharsets.UTF_8)),
      "the persisted bytes must be the SUPPLIED html, before embedLocalFiles rewrote anything"
    )
    val onDisk = new String(bytes, StandardCharsets.UTF_8)
    // A3's second half: a rewritten artifact would carry either of these.
    assert(!onDisk.contains("/api/nf-file?path="), s"the saved file must not carry a proxy URL: $onDisk")
    assert(!onDisk.contains("data:"), s"the saved file must not carry an inline data: URI: $onDisk")
    assert(onDisk.contains(s"""<img src="$png"/>"""), "the original reference spelling survives verbatim")

    // …and the reading says so.
    val persist = payload.hcursor.downField("persist")
    assertEquals(persist.get[Boolean]("ok").toOption, Some(true))
    assertEquals(
      persist.get[String]("path").toOption.map(p => Paths.get(p).toRealPath()),
      Some(file.toRealPath()),
      "persist.path must be the absolute path of the file that was written"
    )
    // The card itself still rendered the rewritten body: the persisted bytes and
    // the rendered bytes are two different things (that is the whole point).
    val rendered = payload.hcursor.get[String]("html").toOption.getOrElse(fail("payload html"))
    assertNotEquals(rendered, onDisk, "the RENDERED payload must be the rewritten body, not the source")
  }

  test("① a filePath render also persists the file's own bytes, verbatim") {
    val title = "From Disk"
    val src = writeSource("source.html", "<div>from disk</div>")
    val result = callCard(
      JsonObject("filePath" -> Json.fromString(src.toString), "title" -> Json.fromString(title))
    )
    val payload = payloadOf(result.getOrElse(fail("expected Right")))
    assertEquals(
      payload.hcursor.get[String]("html").toOption,
      Some("<div>from disk</div>"),
      "the file's content became the card HTML"
    )
    // The filePath leg does NOT re-copy its own source: the saved artifact is
    // this call's render source, and there is exactly one of it.
    val persisted = sortedCards
    assertEquals(persisted.size, 1, s"one saved card, not a copy of the source: $persisted")
    val saved = persisted.head
    assert(saved.getFileName.toString.endsWith("_FromDisk.html"), s"named after the title: $saved")
    assert(CardFileNameRe.matches(saved.getFileName.toString), s"name discipline: ${saved.getFileName}")
    assertEquals(new String(Files.readAllBytes(saved), StandardCharsets.UTF_8), "<div>from disk</div>")
    assertEquals(
      payload.hcursor.downField("persist").get[String]("path").toOption.map(p => Paths.get(p).toRealPath()),
      Some(saved.toRealPath())
    )
  }

  test("① A2: a second render in the same second writes a SECOND file (never overwrites)") {
    val first = "first"
    val second = "second"
    callCard(JsonObject("html" -> Json.fromString(first), "title" -> Json.fromString("Same")))
    callCard(JsonObject("html" -> Json.fromString(second), "title" -> Json.fromString("Same")))
    val files = sortedCards
    assertEquals(files.size, 2, s"two renders must leave two files: $files")
    files.foreach(f => assert(CardFileNameRe.matches(f.getFileName.toString), s"name: ${f.getFileName}"))
    val bodies = files.map(f => new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).sorted
    assertEquals(bodies, List("first", "second"), "the first file must not have been overwritten")
  }

  test("① slug comes from the title; a title with no usable characters falls back to `card`") {
    callCard(JsonObject("html" -> Json.fromString("<div>x</div>"), "title" -> Json.fromString("报告 ✓")))
    val name = sortedCards.head.getFileName.toString
    assert(name.endsWith("_card.html"), s"unusable title must fall back to the `card` slug: $name")
  }

  // ── ② 改写闭环 ──────────────────────────────────────────────────────────

  test("② edit the saved file, render that path: the payload carries the edited marker") {
    val marker = "EDITED-BY-THE-AUTHOR-b41c"
    callCard(JsonObject("html" -> Json.fromString("<div>original</div>"), "title" -> Json.fromString("Loop")))
    val saved = sortedCards.head
    val edited = new String(Files.readAllBytes(saved), StandardCharsets.UTF_8).replace("original", marker)
    Files.write(saved, edited.getBytes(StandardCharsets.UTF_8))

    val result = callCard(
      JsonObject("filePath" -> Json.fromString(saved.toString), "title" -> Json.fromString("Loop"))
    )
    val payload = payloadOf(result.getOrElse(fail("expected Right")))
    assert(
      payload.hcursor.get[String]("html").toOption.exists(_.contains(marker)),
      s"the re-render must carry the edited bytes: ${payload.hcursor.get[String]("html").toOption}"
    )
  }

  // ── ④ XOR 拦截 ──────────────────────────────────────────────────────────

  test("④ html + filePath together is refused with a `mutually exclusive` message") {
    val file = writeSource("both.html", "<div>x</div>")
    val result = callCard(
      JsonObject(
        "html" -> Json.fromString("<div>inline</div>"),
        "filePath" -> Json.fromString(file.toString)
      )
    )
    val error = result.swap.toOption.getOrElse(fail(s"expected Left, got: $result"))
    assert(error.message.contains("mutually exclusive"), s"verbatim wording required: ${error.message}")
    // Neither leg ran: no card, and nothing was persisted for this call.
    assertEquals(sortedCards, Nil, "a refused call must not persist anything")
  }

  test("④ neither parameter is refused, and the message names the new parameter too") {
    val error = callCard(JsonObject.empty).swap.toOption.getOrElse(fail("expected Left"))
    assert(error.message.contains("html"), s"the message must still name `html`: ${error.message}")
    assert(error.message.contains("filePath"), s"the message must name `filePath` too: ${error.message}")
  }

  // ── ⑤ 读盘判据 ──────────────────────────────────────────────────────────

  test("⑤ a non-html/htm extension is refused with a readable reason") {
    val file = writeSource("notes.txt", "<div>x</div>")
    val error = callCard(JsonObject("filePath" -> Json.fromString(file.toString))).swap.toOption
      .getOrElse(fail("expected Left"))
    assert(error.message.contains("'.txt' is not a renderable card source"), s"reason: ${error.message}")
    assert(error.message.contains(".html"), s"the reason must name what IS accepted: ${error.message}")
  }

  test("⑤ a source above 2MB is refused") {
    val big = isolatedRoot.resolve("big.html")
    Files.write(big, new Array[Byte](2 * 1024 * 1024 + 1))
    val error = callCard(JsonObject("filePath" -> Json.fromString(big.toString))).swap.toOption
      .getOrElse(fail("expected Left"))
    assert(error.message.contains("2MB render-source limit"), s"reason: ${error.message}")
  }

  test("⑤ a file outside the data root and the project .nebflow is refused") {
    outsideRoot = Files.createTempDirectory("card-persist-outside-")
    val file = outsideRoot.resolve("x.html")
    Files.write(file, "<div>outside</div>".getBytes(StandardCharsets.UTF_8))
    val error = callCard(JsonObject("filePath" -> Json.fromString(file.toString))).swap.toOption
      .getOrElse(fail("expected Left"))
    assert(
      error.message.contains("outside the locations this server reads card sources from"),
      s"reason: ${error.message}"
    )
  }

  test("⑤ a missing file and a directory are refused") {
    val missing = isolatedRoot.resolve("nope.html")
    assert(
      callCard(JsonObject("filePath" -> Json.fromString(missing.toString))).swap.toOption.exists(
        _.message.contains("no file at")
      ),
      "a missing path must report the absent file"
    )
    val dir = isolatedRoot.resolve("a-directory.html")
    Files.createDirectories(dir)
    assert(
      callCard(JsonObject("filePath" -> Json.fromString(dir.toString))).swap.toOption.exists(
        _.message.contains("not a regular file")
      ),
      "a directory carrying an .html name must be refused"
    )
  }

  test("⑤ the data root and the project .nebflow are the two readable roots") {
    val underRoot = writeSource("in-root.html", "<div>root</div>")
    val rootResult = callCard(JsonObject("filePath" -> Json.fromString(underRoot.toString)))
    assert(rootResult.isRight, s"<dataRoot>/x.html must be readable: $rootResult")

    val project = Paths.get(os.pwd.toString).resolve(".nebflow")
    val underProject = project.resolve("card-persist-spec-x.html")
    Files.createDirectories(project)
    Files.write(underProject, "<div>project</div>".getBytes(StandardCharsets.UTF_8))
    try
      val projectResult = callCard(JsonObject("filePath" -> Json.fromString(underProject.toString)))
      assert(projectResult.isRight, s"project .nebflow/x.html must be readable: $projectResult")
    finally Files.deleteIfExists(underProject)
  }

  test("⑤ a credential-shaped path is refused even under a readable root, without echoing content") {
    // `.ssh` is a credential path SEGMENT (NfCredentialPathSegments) and needs
    // no existing file to be recognized — the identity layer answers on the
    // path's own shape.
    outsideRoot = Files.createTempDirectory("card-persist-ssh-")
    val sshDir = outsideRoot.resolve(".ssh")
    Files.createDirectories(sshDir)
    val secret = sshDir.resolve("id_rsa.html")
    Files.write(secret, "PRIVATE-KEY-BODY-MUST-NOT-LEAK".getBytes(StandardCharsets.UTF_8))

    val error = callCard(JsonObject("filePath" -> Json.fromString(secret.toString))).swap.toOption
      .getOrElse(fail("expected Left"))
    assert(
      error.message.contains("credential"),
      s"the credential identity layer must refuse: ${error.message}"
    )
    assert(
      !error.message.contains("PRIVATE-KEY-BODY-MUST-NOT-LEAK"),
      "the refusal must never echo the file's content"
    )
  }

  test("⑤ a hard link to a credential file's inode is refused (the identity layer again)") {
    // The endpoint's inode snapshot covers the data root's `auth.json`; a card
    // source that is a hard link to it must be refused however it is named.
    val auth = isolatedRoot.resolve("auth.json")
    Files.write(auth, "{fake}".getBytes(StandardCharsets.UTF_8))
    // Rebuild the snapshot so the fixture's inode is in it (the judge is
    // produced lazily per root pair and this spec just changed the root).
    val link = writeSource("plots/innocent.html", "linked")
    Files.delete(link)
    try Files.createLink(link, auth)
    catch case _: Throwable => fail("this filesystem does not support hard links")

    val error = callCard(JsonObject("filePath" -> Json.fromString(link.toString))).swap.toOption
      .getOrElse(fail("expected Left"))
    assert(
      error.message.contains("credential-hardlink") || error.message.contains("hard link"),
      s"the inode identity layer must refuse: ${error.message}"
    )
  }

  test("⑤ an unusable credential judge refuses the render (fail-closed, not fail-open)") {
    val file = writeSource("judge.html", "<div>judged</div>")
    try
      FilePolicyPort.install(new FilePolicyPort:
        def endpointVerdictLayer(real: Path): Option[(FilePolicyPort.NfDenyLayer, String, String)] =
          throw new IllegalStateException("no judge wired")
        def credentialInodeHit(real: Path): Boolean = throw new IllegalStateException("no judge wired")
      )
      val error = callCard(JsonObject("filePath" -> Json.fromString(file.toString))).swap.toOption
        .getOrElse(fail("a judge that cannot be consulted must refuse, never allow"))
      assert(
        error.message.contains("credential judge could not be consulted"),
        s"fail-closed reason: ${error.message}"
      )
    finally FilePolicyPort.install(NfFilePolicy)
  }

  test("⑤ html-only rendering does not consult the credential judge at all") {
    try
      FilePolicyPort.install(new FilePolicyPort:
        def endpointVerdictLayer(real: Path): Option[(FilePolicyPort.NfDenyLayer, String, String)] =
          throw new IllegalStateException("no judge wired")
        def credentialInodeHit(real: Path): Boolean = throw new IllegalStateException("no judge wired")
      )
      val result = callCard(JsonObject("html" -> Json.fromString("<div>inline</div>")))
      assert(result.isRight, s"the html leg is unchanged and must not need the judge: $result")
    finally FilePolicyPort.install(NfFilePolicy)
  }

  // ── ⑥ 落盘失败降级 ──────────────────────────────────────────────────────

  test("⑥ a card still renders when `cards/` cannot be created (persist.ok=false)") {
    // Deterministic, permission-independent obstacle: the name `cards/` is
    // already taken by a regular file, so createDirectories must fail.
    Files.write(isolatedRoot.resolve("cards"), "occupied".getBytes(StandardCharsets.UTF_8))
    val result = callCard(JsonObject("html" -> Json.fromString("<div>still renders</div>")))
    val payload = payloadOf(result.getOrElse(fail("a failed persistence must never turn the card into a Left")))
    assertEquals(payload.hcursor.get[String]("html").toOption, Some("<div>still renders</div>"))
    val persist = payload.hcursor.downField("persist")
    assertEquals(persist.get[Boolean]("ok").toOption, Some(false), s"persist reading: $persist")
    assert(persist.get[String]("reason").toOption.exists(_.nonEmpty), "a failure must name a reason")
  }

  test("⑥ a card still renders when `cards/` is not writable (persist.ok=false)") {
    val cards = isolatedRoot.resolve("cards")
    Files.createDirectories(cards)
    Files.setPosixFilePermissions(cards, PosixFilePermissions.fromString("r-xr-xr-x"))
    try
      // Precondition (non-vacuous): the directory really refuses a write here.
      val probe =
        try
          Files.write(cards.resolve("probe.html"), "x".getBytes(StandardCharsets.UTF_8))
          true
        catch case _: Throwable => false
      assume(!probe, "running with privileges that ignore directory permissions — case skipped")
      if probe then Files.deleteIfExists(cards.resolve("probe.html"))

      val result = callCard(JsonObject("html" -> Json.fromString("<div>readonly</div>")))
      val payload = payloadOf(result.getOrElse(fail("expected Right")))
      assertEquals(payload.hcursor.get[String]("html").toOption, Some("<div>readonly</div>"))
      assertEquals(payload.hcursor.downField("persist").get[Boolean]("ok").toOption, Some(false))
    finally Files.setPosixFilePermissions(cards, PosixFilePermissions.fromString("rwxr-xr-x"))
  }

  // ── ⑦ dataRoot 隔离 ─────────────────────────────────────────────────────

  test("⑦ nothing is written outside the isolated data root") {
    val realCards = Paths.get(sys.props("user.home"), ".nebflow", "cards")
    val before = if Files.isDirectory(realCards) then listNames(realCards) else Set.empty[String]

    callCard(JsonObject("html" -> Json.fromString("<div>isolated</div>"), "title" -> Json.fromString("Iso")))
    val result = callCard(JsonObject("filePath" -> Json.fromString(writeSource("s.html", "<div>s</div>").toString)))
    assert(result.isRight, s"expected Right: $result")

    assert(Files.isDirectory(isolatedRoot.resolve("cards")), "cards/ must live under the isolated root")
    assert(sortedCards.nonEmpty, "files must land under <isolated>/cards/")

    val after = if Files.isDirectory(realCards) then listNames(realCards) else Set.empty[String]
    assertEquals(after, before, s"the real data root must gain nothing: $realCards")
  }

  test("⑦ the design prompt is read from the data root in force, not a hardcoded home") {
    val realPrompt = Paths.get(sys.props("user.home"), ".nebflow", "card-design-prompt.md")
    val realMtimeBefore = if Files.exists(realPrompt) then Some(Files.getLastModifiedTime(realPrompt)) else None

    val isolatedPrompt = isolatedRoot.resolve("card-design-prompt.md")
    assert(!Files.exists(isolatedPrompt), "precondition: the isolated root starts without the prompt")

    // `description` is the public face of the private loader.
    val described = CardTool.description
    assert(described.nonEmpty)
    assert(Files.exists(isolatedPrompt), "the prompt must be auto-created under the ISOLATED data root")

    val realMtimeAfter = if Files.exists(realPrompt) then Some(Files.getLastModifiedTime(realPrompt)) else None
    assertEquals(realMtimeAfter, realMtimeBefore, "the real home's prompt must not be touched")
  }

  private def listNames(dir: Path): Set[String] =
    val s = Files.list(dir)
    try s.iterator().asScala.map(_.getFileName.toString).toSet
    finally s.close()

end CardPersistSpec
