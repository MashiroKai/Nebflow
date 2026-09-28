package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.bridge.{BridgeContext, BridgeManager, BridgePlugin}
import nebflow.shared.SessionMeta

import java.nio.file.attribute.PosixFilePermissions

/** The config→verify→activate closed loop, pinned offline (feishubridge batch,
  * 2026-09-25; upstream plan card `20260925_195206_socchannel-plan2` §4).
  *
  * The loop's ONE activation point is [[FeishuBridgePlugin.sync]]: config
  * `enabled` ∧ mechanical verification ⇒ the adapter is registered with the
  * REAL [[BridgeManager]] (and started exactly once); anything else ⇒ it is
  * not (and a previously registered adapter is stopped and removed). The
  * `factory` seam keeps the socket-opening plugin out of the test — the fake
  * records lifecycle calls instead, so "registered" here means exactly "the
  * REST probe face would answer adapterRegistered=true for it".
  *
  * Verification semantics under test: every REQUIRED field present and valid —
  * a plain field matching its pattern, a secret field with a stored `_ref`
  * whose probe triple (exists/modeOk/readable) is all true. The spec writes
  * only its own temporary data root; the real ~/.nebflow is never touched.
  */
class FeishuAdapterActivationSpec extends CatsEffectSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-feishu-act-")

  /** A valid-looking card save: required plain fields + both required secrets. */
  private def fullBody(): Json =
    io.circe.parser.parse(
      """{"enabled":true,"fields":{"app_id":"cli_0123456789abcdef","region":"feishu",
        |"app_secret":"test-plain-secret","verification_token":"test-plain-vt"}}""".stripMargin
    ).toOption.get

  private def body(json: String): Json = io.circe.parser.parse(json).toOption.get

  private val noopCtx: BridgeContext = new BridgeContext:
    def injectMessage(sessionId: String, content: String, senderId: Option[String]): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(Nil)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  /** Lifecycle-recording fake: the offline stand-in for the real adapter. */
  private final class FakePlugin(starts: Ref[IO, Int], stops: Ref[IO, Int]) extends BridgePlugin:
    def name: String = FeishuBridgePlugin.Name
    def start(ctx: BridgeContext): IO[Unit] = starts.update(_ + 1).void
    val stop: IO[Unit] = stops.update(_ + 1).void
    def onAgentEvent(sessionId: String, event: Json): IO[Unit] = IO.unit

  private def fakeFactory(starts: Ref[IO, Int], stops: Ref[IO, Int]): os.Path => BridgePlugin =
    _ => FakePlugin(starts, stops)

  private def newCounters: (Ref[IO, Int], Ref[IO, Int]) =
    (Ref.unsafe[IO, Int](0), Ref.unsafe[IO, Int](0))

  private def posixOnly(): Unit =
    assume(
      !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName),
      "POSIX-only probe readback (modeOk must be true for the verified leg)"
    )

  private def narrowSecret(path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write(path, "x", createFolders = true)
    java.nio.file.Files.setPosixFilePermissions(
      path.toNIO, PosixFilePermissions.fromString("rw-------"))

  // ───────────────────────────── the gate ─────────────────────────────

  test("ACT-1 nothing configured ⇒ sync registers nothing (phase-1 shape untouched)") {
    val (starts, stops) = newCounters
    for
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, tmpRoot(), fakeFactory(starts, stops))
      names <- manager.registeredNames
    yield assertEquals(names, Set.empty[String])
  }

  test("ACT-2 enabled ∧ verified ⇒ registered and started once; probe/channels faces answer the live truth") {
    posixOnly()
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      verified <- IO(SocialChannels.verified(root, "feishu"))
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      names <- manager.registeredNames
      startsN <- starts.get
      // the REST faces with the LIVE set: the flip is real
      probeLive = SocialChannels.probeJson(root, "feishu", names)
      channelsLive = SocialChannels.channelsJson(root, names)
      // the default-arg calls keep the phase-1 answer (backward compatibility)
      probeDefault = SocialChannels.probeJson(root, "feishu")
    yield
      assert(verified, "a fully saved card must verify")
      assertEquals(names, Set("feishu"))
      assertEquals(startsN, 1)
      assertEquals(probeLive.toOption.get.hcursor.downField("adapterRegistered").as[Boolean].toOption, Some(true))
      assertEquals(
        channelsLive.hcursor.downField("channels").downField("feishu").downField("adapterRegistered").as[Boolean].toOption,
        Some(true))
      assertEquals(probeDefault.toOption.get.hcursor.downField("adapterRegistered").as[Boolean].toOption, Some(false))
  }

  test("ACT-3 enabled but NOT verified (required secret absent) ⇒ NOT registered — the flip cannot be bought with config alone") {
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu",
        body("""{"enabled":true,"fields":{"app_id":"cli_0123456789abcdef","region":"feishu"}}""")))
      verified <- IO(SocialChannels.verified(root, "feishu"))
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      names <- manager.registeredNames
      startsN <- starts.get
    yield
      assert(!verified, "missing required secrets must fail verification")
      assertEquals(names, Set.empty[String], "unverified config must not flip the adapter")
      assertEquals(startsN, 0)
  }

  test("ACT-4 disabled ⇒ unregistered again (toggle off tears the adapter down)") {
    posixOnly()
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      before <- manager.registeredNames
      _ <- IO(SocialChannels.save(root, "feishu",
        body("""{"enabled":false,"fields":{}}"""))) // surgical merge keeps the fields
      enabled <- IO(SocialChannels.isEnabled(root, "feishu"))
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      after <- manager.registeredNames
      stopsN <- stops.get
    yield
      assertEquals(before, Set("feishu"))
      assert(!enabled, "the toggle must be off")
      assertEquals(after, Set.empty[String], "disabled ⇒ the adapter is torn down and removed")
      assert(stopsN >= 1, "teardown must go through the plugin's stop")
  }

  test("ACT-5 a pattern-violating app_id fails verification even when secrets are readable (hand-edited config)") {
    posixOnly()
    val root = tmpRoot()
    // save() refuses to STORE a pattern violation, so this arrives the only way
    // it can in production: a hand-edited nebflow.json. The gate must still
    // reject it. Note the stored refs use the RENDERED root form (a non-default
    // data root renders absolute, not ~/) — resolveRef's exact convention.
    val secretRef = (root / "secrets" / "social-feishu-app-secret").toString
    val vtRef = (root / "secrets" / "social-feishu-verification-token").toString
    narrowSecret(root / "secrets" / "social-feishu-app-secret")
    narrowSecret(root / "secrets" / "social-feishu-verification-token")
    val cfg = io.circe.Json.obj(
      "socialChannels" -> io.circe.Json.obj(
        "version" -> Json.fromInt(1),
        "channels" -> io.circe.Json.obj(
          "feishu" -> io.circe.Json.obj(
            "enabled" -> Json.fromBoolean(true),
            "fields" -> io.circe.Json.obj(
              "app_id" -> Json.fromString("nope"),
              "region" -> Json.fromString("feishu"),
              "app_secret_ref" -> Json.fromString(secretRef),
              "verification_token_ref" -> Json.fromString(vtRef)
            )
          )
        )
      )
    )
    for
      _ <- IO(os.write(nebflow.shared.PathUtil.configJsonWritePath(root), cfg.noSpaces, createFolders = true))
      verified <- IO(SocialChannels.verified(root, "feishu"))
    yield assert(!verified, "a hand-edited pattern violation must not verify")
  }

  test("ACT-6 a secret file that vanished after saving fails verification (probe is live, not cached)") {
    posixOnly()
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      before <- IO(SocialChannels.verified(root, "feishu"))
      _ <- IO(os.remove(root / "secrets" / "social-feishu-app-secret"))
      after <- IO(SocialChannels.verified(root, "feishu"))
    yield
      assert(before, "precondition: the freshly saved card verifies")
      assert(!after, "a vanished credential must fail verification at the next gate read")
  }

  test("ACT-7 sync is stable under repetition: resync of a verified card re-registers without doubling") {
    posixOnly()
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      names <- manager.registeredNames
      startsN <- starts.get
      stopsN <- stops.get
    yield
      assertEquals(names, Set("feishu"), "re-sync must never duplicate the plugin entry")
      assertEquals(stopsN, 1, "the previous instance is stopped exactly once per resync")
      assertEquals(startsN, 2, "each resync starts a fresh instance (config may BE the credentials)")
  }

  // ─────────── A1-boot (feishu-boot-seal rework): the boot leg rewrites nothing ───────────

  /** The `channels.feishu` subtree, canonicalised identically on both sides of a
    * measurement — the criterion's object (A1). */
  private def feishuSubtree(root: os.Path): String =
    SocialChannels.readChannels(root).hcursor
      .downField("channels").downField("feishu").focus.getOrElse(Json.obj()).noSpaces

  private def sha256(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
      .map("%02x".format(_)).mkString

  private def configBytes(root: os.Path): String = os.read(nebflow.shared.PathUtil.configJsonWritePath(root))

  test("A1-boot golden: `enabled ∧ verified` ⇒ sync activates AND the feishu subtree's " +
    "canonical JSON hashes identically before/after the boot path (zero rewrite)") {
    // The criterion the brief states for state ①: the BOOT leg (the ONE activation
    // point, FeishuBridgePlugin.sync) is run to completion on a golden world
    // (enabled=true ∧ verified=true) and the channel subtree must not move a byte.
    // The `save` here is FIXTURE SETUP ONLY (the product's own write path) — the
    // measurement brackets `sync`, exactly as the criterion demands.
    //
    // Read-only face, declared honestly: the boot leg consists of `isEnabled` /
    // `verified` reads plus BridgeManager register/start, i.e. it has NO writer at
    // all (main carries zero call sites that write config from a boot校验 failure).
    // So this criterion cannot go red on its own and PASSES pre-change too; it is a
    // guard against the regression ever ACQUIRING a writer. The red tooth for the
    // same defect family lives at the writer that feeds this gate — A1-boot/red-tooth
    // below and SocialChannelsBootSealSpec.A5-a.
    posixOnly()
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      verified <- IO(SocialChannels.verified(root, "feishu"))
      enabled <- IO(SocialChannels.isEnabled(root, "feishu"))
      beforeBytes <- IO(configBytes(root))
      before <- IO(feishuSubtree(root))
      beforeSha = sha256(before)
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      names <- manager.registeredNames
      startsN <- starts.get
      afterBytes <- IO(configBytes(root))
      after <- IO(feishuSubtree(root))
      afterSha = sha256(after)
    yield
      assert(verified, "precondition: the fixture world is verified=true")
      assert(enabled, "precondition: the fixture world is enabled=true")
      // Anti-tautology: the compared object must be a real, non-empty subtree.
      assert(
        before.nonEmpty && before.contains("\"enabled\":true"),
        s"the fixture must carry a real feishu subtree, got '$before'"
      )
      assertEquals(names, Set("feishu"), "the boot leg must take the enabled ∧ verified arm")
      assertEquals(startsN, 1, "the adapter is started exactly once by the boot leg")
      assertEquals(after, before, "the subtree must be identical (same canonical JSON)")
      assertEquals(afterSha, beforeSha, "A1: feishu 子树规范化 JSON 的 sha256 前后相等（boot 路径零改写）")
      assertEquals(afterBytes, beforeBytes, "stronger: not one byte of the config file moves across the boot path")
  }

  test("A1-boot/red-tooth: a write with NO opinion about the switch must not close it — " +
    "the boot gate still activates (red pre-change)") {
    // The defect's reachable face at the boot boundary: the store is fed by a body
    // that says nothing about `enabled` (the shape that flipped the live channel at
    // 2026-09-28 18:00:06), and the NEXT boot leg then decides whether the adapter
    // may run. Pre-change the write materialises `false` (`getOrElse(false)`), so the
    // boot gate reads the channel as closed and never registers the adapter — the
    // live incident's mechanism (a silently closed channel is a channel that stops
    // coming up). A1's zero-rewrite criterion is measured on the same bracket.
    posixOnly()
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      _ <- IO(SocialChannels.save(root, "feishu", body("""{"fields":{"region":"lark"}}""")))
      enabled <- IO(SocialChannels.isEnabled(root, "feishu"))
      before <- IO(feishuSubtree(root))
      beforeSha = sha256(before)
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      names <- manager.registeredNames
      after <- IO(feishuSubtree(root))
      afterSha = sha256(after)
    yield
      assert(enabled, "缺 `enabled` 的写不得关闭通道（引擎禁代用户决定）：boot 门的输入必须仍是 true")
      assertEquals(names, Set("feishu"), "the boot gate must still activate the adapter")
      assertEquals(afterSha, beforeSha, "A1: 子树 sha256 前后相等（boot 路径零改写）")
      assertEquals(
        SocialChannels.readChannels(root).hcursor.downField("channels").downField("feishu")
          .downField("fields").downField("region").as[String].toOption,
        Some("lark"),
        "the field the body DID have an opinion about still lands (the write is not suppressed)"
      )
  }

  test("ACT-8 sync with a factory returning a non-feishu plugin instance completes — " +
    "the fingerprint guard is a no-op there (feishu-bind batch)") {
    // The lifecycle fakes are plain BridgePlugins, not FeishuBridgePlugin
    // instances, so the P0-2 guard appended to sync has nothing to read. Pinned:
    // the guard's type-test must never crash or alter the sync outcome for
    // such factories.
    posixOnly()
    val (starts, stops) = newCounters
    val root = tmpRoot()
    for
      _ <- IO(SocialChannels.save(root, "feishu", fullBody()))
      manager <- BridgeManager.create(noopCtx)
      _ <- FeishuBridgePlugin.sync(manager, root, fakeFactory(starts, stops))
      names <- manager.registeredNames
      startsN <- starts.get
    yield
      assertEquals(names, Set("feishu"), "the guard must not break the sync of a foreign plugin instance")
      assertEquals(startsN, 1, "the plugin is still started exactly once")
  }
