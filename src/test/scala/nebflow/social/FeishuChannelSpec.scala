package nebflow.social

import munit.FunSuite
import nebflow.core.CredentialFileAcl

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/**
 * Offline regression tests for the Feishu channel (feishu-chan, 2026-09-23).
 *
 * 🔴 Deliberately NO network and NO credential: the live half (WebSocket
 * handshake, real send/receive) is manual evidence only — a test that needed a
 * tenant credential could not run in CI and would drag a secret into the test
 * JVM. What IS pinned here is every pure decision the channel makes.
 *
 * The spec's own temporary data root is always used, so nothing touches the real
 * `~/.nebflow`.
 */
class FeishuChannelSpec extends FunSuite:

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-feishu-")

  /** POSIX-only creation attribute: the mode comes from the creation call, so the
    *  file never exists for an instant with a wider mode (same discipline the
    *  production writer follows). */
  private def narrowAttr: java.nio.file.attribute.FileAttribute[java.util.Set[java.nio.file.attribute.PosixFilePermission]] =
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  // ─────────────────────── inbound mapping ───────────────────────

  test("textOf extracts the body only for message_type=text") {
    assertEquals(FeishuMessage.textOf("text", """{"text":"hello world"}"""), Some("hello world"))
    // Non-text shapes must NOT be guessed at — a wrong guess fabricates content.
    assertEquals(FeishuMessage.textOf("post", """{"title":"t","content":[[{"tag":"text"}]]}"""), None)
    assertEquals(FeishuMessage.textOf("image", """{"image_key":"img_v2_abc"}"""), None)
    assertEquals(FeishuMessage.textOf("text", "not-json"), None)
    assertEquals(FeishuMessage.textOf("text", """{"other":"x"}"""), None)

    // 🔴 The discriminating case (found by mutation testing): a payload that DOES
    // carry a top-level `text` key but whose message_type is not `text`. The gate
    // is the message_type, never the payload's shape — a length/shape-only gate
    // would wrongly harvest this body. Verified red when the gate is removed.
    assertEquals(FeishuMessage.textOf("image", """{"text":"must-not-be-harvested"}"""), None)
    assertEquals(FeishuMessage.textOf("post", """{"text":"must-not-be-harvested"}"""), None)
    assertEquals(FeishuMessage.textOf("", """{"text":"must-not-be-harvested"}"""), None)
  }

  test("textOf keeps unicode and embedded quotes intact") {
    val body = """{"text":"多agent架构平台 \"Nebflow\" ✅"}"""
    assertEquals(FeishuMessage.textOf("text", body), Some("""多agent架构平台 "Nebflow" ✅"""))
  }

  test("inboundFrom builds a message from complete getter values") {
    val r = FeishuMessage.inboundFrom(
      eventId = Some("evt_1"), messageId = Some("om_1"), chatId = Some("oc_1"),
      messageType = Some("text"), contentRaw = Some("""{"text":"hi"}"""), createTime = Some("1700000000000"))
    assert(r.isRight)
    val in = r.toOption.get
    assertEquals(in.messageId, "om_1")
    assertEquals(in.chatId, "oc_1")
    assertEquals(in.text, Some("hi"))
    assertEquals(in.eventId, Some("evt_1"))
    // createTime is carried through verbatim (Feishu sends epoch-millis strings).
    assertEquals(in.createTime, Some("1700000000000"))
  }

  test("inboundFrom refuses to fabricate a message when ids are absent") {
    // The SDK's getters return null for absent fields; the mapping must surface
    // that as a failure, not as an empty-string message.
    val noMid = FeishuMessage.inboundFrom(None, None, Some("oc_1"), Some("text"), Some("""{"text":"x"}"""), None)
    assert(noMid.isLeft, "missing message_id must be an error")
    assert(noMid.left.toOption.get.contains("message_id"))

    val noCid = FeishuMessage.inboundFrom(None, Some("om_1"), None, Some("text"), Some("""{"text":"x"}"""), None)
    assert(noCid.isLeft, "missing chat_id must be an error")
    assert(noCid.left.toOption.get.contains("chat_id"))

    // Empty strings are as absent as nulls.
    val blank = FeishuMessage.inboundFrom(None, Some(""), Some(""), Some("text"),
      Some("""{"text":"x"}"""), None)
    assert(blank.isLeft)
    // …and a blank message id with a real chat id is still a refusal.
    val mixed = FeishuMessage.inboundFrom(None, Some(""), Some("oc_9"), Some("text"),
      Some("""{"text":"x"}"""), None)
    assert(mixed.isLeft)
  }

  test("inboundFrom tolerates a missing eventId and a non-text body") {
    val r = FeishuMessage.inboundFrom(None, Some("om_2"), Some("oc_2"), Some("image"),
      Some("""{"image_key":"k"}"""), None)
    assert(r.isRight)
    assertEquals(r.toOption.get.eventId, None)
    assertEquals(r.toOption.get.text, None)
    assertEquals(r.toOption.get.contentRaw, """{"image_key":"k"}""")
  }

  test("Inbound.toJson carries no credential field") {
    val in = FeishuMessage.inboundFrom(Some("evt"), Some("om_3"), Some("oc_3"), Some("text"),
      Some("""{"text":"hello"}"""), Some("1700000000000")).toOption.get
    val keys = in.toJson.asObject.get.keys.toSet
    assertEquals(keys, Set("eventId", "messageId", "chatId", "messageType", "content", "text", "createTime"))
    // The rendered JSON must never contain a secret-ish key.
    val rendered = in.toJson.noSpaces
    assert(!rendered.contains("Secret") && !rendered.contains("secret") && !rendered.contains("appSecret"))
  }

  // ─────────────────────── send body ───────────────────────

  test("textSendBody encodes content as a STRING, not a nested object") {
    val body = FeishuMessage.textSendBody("ping")
    assertEquals(body.hcursor.downField("msg_type").as[String].toOption, Some("text"))
    // 🔴 The classic failure mode: Feishu wants `content` to be the string form
    // of a JSON object. Sending the object itself is a type error.
    val content = body.hcursor.downField("content").as[String].toOption
    assertEquals(content, Some("""{"text":"ping"}"""))
  }

  test("textSendBody escapes quotes and unicode so the payload stays one line") {
    val body = FeishuMessage.textSendBody("""say "hi" ✅""")
    val content = body.hcursor.downField("content").as[String].toOption.get
    assert(!content.contains("\n"), "content must be a single-line JSON string")
    assertEquals(FeishuMessage.textOf("text", content), Some("""say "hi" ✅"""))
  }

  // ─────────────────────── credential resolution ───────────────────────

  test("legacy flat feishu.json is read (appId/appSecret camelCase)") {
    val root = tmpRoot()
    val p = root / "feishu.json"
    os.write(p, """{"appId":"cli_abcdefghij1234567890","appSecret":"s3cr3t-value-of-length-32-chars"}""",
      perms = os.PermSet.fromString("rw-------"))
    val r = FeishuCredentials.resolve(root)
    assert(r.isRight, s"expected resolution, got $r")
    assertEquals(r.toOption.get.appId, "cli_abcdefghij1234567890")
    assertEquals(r.toOption.get.appSecret, "s3cr3t-value-of-length-32-chars")
  }

  test("credential toString and rendering never expose the secret") {
    val c = FeishuCredentials.Credential("cli_abcdefghij1234567890", "super-secret-value", "test")
    val s = c.toString
    assert(!s.contains("super-secret-value"), s"secret leaked into toString: $s")
    val masked = FeishuCredentials.redacted("cli_abcdefghij1234567890")
    assert(!masked.contains("ijkl"), s"masked form leaked the middle: $masked")
    assertEquals(masked.length, "cli_abcdefghij1234567890".length)
    // Fingerprints are stable and do not contain the value.
    assertEquals(FeishuCredentials.fingerprint("abc"), FeishuCredentials.fingerprint("abc"))
    assert(!FeishuCredentials.fingerprint("abc").contains("abc"))
  }

  test("a present-but-incomplete legacy file is an error, not a silent fallthrough") {
    val root = tmpRoot()
    os.write(root / "feishu.json", """{"appId":"cli_abcdefghij1234567890"}""")
    val r = FeishuCredentials.resolve(root)
    assert(r.isLeft)

    // …even when a COMPLETE schema-side config also exists: a half-written
    // credential must not look like "no credential" and let the other source win.
    os.write(root / "nebflow.json",
      """{"socialChannels":{"channels":{"feishu":{"fields":{"app_id":"cli_fromschema0000000000"}}}}}""")
    assert(FeishuCredentials.resolve(root).isLeft)
  }

  test("schema-side resolution reads app_id inline and app_secret via its _ref path") {
    val root = tmpRoot()
    os.makeDir.all(root / "secrets")
    os.write(root / "secrets" / "social-feishu-app-secret", "schema-side-secret",
      perms = os.PermSet.fromString("rw-------"))
    os.write(root / "nebflow.json",
      s"""{"socialChannels":{"channels":{"feishu":{"fields":{"app_id":"cli_fromschema0000000000",""" +
        """"app_secret_ref":"~/.nebflow/secrets/social-feishu-app-secret"}}}}}""")
    val r = FeishuCredentials.resolve(root)
    // The `~/.nebflow/...` render form maps back under THIS root, so the tmp file
    // is found rather than the real home's.
    assertEquals(r.map(_.appSecret), Right("schema-side-secret"))
  }

  test("missing credential reports both candidate locations") {
    val root = tmpRoot()
    val r = FeishuCredentials.resolve(root)
    assert(r.isLeft)
    val msg = r.left.toOption.get.toString
    assert(msg.contains("feishu.json"), msg)
    assert(msg.contains("socialChannels"), msg)
  }

  // ─────────────────────── ACL reading ───────────────────────

  test("aclReading reports modeOk true for rw------- and false for a wide mode") {
    assume(!CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName), "POSIX-only readback")
    val root = tmpRoot()
    val narrow: Path = (root / "narrow").toNIO
    Files.createFile(narrow, narrowAttr)
    Files.write(narrow, "x".getBytes("UTF-8"))
    val wide: Path = (root / "wide").toNIO
    Files.createFile(wide)
    Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rw-r--r--"))
    Files.write(wide, "x".getBytes("UTF-8"))
    val absent: Path = (root / "nope").toNIO

    assertEquals(FeishuCredentials.aclReading(narrow).hcursor.downField("modeOk").as[Boolean].toOption, Some(true))
    assertEquals(FeishuCredentials.aclReading(narrow).hcursor.downField("mode").as[String].toOption, Some("rw-------"))
    assertEquals(FeishuCredentials.aclReading(wide).hcursor.downField("modeOk").as[Boolean].toOption, Some(false))
    assertEquals(FeishuCredentials.aclReading(absent).hcursor.downField("exists").as[Boolean].toOption, Some(false))
    // Absent ⇒ all three mechanical bits false, never "clean by default".
    assertEquals(FeishuCredentials.aclReading(absent).hcursor.downField("modeOk").as[Boolean].toOption, Some(false))
  }

  test("aclReading never returns file content") {
    val root = tmpRoot()
    val p: Path = (root / "c").toNIO
    Files.write(p, "TOPSECRET".getBytes("UTF-8"))
    assert(!FeishuCredentials.aclReading(p).noSpaces.contains("TOPSECRET"))
  }

  // ─────────────────────── no-live-IO guard ───────────────────────

  test("the offline spec never reaches the network: reading the real home is not attempted") {
    // A guard on the guard: resolving an EMPTY temp root must fail without any
    // network call, proving the resolution path is purely filesystem-local.
    val root = tmpRoot()
    val r = FeishuCredentials.resolve(root)
    assert(r.isLeft, "an empty root cannot resolve a credential")
  }
