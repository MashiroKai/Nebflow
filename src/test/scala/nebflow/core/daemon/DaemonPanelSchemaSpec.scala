package nebflow.core.daemon

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite

/** daemonpanel Phase A — declaration validator (backend F1–F9 + F12 pins).
  *
  * These specs are the RED/GREEN instrument for the backend criteria: each one
  * is written so that the pre-change tree fails it (the feature does not exist)
  * and the post-change tree passes it. They also serve as the mutation surface:
  * flipping a whitelist entry or the fail-closed branch must turn them red.
  */
class DaemonPanelSchemaSpec extends CatsEffectSuite:

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  private val schemaDecl = json("""
    {
      "version": 1,
      "title": "Mail heartbeat",
      "fields": [
        {"key": "host", "label": "IMAP host", "type": "string", "default": "imap.example.com"},
        {"key": "port", "label": "Port", "type": "number", "min": 1, "max": 65535, "default": 993},
        {"key": "tls", "label": "TLS", "type": "boolean", "default": true},
        {"key": "mode", "label": "Mode", "type": "enum", "options": ["ro", "rw"], "default": "ro"},
        {"key": "password", "label": "Password", "type": "secret", "required": true},
        {"key": "notes", "label": "Notes", "type": "text"}
      ]
    }
  """)

  // ── F8: closed type whitelist + whole-declaration fail-closed ──────────

  test("F8 · a whitelisted declaration validates and keeps every field") {
    DaemonPanelSchema.validate(schemaDecl, allowWeb = false) match
      case Left(err) => fail(s"valid declaration was rejected: $err")
      case Right(decl) =>
        assertEquals(decl.kind, "schema") // absent kind => strictest default
        assertEquals(decl.fields.map(_.key), List("host", "port", "tls", "mode", "password", "notes"))
        assertEquals(decl.fields.find(_.key == "password").exists(_.isSecret), true)
  }

  test("F8 · an out-of-whitelist type rejects the WHOLE declaration (no partial render)") {
    val bad = json("""
      {"fields": [
        {"key": "ok", "type": "string"},
        {"key": "evil", "type": "html"}
      ]}
    """)
    DaemonPanelSchema.validate(bad, allowWeb = false) match
      case Right(_) => fail("declaration with type 'html' must be rejected — not partially rendered")
      case Left(err) => assert(err.contains("unsupported type 'html'"), err)
  }

  test("F8 · an unknown top-level key rejects the declaration (closed key set)") {
    val bad = json("""{"fields": [{"key": "a", "type": "string"}], "onload": "alert(1)"}""")
    DaemonPanelSchema.validate(bad, allowWeb = false) match
      case Right(_) => fail("an undeclared top-level key must reject the declaration")
      case Left(err) => assert(err.contains("unknown declaration key"), err)
  }

  test("F8 · an unknown field key rejects the declaration") {
    val bad = json("""{"fields": [{"key": "a", "type": "string", "src": "https://evil.example/x.js"}]}""")
    DaemonPanelSchema.validate(bad, allowWeb = false) match
      case Right(_) => fail("an undeclared field key must reject the declaration")
      case Left(err) => assert(err.contains("unknown key"), err)
  }

  test("F8 · duplicate keys, bad version and bad kind all reject") {
    def rejected(raw: String): Unit =
      DaemonPanelSchema.validate(json(raw), allowWeb = false) match
        case Right(_) => fail(s"expected rejection: $raw")
        case Left(_)  => ()
    rejected("""{"fields": [{"key": "a", "type": "string"}, {"key": "a", "type": "number"}]}""")
    rejected("""{"version": 2, "fields": [{"key": "a", "type": "string"}]}""")
    rejected("""{"kind": "native", "fields": [{"key": "a", "type": "string"}]}""")
    assert(true)
  }

  test("F8 · enum without options, and non-enum with options, both reject") {
    def rejected(raw: String): Unit =
      DaemonPanelSchema.validate(json(raw), allowWeb = false) match
        case Right(_) => fail(s"expected rejection: $raw")
        case Left(_)  => ()
    rejected("""{"fields": [{"key": "m", "type": "enum"}]}""")
    rejected("""{"fields": [{"key": "m", "type": "string", "options": ["a"]}]}""")
    // A default outside the declared options is rejected too.
    rejected("""{"fields": [{"key": "m", "type": "enum", "options": ["a"], "default": "z"}]}""")
    assert(true)
  }

  // ── F9: the web escape hatch is default-closed ────────────────────────

  test("F9 · kind 'web' is DENIED while the nebflow.json switch is absent") {
    val web = json("""{"kind": "web", "url": "http://localhost:3000/config"}""")
    DaemonPanelSchema.validate(web, allowWeb = false) match
      case Right(_) => fail("kind 'web' must be denied by default (F-5)")
      case Left(err) => assert(err.contains("disabled"), err)
  }

  test("F9 · the switch is read only from an explicit boolean; anything else denies") {
    assertEquals(DaemonPanelSchema.allowWeb(None), false)
    assertEquals(DaemonPanelSchema.allowWeb(Some(json("{}"))), false)
    assertEquals(DaemonPanelSchema.allowWeb(Some(json("""{"allowWeb": "yes"}"""))), false)
    assertEquals(DaemonPanelSchema.allowWeb(Some(json("""{"allowWeb": false}"""))), false)
    assertEquals(DaemonPanelSchema.allowWeb(Some(json("""{"allowWeb": true}"""))), true)
  }

  test("F9 · with the switch on, exactly one of url / htmlFile is required") {
    def validate(raw: String): Either[String, DaemonPanelSchema.Declaration] =
      DaemonPanelSchema.validate(json(raw), allowWeb = true)
    assert(validate("""{"kind": "web"}""").isLeft, "neither given must reject")
    assert(
      validate("""{"kind": "web", "url": "http://localhost:3000/c", "htmlFile": "panel.html"}""").isLeft,
      "both given must reject"
    )
    assert(validate("""{"kind": "web", "htmlFile": "panel.html"}""").isRight)
  }

  test("F9 · web url must be absolute, userinfo-free and fragment-free (MCP entry shape)") {
    def rejected(url: String): Unit =
      DaemonPanelSchema.validate(json(s"""{"kind": "web", "url": "$url"}"""), allowWeb = true) match
        case Right(_) => fail(s"expected rejection for url '$url'")
        case Left(_)  => ()
    rejected("/relative/config")
    rejected("javascript:alert(1)")
    rejected("http://user:pass@localhost:3000/config")
    rejected("http://localhost:3000/config#frag")
    assert(
      DaemonPanelSchema.validate(json("""{"kind": "web", "url": "http://localhost:3000/c"}"""), allowWeb = true).isRight
    )
  }

  test("F9 · htmlFile must be relative and traversal-free") {
    def rejected(f: String): Unit =
      DaemonPanelSchema.validate(json(s"""{"kind": "web", "htmlFile": "$f"}"""), allowWeb = true) match
        case Right(_) => fail(s"expected rejection for htmlFile '$f'")
        case Left(_)  => ()
    rejected("/etc/passwd")
    rejected("../../secrets/auth.json")
    rejected("a/../../b.html")
    assert(
      DaemonPanelSchema.validate(json("""{"kind": "web", "htmlFile": "panels/mail.html"}"""), allowWeb = true).isRight
    )
  }

  // ── F-6 / E1 pin: the panel sandbox token set ─────────────────────────

  test("F-6 · sandbox tokens never include allow-same-origin (nor popups/top-navigation)") {
    val decl = DaemonPanelSchema.validate(schemaDecl, allowWeb = false).toOption.get
    assertEquals(decl.sandboxTokens, List("allow-scripts"))
    assert(!decl.sandboxTokens.contains("allow-same-origin"))
    assert(decl.sandboxTokens.toSet.subsetOf(DaemonPanelSchema.AllowedSandboxTokens))
    // `forms` is the only addition the declaration may request.
    val withForms = DaemonPanelSchema.validate(schemaDecl.deepMerge(json("""{"forms": true}""")), allowWeb = false)
    assertEquals(withForms.toOption.get.sandboxTokens, List("allow-scripts", "allow-forms"))
  }

  test("F1 · a config-less declaration is absent, not defaulted (zero-migration pin)") {
    // The zero-migration pin lives in the model: no key => None.
    val raw = json("""{"id": "x", "name": "X", "command": ["true"]}""")
    val cfg = raw.as[DaemonConfig].toOption.get
    assertEquals(cfg.configPanel, None)
    // And a null normalizes to None as well.
    val withNull = json("""{"id": "x", "name": "X", "command": ["true"], "configPanel": null}""")
    assertEquals(withNull.as[DaemonConfig].toOption.get.configPanel, None)
  }

  test("F1 · re-serializing a panel-less config does NOT introduce a configPanel key") {
    val cfg = DaemonConfig(id = "x", name = "X", command = List("true"))
    val encoded = cfg.asJson
    assert(!encoded.asObject.exists(_.contains("configPanel")), s"unexpected key in $encoded")
    // And a panel-bearing config round-trips the declaration verbatim.
    val withPanel = cfg.copy(configPanel = Some(json("""{"fields": [{"key": "a", "type": "string"}]}""")))
    val back = withPanel.asJson.as[DaemonConfig].toOption.get
    assertEquals(back.configPanel, withPanel.configPanel)
  }
