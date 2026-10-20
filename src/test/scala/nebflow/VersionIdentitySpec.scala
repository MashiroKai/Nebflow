package nebflow

import munit.FunSuite

/**
 * 统一更新系统 M3 ① 定向验收：版本身份自报三元组的派生链。
 *
 * 覆盖：
 *  - `Version.build`：值域 = git short sha（构建期 BUILD_SHA 资源）或 "dev"
 *    （测试 classpath 无该资源 ⇒ 属性回退/dev 兜底）；
 *  - `Version.channelFrom`：install-manifest.json（install.sh write_manifest 唯一
 *    写入点）的 channel 字段**优先**；记录不可读/值非法 ⇒ 版本串 `-beta` 启发；
 *    兜底 stable。与 link-server M2 收侧值域（stable/beta）逐字对齐。
 */
class VersionIdentitySpec extends FunSuite:

  test("build: 恒非空，形态 = git short sha 或 dev（tarball/无 git 回退）") {
    val b = Version.build
    assert(b.nonEmpty, "build 恒非空（回退链兜底）")
    assert(b == "dev" || b.matches("[0-9a-f]{7,40}"), s"build = git short sha 或 dev, got: $b")
  }

  test("channel: install-manifest 记录优先（stable）") {
    assertEquals(Version.channelFrom(Some("stable"), "2026.10.20"), "stable")
  }

  test("channel: install-manifest 记录优先（beta——记录与版本串不一致时记录赢）") {
    assertEquals(Version.channelFrom(Some("beta"), "2026.10.20"), "beta")
  }

  test("channel: 记录不可读 ⇒ 版本串启发（含 -beta ⇒ beta）") {
    assertEquals(Version.channelFrom(None, "1.4.1-beta.58"), "beta")
    assertEquals(Version.channelFrom(None, "2026.10.20"), "stable")
  }

  test("channel: 记录值非法（值域外）⇒ 不采信，走启发（服务端值域 stable|beta 对齐）") {
    assertEquals(Version.channelFrom(Some("nightly"), "2026.10.20"), "stable")
    assertEquals(Version.channelFrom(Some(""), "1.4.1-beta.9"), "beta")
  }

  test("channelFrom: 现值（真实环境单点应用）∈ {stable, beta}") {
    assert(Set("stable", "beta").contains(Version.channel), s"got ${Version.channel}")
  }
end VersionIdentitySpec
