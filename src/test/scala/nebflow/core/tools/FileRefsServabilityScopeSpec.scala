package nebflow.core.tools

import io.circe.{Json, JsonObject}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.{Files, Path}

/**
 * imgref rework r2 (2026-09-18) — the two invariants the verifier's `fail` named
 * (`20260918_191232_imgref-verify-r2.md` §2 F1), pinned at the tool face:
 *
 *   **② 判据不随进程历史漂移** — `FileRefs.servableByEndpoint` reads the policy for
 *   the roots that are in force NOW (`NfPathPolicy.current()`), never the snapshot
 *   the JVM froze at its first call (`NfPathPolicy.memoized()`). Before this, the
 *   same file was judged against whichever data root happened to be seen first —
 *   which is why `CardModelFaceSpec` was green in one run order and red in another.
 *
 *   **① 内联腿的守门作用域** — the endpoint's ladder answers TWO different
 *   questions and only one of them is a question the inline (`data:`) leg ever
 *   asks: `Namespace` ("which subtrees may the /api/nf-file ENDPOINT serve from")
 *   is about the endpoint's **reach**, while `Credential` / `CredentialInode`
 *   ("this file IS a credential") and `FileType` (the real path's extension) are
 *   about the **file**. The inline leg embeds the bytes itself and asks the
 *   endpoint for nothing, so it honours the identity layers and not the reach
 *   layer — the shipped behaviour the author's order ("让本地件成功率高一点")
 *   is about. One catch, pinned below: the endpoint's credential step runs FIRST
 *   and therefore *short-circuits over* the inode step, so a hard link to a
 *   credential file sitting in a namespace-refused directory reports the
 *   NAMESPACE layer — the inline leg must ask the inode layer itself
 *   (`FileRefs.credentialInodeClean`) or it would launder a credential into the
 *   payload.
 *
 * Fixtures: REAL PNGs written by `ImageIO` (dimensions + sha256 printed), never a
 * mock, never a 0-byte placeholder. `PathUtil.dataRoot` is redirected per test (and
 * restored), exactly like `CardModelFaceSpec` does — every test allocates its OWN
 * root directory so the per-root policy memo is exercised honestly, and no test
 * depends on the roots another test used.
 */
class FileRefsServabilityScopeSpec extends FunSuite:

  // Phase 5 解耦接线:FileRefs 的端点判据窄端口(生产在 GatewayMain 装配;spec 自接线)。
  nebflow.core.FilePolicyPort.install(nebflow.gateway.NfFilePolicy)

  private val sentinel = "___CARD_HTML___"
  private val ctx = ToolContext(projectRoot = os.pwd.toString)

  private var savedRoot: os.Path = scala.compiletime.uninitialized
  private val made = scala.collection.mutable.ListBuffer.empty[Path]

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    super.beforeEach(context)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    made.foreach { d =>
      if Files.exists(d) then
        val s = Files.walk(d)
        try s.sorted(java.util.Comparator.reverseOrder()).forEach(x => Files.deleteIfExists(x))
        finally s.close()
    }
    made.clear()
    super.afterEach(context)

  // ── fixtures ──────────────────────────────────────────────────────────────

  /** A fresh root directory, owned by this test (deleted in `afterEach`). */
  private def freshRoot(label: String): Path =
    val d = Files.createTempDirectory(s"imgref-scope-$label-")
    made += d
    d

  /**
   * A REAL, browser-decodable PNG (ImageIO), pseudo-random so its `data:` URI is
   * far below the 40,000-char inline budget for these sizes.
   */
  private def writePng(path: Path, w: Int = 32, h: Int = 24, seed: Int = 11): Path =
    Files.createDirectories(path.getParent)
    val img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val rnd = new java.util.Random(seed.toLong)
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        img.setRGB(x, y, rnd.nextInt() & 0xffffff)
        x += 1
      y += 1
    javax.imageio.ImageIO.write(img, "png", path.toFile)
    path

  private def realOf(p: Path): Path = p.toRealPath()

  private def sha256(p: Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(Files.readAllBytes(p)).map("%02x".format(_)).mkString

  private def describeFixture(p: Path): String =
    val dims =
      Option(javax.imageio.ImageIO.read(p.toFile)).map(i => s"${i.getWidth}x${i.getHeight}").getOrElse("unreadable")
    s"${p} bytes=${Files.size(p)} dims=$dims sha256=${sha256(p)}"

  // ── Card harness (the tool face) ──────────────────────────────────────────

  private def card(html: String): Json =
    val input = JsonObject("html" -> Json.fromString(html), "title" -> Json.fromString("T"))
    val result = CardTool.call(input, ctx).unsafeRunSync().getOrElse(fail("expected Right"))
    assert(result.startsWith(sentinel), s"result must start with the sentinel, got: ${result.take(60)}")
    io.circe.parser.parse(result.substring(sentinel.length)).fold(err => fail(s"payload JSON: $err"), identity)

  private def htmlOf(p: Json): String = p.hcursor.get[String]("html").toOption.getOrElse("")
  private def warningsOf(p: Json): List[Json] = p.hcursor.get[List[Json]]("warnings").toOption.getOrElse(Nil)

  private def refs(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  private def reasons(p: Json): List[String] =
    warningsOf(p).flatMap(_.hcursor.get[String]("reason").toOption)

  private def details(p: Json): String =
    warningsOf(p).flatMap(_.hcursor.get[String]("detail").toOption).mkString(" | ")

  // ── ② the verdict follows the roots in force, never the first roots ────────

  test("current policy: the verdict follows the roots in force, never the roots this JVM saw first"):
    val rootA = freshRoot("drift-a")
    val rootB = freshRoot("drift-b")
    val top = writePng(rootA.resolve("top 1.png"))
    val served = writePng(rootA.resolve("plots/served 1.png"))
    val topReal = realOf(top)
    val servedReal = realOf(served)
    println(s"[SCOPE-READING] fixture(top)=${describeFixture(top)}")
    println(s"[SCOPE-READING] fixture(served)=${describeFixture(served)}")

    // In force: rootA ⇒ `top 1.png` sits at the data root's TOP LEVEL (outside every
    // served subtree) while `plots/**` is served.
    PathUtil.setDataRoot(os.Path(rootA))
    val aTop = FileRefs.servableByEndpointLayered(topReal)
    val aServed = FileRefs.servableByEndpointLayered(servedReal)
    println(s"[SCOPE-READING] rootA top=${aTop.map(t => (t._1, t._2))} served=${aServed.map(t => (t._1, t._2))}")
    assertEquals(aTop.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace))
    assertEquals(aTop.map(_._2), Some("credential-path"))
    assertEquals(aServed, None, "plots/** is served from this data root")

    // In force: rootB (a different, empty root) ⇒ the very same files are OUTSIDE
    // the data root and the project .nebflow, so the endpoint would serve them.
    // A policy memoized at the first call would still be answering for rootA here.
    PathUtil.setDataRoot(os.Path(rootB))
    val bTop = FileRefs.servableByEndpointLayered(topReal)
    val bServed = FileRefs.servableByEndpointLayered(servedReal)
    println(s"[SCOPE-READING] rootB top=${bTop.map(t => (t._1, t._2))} served=${bServed.map(t => (t._1, t._2))}")
    assertEquals(bTop, None, "with another data root in force the same file is servable again (no history freeze)")
    assertEquals(bServed, None)

    // …and back: the verdict is a function of the roots in force, not of the order
    // in which the process happened to meet them.
    PathUtil.setDataRoot(os.Path(rootA))
    val again = FileRefs.servableByEndpointLayered(topReal)
    println(s"[SCOPE-READING] rootA again top=${again.map(t => (t._1, t._2))}")
    assertEquals(again.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace))

  // ── single source: the gate IS the endpoint's own ladder ──────────────────

  test("single source: every refusal carries the layer and message of the endpoint's own ladder"):
    val root = freshRoot("single-source")
    val ext = freshRoot("single-source-external")
    val servedPng = writePng(root.resolve("plots/served 2.png"))
    val topPng = writePng(root.resolve("top 2.png"))
    val secretBin = root.resolve("secrets/secret.bin")
    Files.createDirectories(secretBin.getParent)
    Files.write(secretBin, "not-for-the-browser".getBytes("UTF-8"))
    val secretPng = writePng(root.resolve("secrets/shot 2.png"))
    val targetXyz = root.resolve("plots/target 2.xyz")
    Files.write(targetXyz, Files.readAllBytes(servedPng))
    val alias = root.resolve("plots/alias 2.png")
    Files.createSymbolicLink(alias, targetXyz)
    val sshDir = ext.resolve(".ssh")
    Files.createDirectories(sshDir)
    val sshPng = writePng(sshDir.resolve("shot 2.png"))

    PathUtil.setDataRoot(os.Path(root))
    val policy = nebflow.gateway.NfFilePolicy.NfPathPolicy.current()
    println(
      s"[SCOPE-READING] policy dataRoot=${policy.dataRoot} workspaceRoot=${policy.workspaceRoot} " +
        s"credentialInodes=${policy.credentialInodes.size}"
    )

    val expectations: List[(Path, Option[(nebflow.gateway.NfFilePolicy.NfDenyLayer, String)])] = List(
      realOf(servedPng) -> None,
      realOf(topPng) -> Some((nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace, "credential-path")),
      realOf(secretPng) -> Some((nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace, "credential-path")),
      realOf(alias) -> Some((nebflow.gateway.NfFilePolicy.NfDenyLayer.FileType, "file-type")),
      realOf(sshPng) -> Some((nebflow.gateway.NfFilePolicy.NfDenyLayer.Credential, "credential-path"))
    )

    expectations.foreach { (path, expected) =>
      val tool = FileRefs.servableByEndpointLayered(path).map(t => (t._1, t._2))
      // the endpoint's own function, with the policy the tool itself reads:
      // one judgement, two readers (`nfVerdictForReal` is the projection of
      // `nfVerdictForRealLayer`, so this equality also pins that neither drifts)
      val endpoint = nebflow.gateway.NfFilePolicy
        .nfVerdictForRealLayer(path, policy)
        .map((layer, denied) => (layer, denied.reason))
      // the two-tuple projection the URL leg reads must agree as well
      val urlLeg = FileRefs.servableByEndpoint(path).map(_._1)
      println(s"[SCOPE-READING] $path tool=$tool endpoint=$endpoint urlLeg=$urlLeg expected=$expected")
      assertEquals(tool, expected, s"the tool-side gate's layer/reason for $path")
      assertEquals(endpoint, expected, s"the endpoint's own ladder for $path")
      assertEquals(urlLeg, expected.map(_._2), s"the URL-leg projection for $path")
    }

  // ── ① the inline leg honours the reach layer … ────────────────────────────

  test("leg scope: a namespace-only refusal still embeds the bytes, and is still warned on the URL face"):
    val root = freshRoot("leg-scope")
    val img = writePng(root.resolve("shot 3.png"))
    val ref = realOf(img).toString
    PathUtil.setDataRoot(os.Path(root))
    println(s"[SCOPE-READING] fixture=${describeFixture(img)}")
    println(s"[SCOPE-READING] layered=${FileRefs.servableByEndpointLayered(realOf(img)).map(t => (t._1, t._2))}")

    // (a) RESOURCE face — the inline leg embeds the bytes: no request, no endpoint,
    //     so the reach layer is irrelevant. This is the SHIPPED capability the
    //     author's order asks for (and what `CardModelFaceSpec` C1 exercises).
    val asImage = card(s"""<img src="$ref"/>""")
    println(
      s"[SCOPE-READING] img-face fileRefs=${asImage.hcursor.downField("fileRefs").focus.map(_.noSpaces)} " +
        s"warnings=${warningsOf(asImage).map(_.noSpaces).mkString(",")} inline=${htmlOf(asImage).contains("data:image/png;base64,")}"
    )
    assertEquals(refs(asImage, "inlined"), 1, "a readable, non-credential image is embedded")
    assertEquals(refs(asImage, "proxied"), 0, "an embedded image emits no /api/nf-file URL")
    assertEquals(refs(asImage, "failed"), 0, "nothing failed: the bytes are in the payload")
    assertEquals(warningsOf(asImage), Nil)
    assert(htmlOf(asImage).contains("data:image/png;base64,"), "the payload carries the data: URI")
    assert(!htmlOf(asImage).contains("/api/nf-file"), "no URL was emitted for the embedded reference")

    // (b) URL face — the very same file referenced where only /api/nf-file could
    //     ever deliver it (a navigation reference is never embedded): the endpoint
    //     cannot serve that location, so it is REPORTED, never counted.
    val asLink = card(s"""<link rel="stylesheet" href="$ref"/>""")
    println(
      s"[SCOPE-READING] link-face fileRefs=${asLink.hcursor.downField("fileRefs").focus.map(_.noSpaces)} " +
        s"warnings=${warningsOf(asLink).map(_.noSpaces).mkString(",")}"
    )
    assertEquals(refs(asLink, "proxied"), 0, "an unretrievable URL must never be counted as proxied")
    assertEquals(refs(asLink, "inlined"), 0)
    assertEquals(refs(asLink, "failed"), 1)
    assertEquals(reasons(asLink), List("not-servable"))
    assert(details(asLink).contains("credential-bearing"), s"the refusal quotes the endpoint: ${details(asLink)}")
    assert(details(asLink).contains("move or copy the file"), s"…with an executable fix: ${details(asLink)}")
    assert(htmlOf(asLink).contains(ref), "the raw value stays in the markup (no blank rewrite)")

  // ── …and NOT the identity layers (incl. the endpoint's short-circuit) ─────

  test("identity layer: a hard link to a credential inode is never embedded, however the directory is named"):
    val root = freshRoot("identity-inode")
    val secret = root.resolve("secrets/secret.bin")
    Files.createDirectories(secret.getParent)
    Files.write(secret, "not-for-the-browser".getBytes("UTF-8"))
    val link = root.resolve("shot link 4.png")
    Files.createLink(link, secret)
    PathUtil.setDataRoot(os.Path(root))

    val linkReal = realOf(link)
    val layered = FileRefs.servableByEndpointLayered(linkReal).map(t => (t._1, t._2))
    println(s"[SCOPE-READING] hardlink=${linkReal} layered=$layered inodeClean=${FileRefs.credentialInodeClean(link)}")
    // The endpoint's credential step runs FIRST and reports the NAMESPACE layer for
    // this path, so the layer alone says "reach" — the inode answer is the one that
    // says "credential", and the inline leg must ask it separately.
    assertEquals(layered.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.Namespace))
    assertEquals(FileRefs.credentialInodeClean(link), false, "the inode layer must be consulted independently")

    // the reference is the LINK's own name (`shot link 4.png`): the endpoint
    // resolves the real path itself, and that real path is the credential file
    val p = card(s"""<img src="$link"/>""")
    println(
      s"[SCOPE-READING] fileRefs=${p.hcursor.downField("fileRefs").focus.map(_.noSpaces)} " +
        s"warnings=${warningsOf(p).map(_.noSpaces).mkString(",")}"
    )
    assertEquals(refs(p, "inlined"), 0, "a credential file's bytes must never ride in the payload")
    assertEquals(refs(p, "proxied"), 0)
    assertEquals(refs(p, "failed"), 1)
    assertEquals(reasons(p), List("not-servable"))
    assert(!htmlOf(p).contains("data:"), "no data: URI was produced")

  test("identity layer: a file under <dataRoot>/secrets/** is never embedded either"):
    val root = freshRoot("identity-secrets")
    val secretPng = writePng(root.resolve("secrets/shot 5.png"))
    PathUtil.setDataRoot(os.Path(root))
    println(s"[SCOPE-READING] fixture=${describeFixture(secretPng)}")
    println(
      s"[SCOPE-READING] layered=${FileRefs.servableByEndpointLayered(realOf(secretPng)).map(t => (t._1, t._2))} " +
        s"inodeClean=${FileRefs.credentialInodeClean(secretPng)}"
    )
    assertEquals(
      FileRefs.credentialInodeClean(secretPng),
      false,
      "everything under <dataRoot>/secrets/** is a credential inode"
    )
    val p = card(s"""<img src="${realOf(secretPng).toString}"/>""")
    assertEquals(refs(p, "inlined"), 0, "a readable image inside the credential directory is still a credential")
    assertEquals(refs(p, "proxied"), 0)
    assertEquals(refs(p, "failed"), 1)
    assertEquals(reasons(p), List("not-servable"))

  test("identity layer: the real path's extension (FileType) is honoured on the inline leg too"):
    val root = freshRoot("identity-filetype")
    val served = writePng(root.resolve("plots/real 6.png"))
    val target = root.resolve("plots/target 6.xyz")
    Files.write(target, Files.readAllBytes(served))
    val alias = root.resolve("plots/alias 6.png")
    Files.createSymbolicLink(alias, target)
    PathUtil.setDataRoot(os.Path(root))
    println(
      s"[SCOPE-READING] alias=${realOf(alias)} layered=${FileRefs.servableByEndpointLayered(realOf(alias)).map(t => (t._1, t._2))}"
    )
    // the reference is the SYMLINK's name (`alias 6.png`): its own extension is a
    // served image, and the refusal comes from the extension of the REAL path —
    // which is exactly the layer this test pins
    val p = card(s"""<img src="$alias"/>""")
    assertEquals(refs(p, "inlined"), 0, "a symlink cannot lend the embedded image its MIME by name")
    assertEquals(refs(p, "proxied"), 0)
    assertEquals(refs(p, "failed"), 1)
    assertEquals(reasons(p), List("not-servable"))
    assert(details(p).contains("file-type"), s"the refusal quotes the endpoint's own reason: ${details(p)}")
end FileRefsServabilityScopeSpec
