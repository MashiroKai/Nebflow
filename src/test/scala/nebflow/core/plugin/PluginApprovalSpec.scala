package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * 协议符合度批——§B.3 信任门审批清单升级 spec（审计项 7 + 简化申报①处置）：
 *
 * - 红标项（§B.3 审批清单格式表）：无 author / 无 version；mcp env 凭据类键名；
 *   command 指向 shell|curl 类——approvalManifest.flags 数据源（面板渲染面，
 *   RestApiRoutes 不需改动——纯 additive JSON）
 * - 变更摘要（§B.3「与上次审批版本的 diff」；2b 简化申报①的协议要求部分补齐）：
 *   首审 = new-install；审批后未变 = unchanged；改动 = changed + 逐文件
 *   added/removed/modified（trust 记录新增 files 快照，approve 单点写入）
 * - 旧记录（无 files 快照）→ 降级 digest 级比对并注明
 * - §B.3 外部导入：installFrom 本地路径 → 落为 untrusted 待审；重名拒绝；
 *   非 plugin 目录拒绝；manifest name 非法拒绝
 */
class PluginApprovalSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-approval"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "plugins")
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 完整元信息段（version + author）——多测试共用。 */
  private val VersionAuthor = "\"version\":\"1.0.0\",\"author\":{\"name\":\"A\"}"

  private def mkSkillPlugin(name: String, manifestExtra: String = "", envKeys: List[String] = Nil,
                            command: Option[String] = None): os.Path =
    val d = tempRoot / "plugins" / name
    os.makeDir.all(d / "skills" / "s")
    val extra1 = if manifestExtra.isEmpty then "" else "," + manifestExtra
    val envFields = envKeys.map(k => "\"" + k + "\":\"v\"").mkString(",")
    val envJson = if envKeys.isEmpty then "" else ",\"env\":{" + envFields + "}"
    val cmd = command.getOrElse("python3")
    val cmdJson = ",\"command\":\"" + cmd + "\""
    os.write.over(d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name"$extra1}""")
    os.write.over(d / "mcp.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio"$cmdJson$envJson}}}""")
    os.write.over(d / "skills" / "s" / "SKILL.md",
      """---
        |name: s
        |description: fixture
        |---
        |body""".stripMargin)
    d

  private def manifestOf(name: String): PluginRegistry.PluginDef =
    PluginRegistry.scan().unsafeRunSync().find(_.name == name)
      .getOrElse(fail(s"plugin '$name' not loaded"))

  // ── 红标项（§B.3）─────────────────────────────────────

  test("§B.3 红标: 无 author / 无 version → flags 命中；凭据类 env 键名、curl|sh 类 command → flags 命中") {
    mkSkillPlugin("flaggy",
      manifestExtra = "\"description\":\"no author/version fields\"",
      envKeys = List("API_KEY", "REGION"), command = Some("curl"))
    val p = manifestOf("flaggy")
    val manifest = PluginRegistry.approvalManifest(p)
    val flags = manifest.hcursor.downField("flags").as[List[String]].getOrElse(Nil)
    val joined = flags.mkString("; ")
    assert(joined.contains("version"), s"missing version must be flagged, got: $joined")
    assert(joined.contains("author"), s"missing author must be flagged, got: $joined")
    assert(joined.contains("API_KEY") && joined.contains("credential"), s"credential env key must be flagged, got: $joined")
    assert(joined.contains("curl") && joined.contains("shell/network"), s"curl command must be flagged, got: $joined")
    assert(!flags.exists(_.contains("REGION")), s"benign env key must NOT be flagged, got: $joined")
  }

  test("§B.3 红标: 元信息完整 + 无敏感 env/command → flags 为空（干净清单）") {
    mkSkillPlugin("clean", manifestExtra = VersionAuthor)
    val p = manifestOf("clean")
    val flags = PluginRegistry.approvalManifest(p).hcursor.downField("flags").as[List[String]].getOrElse(Nil)
    assertEquals(flags, Nil, s"clean plugin must have no red flags, got: $flags")
  }

  // ── 变更摘要（§B.3；简化申报①的协议要求部分补齐）────────────────

  test("§B.3 变更摘要: 首审 new-install → approve 后 unchanged → 改文件 changed（added/modified 逐文件）") {
    val d = mkSkillPlugin("diffed", manifestExtra = VersionAuthor)
    val before = PluginRegistry.approvalManifest(manifestOf("diffed"))
      .hcursor.downField("changeSummary").as[Json].getOrElse(fail("no changeSummary"))
    assertEquals(before.hcursor.downField("kind").as[String].getOrElse("?"), "new-install",
      "no approval record → new-install")

    PluginRegistry.approve("diffed").unsafeRunSync()
    val after = PluginRegistry.approvalManifest(manifestOf("diffed"))
      .hcursor.downField("changeSummary").as[Json].getOrElse(fail("no changeSummary"))
    assertEquals(after.hcursor.downField("kind").as[String].getOrElse("?"), "unchanged",
      "approve without change → unchanged")

    Thread.sleep(20) // mtime 粒度
    os.write.append(d / "skills" / "s" / "SKILL.md", "\nappended\n") // modified
    os.write.over(d / "NEWFILE.md", "brand new") // added
    val changed = PluginRegistry.approvalManifest(manifestOf("diffed"))
      .hcursor.downField("changeSummary").as[Json].getOrElse(fail("no changeSummary"))
    assertEquals(changed.hcursor.downField("kind").as[String].getOrElse("?"), "changed")
    val added = changed.hcursor.downField("added").as[List[String]].getOrElse(Nil)
    val modified = changed.hcursor.downField("modified").as[List[String]].getOrElse(Nil)
    assert(added.contains("NEWFILE.md"), s"added must list NEWFILE.md, got: $added")
    assert(modified.contains("skills/s/SKILL.md"), s"modified must list SKILL.md, got: $modified")
  }

  test("§B.3 变更摘要: 旧审批记录（无 files 快照）→ 降级 digest 级比对并注明") {
    val d = mkSkillPlugin("legacy", manifestExtra = VersionAuthor)
    val p0 = manifestOf("legacy")
    // 手工植入 2b 形态（仅 sha256/approvedAt/scope）的 trust 记录
    os.write.over(tempRoot / "nebflow.json", Json.obj(
      "plugins" -> Json.obj("trust" -> Json.obj("legacy" -> Json.obj(
        "sha256" -> p0.digest.asJson, "approvedAt" -> 1757000000L.asJson, "scope" -> "all".asJson))))
      .noSpaces)
    PluginRegistry.invalidateCache()
    val manifest = PluginRegistry.approvalManifest(manifestOf("legacy"))
      .hcursor.downField("changeSummary").as[Json].getOrElse(fail("no changeSummary"))
    assertEquals(manifest.hcursor.downField("kind").as[String].getOrElse("?"), "changed")
    assert(manifest.hcursor.downField("note").as[String].toOption.exists(_.contains("digest-level")),
      s"legacy record must degrade honestly, got: $manifest")
    assertEquals(manifest.hcursor.downField("digestMatches").as[Boolean].getOrElse(true), true,
      "digest is identical → digestMatches=true")
  }

  // ── §B.3 外部导入（installFrom）────────────────────────

  test("§B.3 add: 本地目录 → 以 manifest name 落盘为 untrusted（默认拒绝），可再审批") {
    val src = tempRoot / "install-src" / "installed-plugin"
    os.makeDir.all(src / "skills" / "s")
    os.write.over(src / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"installed-plugin","version":"1.0.0","description":"installed"}""")
    os.write.over(src / "skills" / "s" / "SKILL.md",
      """---
        |name: s
        |description: installed
        |---
        |body""".stripMargin)

    PluginRegistry.installFrom(src.toString).flatMap {
      case Left(err) => fail(s"install must succeed: $err")
      case Right(msg) =>
        assert(msg.contains("untrusted"), s"install message must state default-deny, got: $msg")
        // 注册表可见但 untrusted（默认拒绝）
        PluginRegistry.resolve("installed-plugin").flatMap {
          case Right(p) => fail(s"installed plugin must be untrusted, got trusted digest=${p.digest.take(12)}")
          case Left(err2) =>
            assert(err2.contains("PLUGIN_UNTRUSTED"), s"got: $err2")
            // 审批后可用
            PluginRegistry.approve("installed-plugin").flatMap {
              case Left(e) => fail(s"approve after install failed: $e")
              case Right(_) =>
                PluginRegistry.resolve("installed-plugin").map(r =>
                  assert(r.isRight, "approved installed plugin must resolve"))
            }
        }
    }
  }

  test("§B.3 add: 重名已存在 / 非 plugin 目录 / manifest name 非法 → 拒绝（不覆盖不落盘）") {
    val dupSrc = tempRoot / "install-src" / "dup"
    os.makeDir.all(dupSrc / "skills" / "s")
    os.write.over(dupSrc / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"installed-plugin","version":"1.0.0"}""")
    os.write.over(dupSrc / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: x\n---\nb")

    val bareSrc = tempRoot / "install-src" / "bare"
    os.makeDir.all(bareSrc)

    val badNameSrc = tempRoot / "install-src" / "badname"
    os.makeDir.all(badNameSrc)
    os.write.over(badNameSrc / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"Bad_Name","version":"1.0.0"}""")

    for
      dup <- PluginRegistry.installFrom(dupSrc.toString)
      bare <- PluginRegistry.installFrom(bareSrc.toString)
      bad <- PluginRegistry.installFrom(badNameSrc.toString)
    yield
      assert(dup.isLeft && dup.swap.toOption.getOrElse("").contains("already exists"),
        s"duplicate install must be refused: $dup")
      assert(bare.isLeft && bare.swap.toOption.getOrElse("").contains("plugin.json"),
        s"non-plugin dir must be refused: $bare")
      assert(bad.isLeft && bad.swap.toOption.getOrElse("").contains("§5.5"),
        s"illegal manifest name must be refused: $bad")
  }

end PluginApprovalSpec
