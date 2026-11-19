package nebflow.core.plugin

import munit.FunSuite

/**
 * 三态 source spec 解析（远程发布源设计稿 §2.2，P1 验收「三态 spec 解析 12 例（含歧义/
 * 非法 pin/围栏拒绝）」）。纯函数面：零网络零 IO。正例 = 解析产物逐字段断言；反例 =
 * 稳定错误码逐字断言（`SPEC_` 前缀常量）。
 */
class PluginSourceParseSpec extends FunSuite:

  private val commit40 = "a" * 40
  private val sha64    = "b" * 64
  private val digest64 = "c" * 64

  private def codeOf(raw: String): String =
    PluginSourceSpec.parse(raw) match
      case Left(err) => err.code
      case Right(s)  => fail(s"expected a Left, got Right: $s")

  // ── 正例（三态各形态）──────────────────────────────────────

  test("official: index+id 全量 → OfficialSpec 逐字段"):
    assertEquals(
      PluginSourceSpec.parse(
        """{"kind":"official","index":"https://mystand.space/plugins/index.json","id":"visual-report"}"""
      ),
      Right(PluginSourceSpec.OfficialSpec(Some("https://mystand.space/plugins/index.json"), "visual-report"))
    )

  test("official: 仅 id（index 缺省 = 默认官方索引，由 P2 按 Branding 单源拼装）→ index=None"):
    assertEquals(
      PluginSourceSpec.parse("""{"kind":"official","id":"nebflow-qa"}"""),
      Right(PluginSourceSpec.OfficialSpec(None, "nebflow-qa"))
    )

  test("official: id/index 前后空白 trim"):
    assertEquals(
      PluginSourceSpec.parse("""{"kind":"official","id":"  visual-report  ","index":"  https://x/index.json  "}"""),
      Right(PluginSourceSpec.OfficialSpec(Some("https://x/index.json"), "visual-report"))
    )

  test("git: url+path+pin{tag,commit} 全量 → GitSpec 逐字段"):
    assertEquals(
      PluginSourceSpec.parse(
        s"""{"kind":"git","url":"https://github.com/o/r.git","path":"plugins/visual-report","pin":{"tag":"visual-report-v1.6.0","commit":"$commit40"}}"""
      ),
      Right(
        PluginSourceSpec.GitSpec(
          "https://github.com/o/r.git",
          Some("plugins/visual-report"),
          PluginSourceSpec.GitPin(Some("visual-report-v1.6.0"), commit40)
        )
      )
    )

  test("git: 只 pin commit 合法（tag 缺省，§2.2B「只 pin commit 时允许」）"):
    assertEquals(
      PluginSourceSpec.parse(s"""{"kind":"git","url":"git@github.com:o/r","pin":{"commit":"$commit40"}}"""),
      Right(PluginSourceSpec.GitSpec("git@github.com:o/r", None, PluginSourceSpec.GitPin(None, commit40)))
    )

  test("https: archive+sha256+sizeBytes+digest 全量 → HttpsSpec 逐字段"):
    assertEquals(
      PluginSourceSpec.parse(
        s"""{"kind":"https","archive":"https://oss.example.com/p/1.6.0.zip","sha256":"$sha64","sizeBytes":81234,"digest":"$digest64"}"""
      ),
      Right(
        PluginSourceSpec.HttpsSpec("https://oss.example.com/p/1.6.0.zip", sha64, Some(81234L), Some(digest64))
      )
    )

  test("hex 大小写归一：commit/sha256/digest 大写 → 小写落型"):
    val res = PluginSourceSpec.parse(
      s"""{"kind":"https","archive":"https://x/y.zip","sha256":"${sha64.toUpperCase}","digest":"${digest64.toUpperCase}"}"""
    )
    assertEquals(res, Right(PluginSourceSpec.HttpsSpec("https://x/y.zip", sha64, None, Some(digest64))))

  test("未知字段宽容忽略（前向兼容，ReleaseManifest 先例）"):
    assertEquals(
      PluginSourceSpec.parse(s"""{"kind":"official","id":"visual-report","futureField":{"x":1}}"""),
      Right(PluginSourceSpec.OfficialSpec(None, "visual-report"))
    )

  // ── 反例（错误码逐字，表驱动）──────────────────────────────

  test("反例: 顶层与 kind 面（非 JSON / 非 object / kind 缺失 / kind 未知）"):
    assertEquals(codeOf("""{"kind": [1,2]"""), PluginSourceSpec.CodeUnparseable)
    assertEquals(codeOf("""[1,2]"""), PluginSourceSpec.CodeNotObject)
    assertEquals(codeOf(""""a string""""), PluginSourceSpec.CodeNotObject)
    assertEquals(codeOf("""{"id":"x"}"""), PluginSourceSpec.CodeKindMissing)
    assertEquals(codeOf("""{"kind":42}"""), PluginSourceSpec.CodeKindMissing)
    assertEquals(codeOf("""{"kind":"svn"}"""), PluginSourceSpec.CodeKindUnknown)

  test("反例: official 面（id 缺失 / id 违反 §5.5 / index 非 http(s) / index 类型违规）"):
    assertEquals(codeOf("""{"kind":"official"}"""), PluginSourceSpec.CodeOfficialIdMissing)
    assertEquals(codeOf("""{"kind":"official","id":"  "}"""), PluginSourceSpec.CodeOfficialIdMissing)
    assertEquals(codeOf("""{"kind":"official","id":"Bad_Name"}"""), PluginSourceSpec.CodeOfficialIdInvalid)
    assertEquals(codeOf("""{"kind":"official","id":"visual report"}"""), PluginSourceSpec.CodeOfficialIdInvalid)
    assertEquals(codeOf("""{"kind":"official","id":"-leads-dash"}"""), PluginSourceSpec.CodeOfficialIdInvalid)
    assertEquals(
      codeOf("""{"kind":"official","id":"visual-report","index":"ftp://mystand.space/index.json"}"""),
      PluginSourceSpec.CodeOfficialIndexInvalid
    )
    assertEquals(codeOf("""{"kind":"official","id":"visual-report","index":42}"""), PluginSourceSpec.CodeOfficialIndexInvalid)

  test("反例: git 面（url 缺失 / url 不像 remote / 禁浮动 HEAD / pin 非 object）"):
    assertEquals(codeOf("""{"kind":"git"}"""), PluginSourceSpec.CodeGitUrlMissing)
    assertEquals(codeOf("""{"kind":"git","url":"ftp://example.com/r"}"""), PluginSourceSpec.CodeGitUrlInvalid)
    // 浮动 HEAD 禁令（§2.2B）：pin 缺失 / null / 非对象都是同一拒绝面
    assertEquals(codeOf("""{"kind":"git","url":"https://github.com/o/r.git"}"""), PluginSourceSpec.CodePinMissing)
    assertEquals(codeOf("""{"kind":"git","url":"https://github.com/o/r.git","pin":null}"""), PluginSourceSpec.CodePinMissing)
    assertEquals(codeOf("""{"kind":"git","url":"https://github.com/o/r.git","pin":"v1.0"}"""), PluginSourceSpec.CodePinMissing)

  test("反例: git pin 面（commit 缺失 / 非 40-hex / tag 空白）与 path 围栏（绝对 / .. 段）"):
    val u = """{"kind":"git","url":"https://github.com/o/r.git","pin":PH}"""
    assertEquals(codeOf(u.replace("PH", "{}")), PluginSourceSpec.CodePinCommitInvalid)
    assertEquals(codeOf(u.replace("PH", """{"commit":"abc123"}""")), PluginSourceSpec.CodePinCommitInvalid)
    assertEquals(codeOf(u.replace("PH", s"""{"commit":"${"a" * 39}"}""")), PluginSourceSpec.CodePinCommitInvalid)
    assertEquals(
      codeOf(u.replace("PH", s"""{"commit":"$commit40","tag":"   "}""")),
      PluginSourceSpec.CodePinTagInvalid
    )
    val p = """{"kind":"git","url":"https://github.com/o/r.git","pin":{"commit":"COMMIT"},"path":PATH}"""
    assertEquals(codeOf(p.replace("COMMIT", commit40).replace("PATH", """"/etc/passwd"""")), PluginSourceSpec.CodePathInvalid)
    assertEquals(codeOf(p.replace("COMMIT", commit40).replace("PATH", """"../escape"""")), PluginSourceSpec.CodePathInvalid)
    assertEquals(codeOf(p.replace("COMMIT", commit40).replace("PATH", """"a//b"""")), PluginSourceSpec.CodePathInvalid)

  test("反例: https 面（archive / sha256 / digest / sizeBytes 各自的错误码）"):
    assertEquals(codeOf("""{"kind":"https"}"""), PluginSourceSpec.CodeArchiveMissing)
    assertEquals(codeOf("""{"kind":"https","archive":"ftp://x/y.zip"}"""), PluginSourceSpec.CodeArchiveInvalid)
    assertEquals(codeOf("""{"kind":"https","archive":"https://x/y.zip"}"""), PluginSourceSpec.CodeSha256Missing)
    assertEquals(
      codeOf(s"""{"kind":"https","archive":"https://x/y.zip","sha256":"${"b" * 63}"}"""),
      PluginSourceSpec.CodeSha256Invalid
    )
    assertEquals(
      codeOf(s"""{"kind":"https","archive":"https://x/y.zip","sha256":"$sha64","digest":"nothex"}"""),
      PluginSourceSpec.CodeDigestInvalid
    )
    assertEquals(
      codeOf(s"""{"kind":"https","archive":"https://x/y.zip","sha256":"$sha64","sizeBytes":-5}"""),
      PluginSourceSpec.CodeSizeInvalid
    )
    assertEquals(
      codeOf(s"""{"kind":"https","archive":"https://x/y.zip","sha256":"$sha64","sizeBytes":81.5}"""),
      PluginSourceSpec.CodeSizeInvalid
    )
    assertEquals(
      codeOf(s"""{"kind":"https","archive":"https://x/y.zip","sha256":"$sha64","sizeBytes":"81234"}"""),
      PluginSourceSpec.CodeSizeInvalid
    )

  // ── 判定助手同族面（P2 fetcher 复用面）─────────────────────

  test("isGitUrl 与 installFromSync 既有判据同域（https/http/git@/.git 四形态）"):
    assert(PluginSourceSpec.isGitUrl("https://github.com/o/r"))
    assert(PluginSourceSpec.isGitUrl("http://example.com/r.git"))
    assert(PluginSourceSpec.isGitUrl("git@github.com:o/r"))
    assert(PluginSourceSpec.isGitUrl("/tmp/some/repo.git"))
    assert(!PluginSourceSpec.isGitUrl("ftp://example.com/r"))
    assert(!PluginSourceSpec.isGitUrl("visual-report"))

end PluginSourceParseSpec
