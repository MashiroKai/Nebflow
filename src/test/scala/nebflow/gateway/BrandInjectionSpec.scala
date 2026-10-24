package nebflow.gateway

import io.circe.parser

import munit.FunSuite

class BrandInjectionSpec extends FunSuite:

  test("brandScriptTag: valid script element with parseable JSON contract") {
    val tag = StaticRoutes.brandScriptTag
    assert(clue(tag).startsWith("<script>window.__BRAND__="))
    assert(clue(tag).endsWith(";</script>"))
    val jsonStr = tag
      .stripPrefix("<script>window.__BRAND__=")
      .stripSuffix(";</script>")
    parser.parse(jsonStr) match
      case Left(err) => fail(s"not valid JSON: $err")
      case Right(json) =>
        // Live-value pins — updated together with brand.conf on rename day
        // (stand switch 2026-10-16, chain-n-f349f81b): brand face flipped,
        // issuer face (domain/profileUrl/homeDirName) deliberately kept.
        assertEquals(json.hcursor.get[String]("productName"), Right("stand"))
        assertEquals(json.hcursor.get[String]("lowerName"), Right("stand"))
        assertEquals(json.hcursor.get[String]("domain"), Right("mystand.space"))
        // L3 batch 3 append-only extension: homeDirName joins the contract
        // (数据根指向切换批 2026-10: default data root → ~/.stand; the legacy
        // ~/.nebflow stays as the hardcoded fallback in PathUtil, not here)
        assertEquals(json.hcursor.get[String]("homeDirName"), Right(".stand"))
        // 2026-09-01 login-chain fix: profileUrl joins the contract — the
        // frontend's ONLY URL input (activityBar.js builds the profile link).
        assertEquals(
          json.hcursor.get[String]("profileUrl"),
          Right("https://mystand.space/profile")
        )
        // brand-switch-console batch 2026-10-15: name/logoUrl/logoDarkUrl
        // join the contract (append-only). Stand switch slots the explicit
        // logo keys; name falls back to the new productName.
        assertEquals(json.hcursor.get[String]("name"), Right("stand"))
        assertEquals(json.hcursor.get[String]("logoUrl"), Right(nebflow.shared.Branding.logoUrl))
        assertEquals(json.hcursor.get[String]("logoDarkUrl"), Right(nebflow.shared.Branding.logoDarkUrl))
    end match
  }

  test("brandScriptJson: '</' inside values is escaped (script breakout hardening)") {
    val hostile = """a"</script>b"""
    val json =
      StaticRoutes.brandScriptJson(hostile, "x", "y", "z", "https://nebflow.space/profile", "n", "/l.png", "/ld.png")
    assert(!clue(json).contains("</script>"))
    assert(clue(json).contains("<\\/"))
    // '<\/' is a legal JSON escape for '/' — the value round-trips intact
    assertEquals(parser.parse(json).flatMap(_.hcursor.get[String]("productName")), Right(hostile))
  }

  test("injectBeforeHeadClose: inserts directly before the closing head tag") {
    val html = "<!DOCTYPE html><html><head><title>t</title></head><body></body></html>"
    val snippet = "<script>1;</script>"
    val injected = StaticRoutes.injectBeforeHeadClose(html, snippet)
    injected match
      case Some(out) =>
        assertEquals(out, "<!DOCTYPE html><html><head><title>t</title><script>1;</script></head><body></body></html>")
      case None => fail("expected Some")
  }

  test("injectBeforeHeadClose: None when the anchor tag is absent") {
    assertEquals(
      StaticRoutes.injectBeforeHeadClose("<html><body></body></html>", "<script>1;</script>"),
      None
    )
  }

  test("injectBeforeHeadClose: uses the last anchor occurrence") {
    val html = "<head></head><head></head>"
    val snippet = "X"
    assertEquals(
      StaticRoutes.injectBeforeHeadClose(html, snippet),
      Some("<head></head><head>X</head>")
    )
  }

  test("the bundled index templates carry the lowercase anchor tag") {
    // Guards the contract both injection modes rely on: source entry now,
    // dist entry when a webdist build is present (same transform origin).
    val source = scala.io.Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("web/index.html"),
      "UTF-8"
    )
    val html =
      try source.mkString
      finally source.close()
    assert(clue(html).contains("</head>"))
    assert(StaticRoutes.injectBeforeHeadClose(html, "<script>x</script>").isDefined)
  }

end BrandInjectionSpec
