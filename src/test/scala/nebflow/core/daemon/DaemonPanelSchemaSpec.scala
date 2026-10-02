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

  // ── F-7 · the LOCAL <meta CSP> injected into a host-carried panel doc ──

  test("F-7 · the panel CSP is default-src 'none' and denies outbound connections") {
    // The policy must be the strict, local-only shape the ruling asked for:
    // no global CSP is touched anywhere; this string only ever lands inside the
    // panel iframe's own document.
    assert(DaemonPanelSchema.PanelCsp.contains("default-src 'none'"), DaemonPanelSchema.PanelCsp)
    assert(DaemonPanelSchema.PanelCsp.contains("connect-src 'none'"), DaemonPanelSchema.PanelCsp)
    assert(!DaemonPanelSchema.PanelCsp.contains("connect-src 'self'"), "a host-carried doc has no 'self' origin")
    assert(!DaemonPanelSchema.PanelCsp.contains("allow-same-origin"), DaemonPanelSchema.PanelCsp)
  }

  test("F-7 · the meta lands as the FIRST child of <head>, before any author script") {
    val doc =
      """<!doctype html><html><head><title>t</title>
        |<script>window.__ran = true;</script></head>
        |<body><p>panel</p></body></html>""".stripMargin
    val out = DaemonPanelSchema.panelSrcdoc(doc)
    assert(DaemonPanelSchema.hasPanelCsp(out), "the CSP marker is absent from the injected document")
    val metaIdx = out.indexOf("data-daemon-panel-csp")
    val scriptIdx = out.indexOf("<script>")
    assert(metaIdx > 0 && metaIdx < scriptIdx, s"meta ($metaIdx) must precede the first script ($scriptIdx)")
    // It is a real meta element carrying the policy, not a comment or a string.
    assert(out.contains(s"""http-equiv="Content-Security-Policy""""), out.take(400))
    assert(out.contains(DaemonPanelSchema.PanelCsp), "the policy text itself is missing")
    // The document's own markup survives untouched.
    assert(out.contains("<p>panel</p>"), "the author body was altered")
  }

  test("F-7 · injection is idempotent and total on malformed input") {
    val doc = """<html><head></head><body>x</body></html>"""
    val once = DaemonPanelSchema.injectPanelCsp(doc)
    val twice = DaemonPanelSchema.injectPanelCsp(once)
    assertEquals(twice, once, "a second injection must be a no-op")
    assertEquals(once.sliding("data-daemon-panel-csp".length).count(_ == "data-daemon-panel-csp"), 1)
    // No <head> at all: a meta outside head would be IGNORED by the browser,
    // so a fragment is wrapped into a minimal document first.
    val fragment = DaemonPanelSchema.panelSrcdoc("<p>bare</p>")
    assert(DaemonPanelSchema.hasPanelCsp(fragment), fragment)
    assert(fragment.contains("<head"), "a meta outside <head> would be silently dropped")
    // Degenerate inputs never throw.
    for s <- List("", "<", "<head", "plain text", "<html><head") do
      assert(DaemonPanelSchema.panelSrcdoc(s).nonEmpty, s"empty result for ${s.take(20)}")
  }

  test("F-7 · a declaration cannot suppress the policy by quoting the marker (fail-open hole)") {
    // The idempotence check must key on the removal of OUR OWN tag, never on the
    // bare marker substring: a declaration that merely PRINTS the marker text
    // would, under a substring-keyed skip, make the injector believe the policy
    // was already in force — and the panel would run with NO policy at all.
    val quoting =
      """<html><head></head><body><p>data-daemon-panel-csp is a marker string</p></body></html>"""
    val out = DaemonPanelSchema.panelSrcdoc(quoting)
    assert(DaemonPanelSchema.hasPanelCsp(out), "the marker substring suppressed the real injection")
    assert(out.contains(DaemonPanelSchema.PanelCsp), "the policy text is absent")
    assert(out.contains("http-equiv=\"Content-Security-Policy\""), "no real meta was written")
    // The author's own marker text survives (we do not edit their markup).
    assert(out.contains("is a marker string"), "the author's body was altered")
    // And a document that is NOT a document at all still gets a policy when it
    // quotes the marker in plain text.
    val plain = DaemonPanelSchema.panelSrcdoc("data-daemon-panel-csp")
    assert(DaemonPanelSchema.hasPanelCsp(plain), plain)
  }

  test("F-7 · a declaration cannot remove or weaken the injected policy") {
    // A hostile declaration puts its OWN, permissive meta first — the injected
    // policy is still present, and the strictest policy wins because the FIRST
    // meta in head is the one the browser honours and ours is injected first.
    val hostile =
      """<html><head><meta http-equiv="Content-Security-Policy" content="default-src *">
        |</head><body>x</body></html>""".stripMargin
    val out = DaemonPanelSchema.panelSrcdoc(hostile)
    val ours = out.indexOf("data-daemon-panel-csp")
    val theirs = out.indexOf("default-src *")
    assert(ours > 0 && theirs > 0, "both metas expected in the fixture")
    assert(ours < theirs, s"the injected policy ($ours) must precede the author's ($theirs)")
  }

  // ── F-7 placement · the policy must land in the REAL head, not in text ──
  //
  // Placement is the half of F-7 that "the marker is in the bytes" cannot see:
  // a <meta> outside a real <head> is IGNORED by the browsing context, so a
  // byte-level check would attest to protection that is not in force.
  //
  // These specs locate the head INDEPENDENTLY of the implementation: candidate
  // offsets in comment/raw-text regions are excluded first, and a tag name only
  // counts on its full boundary. They are the red instrument for the fail-open
  // family that keys a security decision on declaration-controlled text.

  /** Offsets sitting inside an HTML comment or a raw-text element body. */
  private def opaqueRegions(doc: String): List[(Int, Int)] =
    val out = scala.collection.mutable.ListBuffer[(Int, Int)]()
    var i = doc.indexOf("<!--")
    while i >= 0 do
      val end = doc.indexOf("-->", i + 4)
      val stop = if end < 0 then doc.length else end + 3
      out += ((i, stop))
      i = doc.indexOf("<!--", stop)
    List("script", "style", "textarea", "title").foreach { tag =>
      val lower = doc.toLowerCase
      var at = 0
      var go = true
      while go do
        val open = lower.indexOf(s"<$tag", at)
        if open < 0 then go = false
        else
          val afterO = if open + tag.length + 1 < lower.length then lower.charAt(open + tag.length + 1) else '>'
          val isTag = afterO == '>' || afterO.isWhitespace
          val close = lower.indexOf(s"</$tag", open)
          if isTag && close >= 0 then
            val end = doc.indexOf('>', close)
            out += ((open, if end < 0 then doc.length else end + 1))
            at = if end < 0 then doc.length else end + 1
          else at = open + 1
    }
    out.toList

  end opaqueRegions

  private def isOpaque(regions: List[(Int, Int)], idx: Int): Boolean =
    regions.exists { case (a, b) => idx >= a && idx < b }

  /**
   * [start, end) of the FIRST real `<head …>` element in `doc`, or None.
   *
   * Deliberately a different traversal from the implementation's, and it also
   * models the parser rule that matters: a `<head>` reached after body-level
   * content has begun is DISCARDED by the HTML parser (the round-2 arrival
   * probe caught exactly that shape), so head state must be tracked.
   */
  private def realHeadSpan(doc: String): Option[(Int, Int)] =
    val lower = doc.toLowerCase
    val regions = opaqueRegions(doc)
    val headAllowed = Set(
      "base",
      "basefont",
      "bgsound",
      "link",
      "meta",
      "title",
      "noscript",
      "noframes",
      "style",
      "script",
      "template",
      "head"
    )
    val rawText = Set("script", "style", "textarea", "title")
    var i = 0
    var inHead = true
    var found: Option[Int] = None
    while found.isEmpty && i < lower.length do
      val lt = lower.indexOf('<', i)
      if lt < 0 then i = lower.length
      else if lower.startsWith("<!--", lt) then
        val e = lower.indexOf("-->", lt + 4)
        i = if e < 0 then lower.length else e + 3
      else if isOpaque(regions, lt) then i = lt + 1
      else
        val m = "^</?([a-zA-Z][a-zA-Z0-9:-]*)".r.findFirstMatchIn(doc.substring(lt)).filter(_.start == 0)
        m match
          case None => i = lt + 1
          case Some(mm) =>
            val name = mm.group(1).toLowerCase
            val gt = doc.indexOf('>', lt)
            if gt < 0 then i = lower.length
            else if name == "head" then
              if inHead then found = Some(gt + 1)
              i = gt + 1
            else if name == "body" || name == "html" then
              if name == "body" then inHead = false
              i = gt + 1
            else if headAllowed.contains(name) then
              if rawText.contains(name) then
                val close = lower.indexOf(s"</$name", gt)
                i = if close < 0 then lower.length else lower.indexOf('>', close) + 1
              else i = gt + 1
            else
              inHead = false
              i = gt + 1
            end if
        end match
      end if
    end while
    if found.isEmpty then None
    else
      val open = found.get
      val close = lower.indexOf("</head", open)
      Some((open, if close < 0 then doc.length else close))

  end realHeadSpan

  private def assertPolicyInRealHead(label: String, doc: String): String =
    val out = DaemonPanelSchema.panelSrcdoc(doc)
    val meta = out.indexOf("data-daemon-panel-csp")
    assert(meta >= 0, s"[$label] no injected meta at all")
    assert(
      !isOpaque(opaqueRegions(out), meta),
      s"[$label] the meta was written inside a comment/script body (browser ignores it)"
    )
    val span = realHeadSpan(out)
    assert(span.isDefined, s"[$label] the result has no real <head> element")
    val (a, b) = span.get
    assert(meta >= a && meta < b, s"[$label] meta@$meta is outside the real head [$a,$b)")
    assert(DaemonPanelSchema.hasPanelCsp(out), s"[$label] hasPanelCsp disagrees with the real placement")
    out

  test("F-7 · a '<head>' decoy inside a COMMENT is not the insertion anchor") {
    val doc = "<!-- <head> --><html><head><title>t</title></head><body><p>x</p></body></html>"
    assertPolicyInRealHead("comment-decoy", doc)
  }

  test("F-7 · a document whose only '<head' is really '<header>' gains a real head") {
    val doc =
      """<html><body><header>hdr</header>
        |<script>fetch('https://evil.example/x')</script></body></html>""".stripMargin
    assertPolicyInRealHead("header-only", doc)
  }

  test("F-7 · a '<head>' inside a SCRIPT string is not the insertion anchor") {
    val doc =
      """<script>var s = '<head>';</script><html><head><title>t</title></head>
        |<body><p>x</p></body></html>""".stripMargin
    assertPolicyInRealHead("script-string", doc)
  }

  test("F-7 · a '<head>' inside an ATTRIBUTE value is not the insertion anchor") {
    val doc = """<div data-x="<head>">d</div><html><head><title>t</title></head><body>x</body></html>"""
    assertPolicyInRealHead("attr-decoy", doc)
  }

  test("F-7 · a '<head>' the parser DROPS (body content already began) is not the anchor") {
    // The HTML parser leaves head state at the first body-level token, so a
    // <head> written after <body>/<header> is DISCARDED — anchoring on it puts
    // the policy exactly where the browser drops it. Found by the round-2
    // server-side arrival probe, not by the byte-level specs: the bytes look
    // fine and only real enforcement exposes it.
    val doc = "<html><body><header>h</header></body><head><title>t</title></head><body><p>x</p></body></html>"
    assertPolicyInRealHead("head-after-body", doc)
  }

  test("F-7 · a '<head>' inside a STYLE raw-text body is not the insertion anchor") {
    val doc =
      """<style>/* <head> */</style><html><head><title>t</title></head><body><p>x</p></body></html>"""
    assertPolicyInRealHead("style-rawtext", doc)
  }

  test("F-7 · a document whose only head is COMMENTED OUT gains a real head") {
    val doc = "<html><body><!-- <head></head> --><p>x</p></body></html>"
    assertPolicyInRealHead("commented-head-tag", doc)
  }

  test("F-7 · cspInjected never answers true for a policy outside the real head") {
    // The wire field is computed from hasPanelCsp. If placement can be ignored
    // while the flag says yes, the wire attests to protection that is not in
    // force — the fail-open reading this whole fix exists to close. Asserted as
    // an AGREEEMENT between the two questions over the decoy family.
    val docs = List(
      "<!-- <head> --><html><head><title>t</title></head><body><p>x</p></body></html>",
      "<html><body><header>hdr</header><p>x</p></body></html>",
      "<html><body><p>x</p></body></html>",
      "<script>var s='<head>';</script><html><head><title>t</title></head><body>x</body></html>",
      "<div data-x='<head>'>d</div><html><head><title>t</title></head><body>x</body></html>",
      "<!doctype html><html><head></head><body>data-daemon-panel-csp</body></html>"
    )
    val disagreements = docs.flatMap { doc =>
      val out = DaemonPanelSchema.panelSrcdoc(doc)
      val meta = out.indexOf("data-daemon-panel-csp")
      val honoured = realHeadSpan(out).exists { case (a, b) => meta >= a && meta < b }
      if DaemonPanelSchema.hasPanelCsp(out) && !honoured then Some(doc.take(56)) else None
    }
    assert(
      disagreements.isEmpty,
      s"cspInjected=true while the policy sits outside the real head: $disagreements"
    )
  }

  test("F-7 · hasPanelCsp reports FALSE for a misplaced policy (attestation is placement-aware)") {
    // 🔴 This asks hasPanelCsp DIRECTLY, on hand-built input. Going through
    // panelSrcdoc cannot cover it: once the injector always lands the meta
    // correctly, a bytes-only predicate would agree on every generated document
    // and a regression to "the marker is present => true" would stay invisible.
    // The semantic the ruling requires is narrower than "our bytes are there":
    // report true only when the policy is in the real head, false otherwise.
    //
    // The tag is taken from the injector's OWN output (it is private, and
    // rebuilding it by hand would test a different string than production).
    val generated = DaemonPanelSchema.panelSrcdoc("<!doctype html><html><head></head><body>x</body></html>")
    val start = generated.indexOf("<meta data-daemon-panel-csp")
    val end = generated.indexOf('>', start) + 1
    val tag = generated.substring(start, end)
    assert(tag.contains("http-equiv=\"Content-Security-Policy\""), s"unexpected tag: $tag")

    // (a) present but in the BODY (outside the head) => NOT honoured.
    val inBody = s"<!doctype html><html><head><title>t</title></head><body>$tag</body></html>"
    assert(!DaemonPanelSchema.hasPanelCsp(inBody), "a meta in the body is ignored by the browser")

    // (b) present but inside a COMMENT => not markup at all.
    val inComment = s"<!doctype html><html><head><!-- $tag --><title>t</title></head><body>x</body></html>"
    assert(!DaemonPanelSchema.hasPanelCsp(inComment), "a commented-out meta is not in force")

    // (c) present but before the head opens (ahead of the doctype) => ignored.
    val beforeHead = s"$tag<!doctype html><html><head><title>t</title></head><body>x</body></html>"
    assert(!DaemonPanelSchema.hasPanelCsp(beforeHead), "a meta ahead of the document is ignored")

    // (d) no real head at all => nothing to honour it.
    val noHead = s"<!doctype html><html><body>$tag<p>x</p></body></html>"
    assert(!DaemonPanelSchema.hasPanelCsp(noHead), "no real <head> means no honoured policy")

    // (e) the control: correctly placed => true, and only then.
    val good = s"<!doctype html><html><head>$tag<title>t</title></head><body>x</body></html>"
    assert(DaemonPanelSchema.hasPanelCsp(good), "a correctly placed policy must be attested")
  }
end DaemonPanelSchemaSpec
