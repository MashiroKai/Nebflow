package nebflow.shared

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
    // Updated on rename day 2026-10-16 (stand S2 prep, chain-n-f349f81b):
    // productName/lowerName/name/logo slots flipped; issuer-face values
    // (domain/profileUrl/loginUrl/installUrl/serverUrl) deliberately kept.
    assertEquals(Branding.productName, "stand")
    assertEquals(Branding.lowerName, "stand")
    assertEquals(Branding.fullName, "Nebflow")
    assertEquals(Branding.domain, "nebflow.space") // 唯一真值（2026-09-07 命名边界裁定；可 env 覆盖）
    assertEquals(Branding.profileUrl, "https://nebflow.space/profile")
    assertEquals(Branding.loginUrl, "https://nebflow.space/login")
    assertEquals(Branding.installUrl, "https://nebflow.space/install.sh")
    assertEquals(Branding.serverUrl, "https://neblink.nebflow.space")
    assertEquals(Branding.githubOrg, "MashiroKai")
    assertEquals(Branding.githubRepo, "Nebflow")
    assertEquals(Branding.homeDirName, ".nebflow")
    assertEquals(Branding.envPrefix, "NEBFLOW")
    assertEquals(Branding.cosBucket, "nebflow-releases")
    assertEquals(Branding.subsystemName, "neblink")
    // brand-switch-console batch 2026-10-15: name falls back to productName
    // when the optional brand.conf key is absent; the logo references default
    // to the canonical in-place /css/ assets (files stay where they are).
    // 2026-10-16: stand switch slots the explicit logo keys (r4 clean set).
    assertEquals(Branding.name, "stand")
    assertEquals(Branding.logoUrl, "/brand/stand/logo/logo-512-light.png")
    assertEquals(Branding.logoDarkUrl, "/brand/stand/logo/logo-512-dark.png")
  }

  test("academicSearchUserAgent derives from the live brand (productName + installUrl host)") {
    assertEquals(
      Branding.academicSearchUserAgent,
      "stand/academic-search (mailto:research@nebflow.space)"
    )
  }

  test("dualEnv: brand prefix wins, legacy NEBFLOW_ prefix falls back (env 覆盖机制)") {
    // 可选环境覆盖：NEBFLOW_BRAND_DOMAIN / NEBFLOW_PROFILE_URL 覆盖 brand.conf 默认值
    val env = Map(
      "NEBFLOW_BRAND_DOMAIN" -> "nebflow.space",
      "NEBFLOW_PROFILE_URL" -> "https://nebflow.space/profile",
      "NEBFLOW_HOME" -> "/tmp/legacy-home"
    )
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "BRAND_DOMAIN"), Some("nebflow.space"))
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "PROFILE_URL"), Some("https://nebflow.space/profile"))
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "HOME"), Some("/tmp/legacy-home"))
    // 未设 env → None（fallback 到 brand.conf 默认值）
    assertEquals(Branding.dualEnv(Map.empty, "NEBFLOW", "BRAND_DOMAIN"), None)
  }

end BrandingSpec
