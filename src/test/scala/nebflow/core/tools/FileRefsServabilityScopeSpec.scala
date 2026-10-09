package nebflow.core.tools

import io.circe.{Json, JsonObject}
import cats.effect.IO
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

  // ── Pop harness (the tool face; pop-upgrade 批 2026-10-03 re-point: the
  //    media legs of PopTool are FileRefs' remaining consumer) ───────────────

  private val nebulaDef = nebflow.actor.AgentDef(name = "Nebula", description = "", tools = Nil)

  /** Pop ONE image path through the tool and return the parsed payload. */
  private def popImage(ref: String): Json =
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val popCtx = ToolContext(
      sessionId = Some("imgref-scope-test"),
      sessionStore = None,
      agentDef = Some(nebulaDef),
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = Some((j: Json) => IO { buf += j }),
      projectRoot = ""
    )
    val result = PopTool.call(JsonObject("filePath" -> Json.fromString(ref)), popCtx).unsafeRunSync()
      .getOrElse(fail("expected Right"))
    assert(result.startsWith(PopTool.Sentinel), s"payload sentinel expected: ${result.take(60)}")
    io.circe.parser.parse(result.substring(PopTool.Sentinel.length)).fold(err => fail(s"payload JSON: $err"), identity)

  private def warningsOf(p: Json): List[Json] = p.hcursor.get[List[Json]]("warnings").toOption.getOrElse(Nil)

  private def refs(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  private def reasons(p: Json): List[String] =
    warningsOf(p).flatMap(_.hcursor.get[String]("reason").toOption)

  private def details(p: Json): String =
    warningsOf(p).flatMap(_.hcursor.get[String]("detail").toOption).mkString(" | ")

  // ── ② the verdict follows the roots in force, never the first roots ────────

  test("current policy: the verdict follows the roots in force, never the roots this JVM saw first"):
    // 2026-10-07 flip: the namespace reach layer is retired, so the ROOT-DRIFT
    // probe can no longer ride a top-level data-root file (it serves under
    // every root now) — and since the root-anchored seed made
    // `<dataRoot>/auth.json` a credential BY IDENTITY, the basename is refused
    // under EVERY root (identity is root-free) and carries no drift readout
    // any more. The root-relative surface that remains is the R2 inode
    // snapshot (scanCredentialInodes(dataRoot)), and it is only visible
    // through an ALIAS: a hard link with an innocuous name shares auth.json's
    // (dev,ino) — refused as CredentialInode under the root in force, served
    // under any other root.
    val rootA = freshRoot("drift-a")
    val rootB = freshRoot("drift-b")
    val auth = Files.write(rootA.resolve("auth.json"), "\"t\"".getBytes("UTF-8"))
    val authReal = realOf(auth)
    Files.createDirectories(rootA.resolve("plots"))
    val alias = Files.createLink(rootA.resolve("plots/auth-alias.bin"), authReal)
    val aliasReal = realOf(alias)
    val plain = writePng(rootA.resolve("plots/plain.png"))
    val plainReal = realOf(plain)
    println(s"[SCOPE-READING] fixture(auth)=${describeFixture(auth)}")

    // In force: rootA ⇒ the alias IS a credential inode of this root; the
    // plain file and the alias's innocuous name are served/refused
    // respectively by the live layers.
    PathUtil.setDataRoot(os.Path(rootA))
    val aAuth = FileRefs.servableByEndpointLayered(authReal)
    val aAlias = FileRefs.servableByEndpointLayered(aliasReal)
    val aPlain = FileRefs.servableByEndpointLayered(plainReal)
    println(s"[SCOPE-READING] rootA auth=${aAuth.map(t => (t._1, t._2))} alias=${aAlias.map(t => (t._1, t._2))} plain=${aPlain.map(t => (t._1, t._2))}")
    // root-anchored seed (auth.json inside the data root) fires as Credential
    assertEquals(aAuth.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.Credential))
    assertEquals(aAlias.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.CredentialInode),
      "the alias's innocuous name passes identity; the root's own inode snapshot catches it")
    assertEquals(aPlain, None, "any credential-clean file is served from this data root (namespace layer retired)")

    // In force: rootB ⇒ the very same alias inode belongs to NO root in
    // force (rootB has its own, different inode snapshot) ⇒ served. A policy
    // memoized at the first call would still be answering for rootA here.
    PathUtil.setDataRoot(os.Path(rootB))
    val bAlias = FileRefs.servableByEndpointLayered(aliasReal)
    val bPlain = FileRefs.servableByEndpointLayered(plainReal)
    println(s"[SCOPE-READING] rootB alias=${bAlias.map(t => (t._1, t._2))} plain=${bPlain.map(t => (t._1, t._2))}")
    assertEquals(bAlias, None, "under another data root the alias is not a credential inode (no history freeze)")
    assertEquals(bPlain, None)

    // …and back: the verdict is a function of the roots in force, not of the
    // order in which the process happened to meet them.
    PathUtil.setDataRoot(os.Path(rootA))
    val again = FileRefs.servableByEndpointLayered(aliasReal)
    println(s"[SCOPE-READING] rootA again alias=${again.map(t => (t._1, t._2))}")
    assertEquals(again.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.CredentialInode))

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

    // 2026-10-07 flip expectations: the namespace reach layer and the FileType
    // layer no longer refuse — top-level files and extension-lent symlinks
    // SERVE; the remaining refusals are the R2 inode layer (secrets/*) and the
    // credential identity tables (.ssh trees).
    val expectations: List[(Path, Option[(nebflow.gateway.NfFilePolicy.NfDenyLayer, String)])] = List(
      realOf(servedPng) -> None,
      realOf(topPng) -> None, // namespace reach retired: top-level serves
      realOf(secretPng) -> Some((nebflow.gateway.NfFilePolicy.NfDenyLayer.Credential, "credential-path")),
      realOf(alias) -> None, // FileType retired: the symlink serves (its bytes are a real PNG)
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

  test("leg scope: a namespace-only refusal still embeds the bytes (the inline leg asks the endpoint nothing)"):
    val root = freshRoot("leg-scope")
    val img = writePng(root.resolve("shot 3.png"))
    val ref = realOf(img).toString
    PathUtil.setDataRoot(os.Path(root))
    println(s"[SCOPE-READING] fixture=${describeFixture(img)}")
    println(s"[SCOPE-READING] layered=${FileRefs.servableByEndpointLayered(realOf(img)).map(t => (t._1, t._2))}")

    // RESOURCE face — the inline leg embeds the bytes: no request, no endpoint,
    // so the reach layer is irrelevant. This is the SHIPPED capability the
    // author's order asks for ("让本地件成功率高一点"), now on the Pop payload face.
    val asImage = popImage(ref)
    println(
      s"[SCOPE-READING] img-face fileRefs=${asImage.hcursor.downField("fileRefs").focus.map(_.noSpaces)} " +
        s"warnings=${warningsOf(asImage).map(_.noSpaces).mkString(",")}"
    )
    assertEquals(refs(asImage, "inlined"), 1, "a readable, non-credential image is embedded")
    assertEquals(refs(asImage, "failed"), 0, "nothing failed: the bytes are in the payload")
    assertEquals(warningsOf(asImage), Nil)
    assert(
      asImage.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
        .exists(_.hcursor.get[String]("src").toOption.exists(_.startsWith("data:image/png;base64,"))),
      "the payload carries the data: URI"
    )

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
    // 2026-10-07 flip: the namespace reach layer is gone, and a hard link's
    // realpath is the LINK's OWN path (`<root>/shot link 4.png` — a hard link
    // shares the inode, not the name): neither the root-anchored seed nor the
    // root-free identity tables see anything credential-shaped about it. The
    // ONLY layer that can catch it is the R2 inode guard ⇒ CredentialInode
    // (exactly the aliasing the (dev,ino) snapshot exists for — realpath
    // cannot see a hard link).
    assertEquals(layered.map(_._1), Some(nebflow.gateway.NfFilePolicy.NfDenyLayer.CredentialInode))
    assertEquals(FileRefs.credentialInodeClean(link), false, "the inode layer must be consultable independently")

    // the reference is the LINK's own name (`shot link 4.png`): the endpoint
    // resolves the real path itself, and that real path is the credential file
    val p = popImage(link.toString)
    println(
      s"[SCOPE-READING] fileRefs=${p.hcursor.downField("fileRefs").focus.map(_.noSpaces)} " +
        s"warnings=${warningsOf(p).map(_.noSpaces).mkString(",")}"
    )
    assertEquals(refs(p, "inlined"), 0, "a credential file's bytes must never ride in the payload")
    assertEquals(refs(p, "failed"), 1)
    assertEquals(reasons(p), List("not-servable"))
    assert(
      !p.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
        .exists(_.hcursor.get[String]("src").toOption.exists(_.startsWith("data:"))),
      "no data: URI was produced"
    )

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
    val p = popImage(realOf(secretPng).toString)
    assertEquals(refs(p, "inlined"), 0, "a readable image inside the credential directory is still a credential")
    assertEquals(refs(p, "failed"), 1)
    assertEquals(reasons(p), List("not-servable"))

  test("FileType layer RETIRED (2026-10-07 flip): an extension-lent symlink embeds — the bytes are what render"):
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
    // The reference is the SYMLINK's name (`alias 6.png`); the REAL path's
    // extension is `.xyz`. Before the flip the endpoint's FileType layer
    // refused exactly this shape; with that layer retired the bytes (a real
    // PNG) ride and the file embeds — same judgment as the Pop/Canvas open
    // face, where an unregistered type downloads instead of refusing.
    val p = popImage(alias.toString)
    assertEquals(refs(p, "inlined"), 1, "the extension is no longer a refusal dimension")
    assertEquals(refs(p, "failed"), 0)
    assertEquals(warningsOf(p), Nil)
    assert(
      p.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
        .exists(_.hcursor.get[String]("src").toOption.exists(_.startsWith("data:image/png;base64,"))),
      "the payload carries the data: URI"
    )
end FileRefsServabilityScopeSpec
