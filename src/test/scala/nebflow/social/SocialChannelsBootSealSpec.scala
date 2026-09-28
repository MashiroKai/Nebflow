package nebflow.social

import io.circe.Json
import munit.FunSuite

/**
 * feishu-boot-seal (2026-09-28) — the STORED half of the boot-seal defect.
 *
 * Defect shape (pre-change `SocialChannels.save` step 4):
 *
 * {{{
 *   body.hcursor.downField("enabled").as[Boolean].getOrElse(false)
 * }}}
 *
 * i.e. a request body that did not MENTION `enabled` was read as "the user
 * turned the channel off" and `false` was written into `~/.nebflow/nebflow.json`
 * — the engine closing the user's switch on its own authority (observed live
 * 2026-09-28 18:00:06: a verified channel flipped to `enabled:false` with zero
 * log lines in the window; `findings` in the batch report).
 *
 * The invariants pinned here (the fix's own shape):
 *   · A5-a  a body WITHOUT `enabled` keeps the stored value — a save that has no
 *           opinion about the switch must not express one, and must not be
 *           readable afterwards as "the user closed this channel".
 *   · A5-b  an EXPLICIT `enabled:false` still lands `false` — the archive /
 *           explicit-close semantics are NOT part of the regression face.
 *   · A4    a channel that was never stored gains NO `enabled` key ("never
 *           stored" and "stored false" are different facts about the world; the
 *           reader conflates them on purpose, the writer must not).
 *   · A1    the golden path — an explicit `enabled:true` save — is a surgical
 *           rewrite: the switch is written and every other key of the file
 *           survives.
 *   · A3    the archive body (`{enabled:false, fields:{}}`) keeps the stored
 *           credential refs (surgical merge) and only flips the switch.
 *   · A2    the fingerprint reading is a STATUS, never a config writer: a
 *           stored-A-connected-B world reads `mismatch` and the stored switch
 *           is untouched by that reading.
 *
 * RED leg (fail-first): every assertion here is written against the pre-change
 * code by `git stash`ing the one changed source file — see the batch report for
 * both readings (pre-change red, post-change green).
 *
 * 🔴 Mutation safety: only this spec's own temporary data root is read or
 * written; the real `~/.nebflow` is never touched. No credential value is ever
 * involved — the `_ref` strings below are plain text paths that no code in this
 * spec resolves or reads.
 */
class SocialChannelsBootSealSpec extends FunSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-social-bootseal-")

  private def json(s: String): Json = io.circe.parser.parse(s).toOption.get

  /** The stored channel entry, exactly as the writer left it. */
  private def entry(root: os.Path, id: String): Json =
    SocialChannels.readChannels(root).hcursor.downField("channels").downField(id).focus
      .getOrElse(Json.obj())

  private def enabledOf(root: os.Path, id: String): Option[Boolean] =
    entry(root, id).hcursor.downField("enabled").as[Boolean].toOption

  /** `Some(true)` = the key is present and true; `None` = the key is ABSENT. */
  private def hasEnabledKey(root: os.Path, id: String): Boolean =
    entry(root, id).hcursor.downField("enabled").focus.isDefined

  private def fieldOf(root: os.Path, id: String, key: String): Option[String] =
    entry(root, id).hcursor.downField("fields").downField(key).as[String].toOption

  private def saved(root: os.Path, id: String, body: Json): Json =
    SocialChannels.save(root, id, body) match
      case Right(ok) => ok
      case Left(err) => fail(s"save($id) must succeed, got $err (body=${body.noSpaces})")

  /** A verified-looking feishu card: required plain fields present and valid. */
  private def verifiedBody(enabled: Option[Boolean]): Json =
    val switch = enabled.map(b => s""""enabled":$b,""").getOrElse("")
    json(s"""{$switch"fields":{"app_id":"cli_aa32140e8af85d25","region":"feishu"}}""")

  // ── A5-a — the fix: an absent `enabled` keeps the stored switch ──────────

  test("A5-a body WITHOUT `enabled` keeps the stored switch true and merges the fields (no close semantics)") {
    val root = tmpRoot()
    saved(root, "feishu", verifiedBody(Some(true)))
    assertEquals(enabledOf(root, "feishu"), Some(true), "precondition: the seed save enabled the channel")
    val stampBefore = entry(root, "feishu").hcursor.downField("updatedAt").as[Long].toOption
    assert(stampBefore.isDefined, "the writer must stamp updatedAt")

    // A save that says nothing about the switch (the shape a config-less client
    // emits): it must be read as "no opinion", never as "off".
    Thread.sleep(1100) // updatedAt is epoch-SECONDS; the refresh must be observable
    saved(root, "feishu", json("""{"fields":{"region":"lark"}}"""))

    assertEquals(
      enabledOf(root, "feishu"), Some(true),
      "缺键 ⇒ 保持现值（禁止 getOrElse(false)：该次调用不得产生「用户关通道」语义）"
    )
    assert(SocialChannels.isEnabled(root, "feishu"), "the activation gate still reads the channel as enabled")
    assertEquals(fieldOf(root, "feishu", "region"), Some("lark"), "present plain fields still merge")
    assertEquals(
      fieldOf(root, "feishu", "app_id"), Some("cli_aa32140e8af85d25"),
      "fields absent from the body survive the surgical merge"
    )
    val stampAfter = entry(root, "feishu").hcursor.downField("updatedAt").as[Long].toOption
    assert(
      stampAfter.exists(a => stampBefore.exists(b => a >= b)) && stampAfter != stampBefore,
      s"updatedAt must be refreshed by the write: $stampBefore -> $stampAfter"
    )
  }

  // ── A5-b — the explicit write is untouched (no regression) ──────────────

  test("A5-b an EXPLICIT `enabled:false` still lands false (explicit writes are honoured verbatim)") {
    val root = tmpRoot()
    saved(root, "feishu", verifiedBody(Some(true)))
    saved(root, "feishu", json("""{"enabled":false,"fields":{}}"""))
    assertEquals(enabledOf(root, "feishu"), Some(false), "显式 false ⇒ 落 false（不回归）")
    assertEquals(SocialChannels.isEnabled(root, "feishu"), false)
    // ...and an explicit true flips it back, on the same path.
    saved(root, "feishu", json("""{"enabled":true,"fields":{}}"""))
    assertEquals(enabledOf(root, "feishu"), Some(true), "显式 true ⇒ 落 true")
  }

  // ── A3 — archive semantics stay archive semantics ───────────────────────

  test("A3 the archive body keeps the stored credential refs and only flips the switch") {
    val root = tmpRoot()
    saved(root, "feishu", verifiedBody(Some(true)))
    // Seed a stored secret PATH the way writeSecret leaves one (a path string,
    // never a credential value — nothing here reads or resolves it).
    saved(root, "feishu", json("""{"fields":{"app_secret":"injected-plaintext"}}"""))
    val refBefore = fieldOf(root, "feishu", "app_secret_ref")
    assert(refBefore.isDefined, "the credential write must leave a _ref path behind")
    assert(!refBefore.get.contains("injected-plaintext"), "the stored value is a PATH, never the plaintext")

    saved(root, "feishu", json("""{"enabled":false,"fields":{}}"""))

    assertEquals(enabledOf(root, "feishu"), Some(false), "archive flips the switch")
    assertEquals(fieldOf(root, "feishu", "app_secret_ref"), refBefore, "archive keeps the stored ref")
    assertEquals(fieldOf(root, "feishu", "app_id"), Some("cli_aa32140e8af85d25"), "archive keeps the plain fields")
  }

  // ── A4 — no default-false materialisation ───────────────────────────────

  test("A4 a channel that was NEVER stored keeps the `enabled` key ABSENT (no default-false trap)") {
    val root = tmpRoot()
    saved(root, "feishu", verifiedBody(None)) // a body with no switch and no stored value
    assertEquals(
      hasEnabledKey(root, "feishu"), false,
      "从未存过 ⇒ 键保持缺席（不得把「从未表态」物化成 false）"
    )
    // The READ side keeps its own (correct) conflation: a store with no
    // `enabled` key is not an enabled channel — that is a read of the world,
    // not the engine making a decision for the user.
    val channels = SocialChannels.channelsJson(root).hcursor.downField("channels")
    assertEquals(
      channels.downField("feishu").downField("enabled").as[Boolean].toOption, Some(false),
      "the read side still answers false for an absent key (unchanged semantics)"
    )
    assertEquals(SocialChannels.isEnabled(root, "feishu"), false)
    // ...and the world can still be changed explicitly afterwards.
    saved(root, "feishu", json("""{"enabled":true,"fields":{}}"""))
    assertEquals(enabledOf(root, "feishu"), Some(true))
  }

  // ── A1 — golden path: surgical rewrite, zero collateral config change ───

  test("A1 golden explicit `enabled:true`: the switch is written and every other key of the file survives") {
    val root = tmpRoot()
    os.write(root / "nebflow.json",
      """{"version":3,"theme":"dark","socialChannels":{"version":1,"channels":{"wechat":{"enabled":true,"fields":{"app_id":"wx0123456789abcdef"},"updatedAt":111}}},"unrelatedKey":{"nested":[1,2,3]}}""")

    saved(root, "feishu", verifiedBody(Some(true)))

    val file = io.circe.parser.parse(os.read(root / "nebflow.json")).toOption.get
    assertEquals(file.hcursor.downField("version").as[Int].toOption, Some(3), "top-level keys survive")
    assertEquals(file.hcursor.downField("theme").as[String].toOption, Some("dark"))
    assertEquals(
      file.hcursor.downField("unrelatedKey").downField("nested").as[List[Int]].toOption, Some(List(1, 2, 3)),
      "unrelated subtrees survive verbatim"
    )
    assertEquals(enabledOf(root, "feishu"), Some(true), "the golden path writes the switch")
    assertEquals(
      file.hcursor.downField("socialChannels").downField("channels").downField("wechat")
        .downField("updatedAt").as[Long].toOption, Some(111L),
      "a sibling channel entry is untouched"
    )
    assertEquals(
      file.hcursor.downField("socialChannels").downField("channels").downField("wechat")
        .downField("enabled").as[Boolean].toOption, Some(true),
      "a sibling channel's switch is untouched"
    )
    // Re-saving with the SAME body is idempotent on the switch: the golden leg
    // never turns into a close on a second pass.
    saved(root, "feishu", verifiedBody(Some(true)))
    assertEquals(enabledOf(root, "feishu"), Some(true))
  }

  // ── A2 — the fingerprint reading is a status, never a config writer ─────

  test("A2 fingerprint mismatch is a STATUS: the connection face reports it and rewrites nothing") {
    val root = tmpRoot()
    saved(root, "feishu", verifiedBody(Some(true)))
    val before = os.read(root / "nebflow.json")

    val plugin = new FeishuBridgePlugin(root,
      pinnedCreds = Some(FeishuCredentials.Credential("cli_otherapp0000001", "injected-secret", "spec")))
    // A stored-A-connected-B world: the running bridge resolves a DIFFERENT app
    // (`cli_otherapp0000001`) than the one on file (`cli_aa32140e8af85d25`).
    val json = FeishuBridgePlugin.connectionJson(root, Some(plugin))

    assertEquals(
      json.hcursor.downField("fingerprintMatch").as[String].toOption, Some("mismatch"),
      "失配必须读成 mismatch（状态标记），而不是静默改写配置"
    )
    assertEquals(json.hcursor.downField("enabled").as[Boolean].toOption, Some(true),
      "the stored switch is reported as-is")
    assertEquals(os.read(root / "nebflow.json"), before,
      "零配置改写：读一次 connection face 不得动盘上任何字节（更不得写 enabled=false）")
    assertEquals(enabledOf(root, "feishu"), Some(true))
  }
end SocialChannelsBootSealSpec
