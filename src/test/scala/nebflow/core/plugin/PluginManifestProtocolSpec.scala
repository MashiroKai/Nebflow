package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * 协议符合度批——plugin.json manifest 协议 1.0.0 符合 spec（审计项 1/2/3 +
 * Agent Plugins 1.0.0 §5/§7.1/§4.1）。
 *
 * 覆盖：
 * - §5.2 闭合 schema 十字段：四元数据字段（homepage/repository/license/keywords）
 *   识别 + 记录（2b knownManifestKeys 缺口补齐）；未知字段宽容+告警（回归）
 * - §5.2/§5.3 $schema：必填 + canonical（缺失/非 canonical → 拒载）
 * - §5.4 类型校验：author object（仅 name/email/url）、keywords string[]、
 *   version/description/homepage/repository/license string——违规 fatal
 * - §5.5 name 约束：大小写/连续 -- .. /首尾字母数字
 * - 版本语义（审计项 2）：非 semver version 不拒载（spec §5.4 MUST NOT）
 * - §7.1 skill 一致性门：frontmatter 缺 name/description → skip+告警
 * - §4.1 路径围栏：SKILL.md / tools.json 符号链接逃逸 → 跳过/忽略（不拒载插件）
 */
class PluginManifestProtocolSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-manifest"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "plugins")
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def manifestOf(name: String, schema: String = PluginRegistry.CanonicalSchema, extra: String = ""): String =
    s"""{"$$schema":"$schema","name":"$name","version":"1.0.0","description":"$name fixture"$extra}"""

  /** 建一个只有 skills 的插件目录（可覆写 manifest 内容）。 */
  private def skillPlugin(dirname: String, manifest: String): os.Path =
    val d = tempRoot / "plugins" / dirname
    os.makeDir.all(d / "skills" / "s")
    os.write.over(d / "plugin.json", manifest)
    os.write.over(d / "skills" / "s" / "SKILL.md",
      """---
        |name: s
        |description: fixture skill
        |---
        |body""".stripMargin)
    d

  private def loadOne(dirname: String): IO[Either[String, PluginRegistry.PluginDef]] =
    PluginRegistry.scan().map { all =>
      all.find(_.warnings.nonEmpty && false) // noop to keep shape
      all.find(_.name == dirname).map(Right(_)).getOrElse(
        Left(s"plugin '$dirname' not loaded (scan: ${all.map(_.name).mkString(",")})"))
    }

  private def rejected: IO[Map[String, String]] =
    PluginRegistry.listWithRejected().map(_._2.toMap)

  // ── §5.2 闭合 schema 十字段（审计项 1：2b 缺 4 个元数据字段）──────

  test("§5.2 十字段: 完整元数据 manifest 装载且 homepage/repository/license/keywords/author 记录进注册表") {
    val d = tempRoot / "plugins" / "full-metadata"
    os.makeDir.all(d / "skills" / "s")
    os.write.over(d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"full-metadata","version":"2.3.4",
         |"description":"all ten fields","author":{"name":"Alice","email":"a@x.io","url":"https://x.io/~a"},
         |"homepage":"https://x.io/plugin","repository":"https://github.com/x/plugin","license":"MIT",
         |"keywords":["search","web"]}""".stripMargin.replace("\n", ""))
    os.write.over(d / "skills" / "s" / "SKILL.md",
      """---
        |name: s
        |description: fixture skill
        |---
        |body""".stripMargin)
    loadOne("full-metadata").map {
      case Left(err) => fail(s"full-metadata must load: $err")
      case Right(p) =>
        assertEquals(p.homepage, "https://x.io/plugin", "homepage must be recorded (§5.4)")
        assertEquals(p.repository, "https://github.com/x/plugin", "repository must be recorded (§5.4)")
        assertEquals(p.license, "MIT", "license must be recorded (§5.4)")
        assertEquals(p.keywords, List("search", "web"), "keywords must be recorded (§5.4)")
        assert(p.author.contains("Alice") && p.author.contains("a@x.io"),
          s"author object must render into author field, got: ${p.author}")
        assert(p.warnings.forall(w => !w.contains("homepage") && !w.contains("keywords")),
          "protocol fields must NOT trigger unknown-field warnings")
    }
  }

  // ── §5.2/§5.3 $schema：必填 + canonical ──────────────────────

  test("§5.3: 缺失 $schema → 拒载（required field）") {
    skillPlugin("no-schema", """{"name":"no-schema","version":"1.0.0"}""")
    rejected.map { r =>
      val reason = r.getOrElse("no-schema", fail(s"no-schema must be rejected, got: ${r.keySet}"))
      assert(reason.contains("$schema") && reason.contains("required"),
        s"rejection must name the missing required field, got: $reason")
    }
  }

  test("§5.2: 非 canonical $schema → 拒载（unsupported version，客户端只识别 canonical 值）") {
    skillPlugin("wrong-schema", manifestOf("wrong-schema", schema = "https://agent-plugins.org/schema/1.0.0"))
    skillPlugin("future-schema", manifestOf("future-schema", schema = "https://agent-plugins.org/schemas/9.9.9/plugin.schema.json"))
    rejected.map { r =>
      val w = r.getOrElse("wrong-schema", fail("wrong-schema must be rejected"))
      val f = r.getOrElse("future-schema", fail("future-schema must be rejected"))
      assert(w.contains("PLUGIN_SCHEMA_UNSUPPORTED") && w.contains("canonical"), s"got: $w")
      assert(f.contains("PLUGIN_SCHEMA_UNSUPPORTED"), s"got: $f")
    }
  }

  // ── §5.5 name 约束 ─────────────────────────────────────

  test("§5.5: 非法 name（大写 / 连续 -- / 首-.）→ 拒载；合法边界形态（acme.tools）装载") {
    skillPlugin("bad-caps", manifestOf("Bad-Plugin"))
    skillPlugin("bad-dashes", manifestOf("has--double"))
    skillPlugin("bad-lead", manifestOf("-start"))
    skillPlugin("acme.tools-dotted", manifestOf("acme.tools-dotted"))
    rejected.map { r =>
      assert(r("bad-caps").contains("PLUGIN_NAME_ILLEGAL"), s"uppercase must be rejected: ${r("bad-caps")}")
      assert(r("bad-dashes").contains("PLUGIN_NAME_ILLEGAL"), s"consecutive '--' must be rejected: ${r("bad-dashes")}")
      assert(r("bad-lead").contains("PLUGIN_NAME_ILLEGAL"), s"leading '-' must be rejected: ${r("bad-lead")}")
      assert(!r.contains("acme.tools-dotted"), "dotted valid name must load")
    }
  }

  // ── §5.4 类型校验（fatal）+ 版本语义（审计项 2）────────────────

  test("§5.4: version 为数字 / keywords 非字符串数组 / author 带未知字段 → 拒载（类型违规 fatal）") {
    skillPlugin("num-version", s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"num-version","version":123}""")
    skillPlugin("bad-keywords", manifestOf("bad-keywords", extra = ""","keywords":"not-array""""))
    skillPlugin("bad-author", manifestOf("bad-author",
      extra = ""","author":{"name":"A","role":"admin"}""")) // author 闭合：仅 name/email/url
    rejected.map { r =>
      assert(r("num-version").contains("must be a string"), s"got: ${r("num-version")}")
      assert(r("bad-keywords").contains("keywords"), s"got: ${r("bad-keywords")}")
      assert(r("bad-author").contains("author"), s"got: ${r("bad-author")}")
    }
  }

  test("版本语义（审计项 2）: 非 semver version → 不拒载（spec §5.4 RECOMMENDED，MUST NOT 拒绝）") {
    skillPlugin("loose-version", manifestOf("loose-version").replace("\"version\":\"1.0.0\"", "\"version\":\"r2-final\""))
    loadOne("loose-version").map {
      case Right(p) => assertEquals(p.version, "r2-final", "non-semver version must be recorded verbatim")
      case Left(err) => fail(s"non-semver version MUST NOT reject the manifest: $err")
    }
  }

  // ── §7.1 skill 一致性门 ────────────────────────────────

  test("§7.1: SKILL.md frontmatter 缺 description → skill skip + 告警（其余组件继续）") {
    val d = tempRoot / "plugins" / "skill-no-desc"
    os.makeDir.all(d / "skills" / "broken" / "sub")
    os.makeDir.all(d / "skills" / "good")
    os.write.over(d / "plugin.json", manifestOf("skill-no-desc"))
    os.write.over(d / "skills" / "broken" / "SKILL.md", "---\nname: broken\n---\nbody") // 缺 description
    os.write.over(d / "skills" / "good" / "SKILL.md",
      """---
        |name: good
        |description: fine
        |---
        |body""".stripMargin)
    loadOne("skill-no-desc").map {
      case Left(err) => fail(s"plugin must continue loading other skills: $err")
      case Right(p) =>
        assertEquals(p.skills.map(_.id), List("skill-no-desc/good"), "non-conforming skill skipped, conforming loaded")
        assert(p.warnings.exists(w => w.contains("broken") && w.contains("description")),
          s"skip must be reported (§7.1 SHOULD), got: ${p.warnings}")
    }
  }

  // ── §4.1 路径围栏（symlink 逃逸）──────────────────────────

  test("§4.1: SKILL.md 符号链接逃逸插件根 → 该 skill 跳过；tools.json 逃逸 → tools 扩展忽略") {
    val outside = tempRoot / "outside-staging"
    os.makeDir.all(outside)
    os.write.over(outside / "evil.md", "evil body")
    os.write.over(outside / "evil-tools.json", Json.obj("tools" -> List("WebSearch").asJson).noSpaces)

    val d1 = tempRoot / "plugins" / "skill-escape"
    os.makeDir.all(d1 / "skills" / "s")
    os.write.over(d1 / "plugin.json", manifestOf("skill-escape"))
    // SKILL.md 直接以 symlink 指向插件根外的 staging（§4.1 逃逸形态）。
    // 附一个合法 mcp.json 组件——围栏跳过 skill 后插件仍可装载（§4.1 最窄失败边界）
    os.symlink(d1 / "skills" / "s" / "SKILL.md", outside / "evil.md")
    os.write.over(d1 / "mcp.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio","command":"python3"}}}""")

    // tools 逃逸：org.nebflow/tools.json → 插件根外；plugin 本体有合法 skills，继续装载
    val d2 = tempRoot / "plugins" / "tools-escape"
    os.makeDir.all(d2 / "skills" / "s")
    os.makeDir.all(d2 / "org.nebflow")
    os.write.over(d2 / "plugin.json", manifestOf("tools-escape"))
    os.write.over(d2 / "skills" / "s" / "SKILL.md",
      """---
        |name: s
        |description: tmp
        |---
        |body""".stripMargin)
    os.symlink(d2 / "org.nebflow" / "tools.json", outside / "evil-tools.json")

    for
      esc <- loadOne("skill-escape")
      tools <- loadOne("tools-escape")
    yield
      esc match
        case Right(p) =>
          assertEquals(p.skills, Nil, "escaping SKILL.md must be skipped (§4.1)")
          assert(p.warnings.exists(_.contains("outside the plugin root")),
            s"containment skip must be reported, got: ${p.warnings}")
        case Left(err) => fail(s"skill-escape plugin should still load (skill-level skip): $err")
      tools match
        case Right(p) =>
          assertEquals(p.toolsExtension, Nil, "escaping tools.json must be ignored (§4.1)")
          assert(p.warnings.exists(_.contains("outside the plugin root")),
            s"containment ignore must be reported, got: ${p.warnings}")
        case Left(err) => fail(s"tools-escape plugin should still load: $err")
  }

end PluginManifestProtocolSpec
