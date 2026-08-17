package nebflow.core

import munit.FunSuite

class BrandingSpec extends FunSuite:

  test("parseBrandConf: values, inline comments, blank lines, junk lines") {
    val text =
      """# full-line comment
        |
        |productName   = Nebflow      # inline comment
        |url = https://example.com/x#frag   # comment after a fragment
        |spaced =  value with spaces
        |noEqualsLine
        | = orphan value
        |dup = first
        |dup = second
        |""".stripMargin
    val conf = Branding.parseBrandConf(text)
    assertEquals(conf("productName"), "Nebflow")
    assertEquals(conf("url"), "https://example.com/x#frag")
    assertEquals(conf("spaced"), "value with spaces")
    assertEquals(conf("dup"), "second") // last one wins
    assert(!conf.contains("noEqualsLine"))
    assert(!conf.contains(""))
  }

  test("parseBrandConf: empty text yields empty map") {
    assertEquals(Branding.parseBrandConf(""), Map.empty[String, String])
    assertEquals(Branding.parseBrandConf("# only\n\n# comments\n"), Map.empty[String, String])
  }

  test("live constants match the repo-root brand.conf (current brand)") {
    // Data-value assertions (will be updated together with brand.conf on
    // rename day) — they guard the packaging path: if brand.conf stops
    // reaching the classpath, these fail with the packaging error.
    assertEquals(Branding.productName, "Nebflow")
    assertEquals(Branding.lowerName, "nebflow")
    assertEquals(Branding.fullName, "Nebflow")
    assertEquals(Branding.domain, "neblink.example") // placeholder (D4)
    assertEquals(Branding.installUrl, "https://nebflow.space/install.sh")
    assertEquals(Branding.serverUrl, "https://neblink.nebflow.space")
    assertEquals(Branding.githubOrg, "MashiroKai")
    assertEquals(Branding.githubRepo, "Nebflow")
    assertEquals(Branding.homeDirName, ".nebflow")
    assertEquals(Branding.envPrefix, "NEBFLOW")
    assertEquals(Branding.cosBucket, "nebflow-releases-1411212853")
    assertEquals(Branding.subsystemName, "neblink")
  }

  test("academicSearchUserAgent is byte-identical to the pre-rebrand UA") {
    assertEquals(
      Branding.academicSearchUserAgent,
      "Nebflow/academic-search (mailto:research@nebflow.space)",
    )
  }

end BrandingSpec
