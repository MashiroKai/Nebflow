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
    assertEquals(Branding.domain, "neblink.space") // 调试域真值（2026-09-01 登录链修复；发布 env 覆盖为 nebflow.space）
    assertEquals(Branding.profileUrl, "https://neblink.space/profile")
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

  test("dualEnv: brand prefix wins, legacy NEBFLOW_ prefix falls back (双域覆盖机制)") {
    // 发布环境：NEBFLOW_BRAND_DOMAIN / NEBFLOW_PROFILE_URL 覆盖调试默认值
    val env = Map(
      "NEBFLOW_BRAND_DOMAIN" -> "nebflow.space",
      "NEBFLOW_PROFILE_URL" -> "https://nebflow.space/profile",
      "NEBFLOW_HOME" -> "/tmp/legacy-home",
    )
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "BRAND_DOMAIN"), Some("nebflow.space"))
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "PROFILE_URL"), Some("https://nebflow.space/profile"))
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "HOME"), Some("/tmp/legacy-home"))
    // 未设 env → None（fallback 到 brand.conf 默认值，即调试域）
    assertEquals(Branding.dualEnv(Map.empty, "NEBFLOW", "BRAND_DOMAIN"), None)
  }

end BrandingSpec
