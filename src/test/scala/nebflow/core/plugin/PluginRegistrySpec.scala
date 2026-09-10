package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * 阶段 2b Plugins——PluginRegistry spec（§B.2 装载校验 / §B.3 注册表与信任门 /
 * §B.4 第 1-2 步 / §B.8-2/3/5/7/8 对应断言）。
 *
 * 覆盖：
 * - 扫描/解析/digest：全量（skills+mcp+tools）/ 仅其一 / 双全无拒载 / manifest
 *   缺 name 拒载 / 白名单外工具拒载（§B.8-8）/ 未知字段目录宽容+告警（§B.8-5）
 * - 信任门：默认拒绝 + 错误信息含审批指引（验收 1）；approve→trusted；
 *   改文件 digest 失效拒用（§B.8-3，验收 3）；revoke→untrusted
 * - 目录注入（§B.4 第 2 步）：untrusted 不进 catalog；格式对齐蓝图
 * - feature flag（§G.2）：plugins.enabled=false → catalog 为空
 */
class PluginRegistrySpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-registry"
  private val originalRoot = PathUtil.dataRoot

  // 隔离 dataRoot（NodeAcceptanceSpec 先例）：plugins/ 与 nebflow.json 都落临时目录
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private def pluginDir(name: String): os.Path =
    val d = tempRoot / "plugins" / name
    os.makeDir.all(d)
    d

  private def writeManifest(dir: os.Path, name: String, extra: String = ""): Unit =
    os.write.over(
      dir / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$name fixture plugin"$extra}"""
    )

  private def writeSkill(dir: os.Path, skill: String, body: String): Unit =
    os.makeDir.all(dir / "skills" / skill)
    os.write.over(dir / "skills" / skill / "SKILL.md",
      s"""---
         |name: $skill
         |description: $skill test skill
         |---
         |# $skill
         |$body""".stripMargin)

  private def writeTools(dir: os.Path, tools: List[String]): Unit =
    os.makeDir.all(dir / "org.nebflow")
    os.write.over(dir / "org.nebflow" / "tools.json", Json.obj("tools" -> tools.asJson).noSpaces)

  // ── fixtures ─────────────────────────────────────────────
  // full：skills + mcp + tools（全量形态）
  // skills-only / mcp-only：裁定 12「可只用其一」
  // empty：双全无 → 拒载（§B.8-7）
  // bad-tools：申请白名单外工具 Task → 拒载（§B.8-8）
  // messy：未知字段 + 未知目录 + 未知 org.nebflow 条目 → 宽容 + 告警（§B.8-5）

  private val full = pluginDir("full")
  writeManifest(full, "full")
  writeSkill(full, "howto", "read ${SKILL_DIR}/refs/spec.md for details")
  os.write.over(full / "mcp.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"fetch":{"type":"stdio","command":"python3","args":["-c","print(1)"]}}}""")
  writeTools(full, List("WebSearch", "WebFetch"))

  private val skillsOnly = pluginDir("skills-only")
  writeManifest(skillsOnly, "skills-only")
  writeSkill(skillsOnly, "explore", "method body")

  private val mcpOnly = pluginDir("mcp-only")
  writeManifest(mcpOnly, "mcp-only")
  os.write.over(mcpOnly / "mcp.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio","command":"python3","args":["-c","print(1)"]}}}""")

  private val empty = pluginDir("empty")
  writeManifest(empty, "empty")

  private val badTools = pluginDir("bad-tools")
  writeManifest(badTools, "bad-tools")
  os.write.over(badTools / "mcp.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio","command":"python3"}}}""")
  writeTools(badTools, List("Task")) // 编排类工具永不进白名单（§B.6）

  private val messy = pluginDir("messy")
  writeManifest(messy, "messy", extra = ""","unknownField": {"x": 1}""")
  writeSkill(messy, "s1", "body")
  os.makeDir.all(messy / "agents")
  os.write.over(messy / "agents" / "ghost.md", "# unknown component dir")
  os.makeDir.all(messy / "org.nebflow")
  os.write.over(messy / "org.nebflow" / "extra.json", "{}")

  // 隔离 nebflow.json（信任表读写落临时根）
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def scan: IO[List[PluginRegistry.PluginDef]] = PluginRegistry.scan()
  private def names: IO[Set[String]] = scan.map(_.map(_.name).toSet)

  /** 本 suite 盘上 6 包的缺席注记（口径：本 spec 固定 fixtures——empty/bad-tools 拒载、
    * full/skills-only 撤审后无审批记录、messy 从未审批、mcp-only 审批后改动 digest 漂移）。 */
  private val absenceNote6 =
    "另有 6 个插件未载入（装载失败 2 / 信任未批准 3 / digest 漂移 1）"

  // ── 扫描 / 装载校验（§B.2 / §B.8-7/5/8）────────────────────

  test("§B.8-7 装载校验: skills 与 mcp 可只用其一——两者其一的插件全部装载") {
    for loaded <- names
    yield
      assert(loaded.contains("skills-only"), s"skills-only must load, got $loaded")
      assert(loaded.contains("mcp-only"), s"mcp-only must load, got $loaded")
  }

  test("§B.8-7 装载校验: skills 与 mcp 全无 → 拒载 + 告警（rejected 列表留痕）") {
    PluginRegistry.listWithRejected().map { case (_, rejected) =>
      val entry = rejected.find(_._1 == "empty")
      assert(entry.isDefined, s"'empty' must be rejected, got $rejected")
      assert(entry.get._2.contains("neither"), "rejection reason must explain the neither-skills-nor-mcp rule")
    }
  }

  test("§B.3 解析结构: full 插件产出 skills/mcpServers/toolsExtension/digest 完整注册表") {
    scan.map(_.find(_.name == "full")).map {
      case Some(p) =>
        assertEquals(p.skills.map(_.id), List("full/howto"), "plugin 内 skill id = <plugin>/<skill>（§B.2 命名空间）")
        assertEquals(p.mcpServers.keySet, Set("fetch"))
        assertEquals(p.toolsExtension, List("WebSearch", "WebFetch"))
        assert(p.digest.nonEmpty && p.digest.length == 64, "digest must be sha256 hex")
        assert(p.fileCount >= 4, s"digest covers full directory tree, got ${p.fileCount} files")
      case None => fail("'full' plugin not loaded")
    }
  }

  test("§B.8-8 白名单: 申请 Task（编排类）→ 装载校验拒绝") {
    PluginRegistry.listWithRejected().map { case (loaded, rejected) =>
      assert(!loaded.exists(_.name == "bad-tools"), "bad-tools must NOT load")
      val entry = rejected.find(_._1 == "bad-tools")
      assert(entry.isDefined, s"bad-tools must be rejected, got $rejected")
      assert(entry.get._2.contains("Task") && entry.get._2.contains("WebSearch"),
        "rejection must name the illegal tool and the whitelist")
    }
  }

  test("§B.8-5 前向兼容: 未知 manifest 字段/未知组件目录 → 不崩溃、宽容跳过、各产生告警") {
    scan.map(_.find(_.name == "messy")).map {
      case Some(p) =>
        assert(p.trust.trusted || !p.trust.trusted, "loads regardless of trust state (this assert: no crash)")
        val w = p.warnings.mkString("; ")
        assert(w.contains("unknownField"), s"unknown manifest field must warn, got: $w")
        assert(w.contains("agents"), s"unknown component dir must warn, got: $w")
        assert(w.contains("extra.json"), s"unknown org.nebflow entry must warn, got: $w")
      case None => fail("'messy' plugin must load (forward-compat: skip + warn, never crash)")
    }
  }

  // ── digest ─────────────────────────────────────────────

  test("digest 确定性: 同目录两次计算一致；内容改动 → digest 变化") {
    for
      before <- scan.map(_.find(_.name == "skills-only").map(_.digest))
      // mtime 粒度保险（ms 级时间戳）：确保 treeSig 感知到变化
      _ <- IO.sleep(20.millis)
      _ <- IO.blocking {
        val d = tempRoot / "plugins" / "skills-only" / "skills" / "explore" / "SKILL.md"
        os.write.append(d, "\nappended line\n")
      }
      after <- scan.map(_.find(_.name == "skills-only").map(_.digest))
    yield
      val b = before.getOrElse(fail("skills-only missing"))
      val a = after.getOrElse(fail("skills-only missing after edit"))
      assert(b != a, s"content change must change digest ($b == $a)")
  }

  // ── 信任门（默认拒绝 / approve / digest 失效 / revoke）───────

  test("§B.8-2 信任门默认拒绝: 未审批插件 resolve → Left 且错误信息含审批指引") {
    PluginRegistry.resolve("full").map {
      case Right(p) => fail(s"untrusted plugin must not resolve, got $p")
      case Left(err) =>
        assert(err.contains("PLUGIN_UNTRUSTED"), s"error must carry the gate error code, got: $err")
        assert(err.contains("approve"), s"error must contain approval guidance, got: $err")
        assert(err.contains("full"), "error must name the plugin")
    }
  }

  test("§B.3 审批: approve → trusted（digest 入表）；再 approve 幂等刷新") {
    for
      r1 <- PluginRegistry.approve("full")
      _ = assert(r1.isRight, s"approve must succeed: $r1")
      resolved <- PluginRegistry.resolve("full")
      _ = assert(resolved.isRight, s"approved plugin must resolve: ${resolved.swap.toOption}")
    yield ()
  }

  test("§B.8-3 升级即重审: 审批后修改任一文件 → digest 失效拒用（spawn 期 resolve Left）") {
    for
      _ <- PluginRegistry.approve("mcp-only")
      ok <- PluginRegistry.resolve("mcp-only")
      _ = assert(ok.isRight, "approved plugin must resolve before modification")
      _ <- IO.sleep(20.millis) // mtime 粒度保险
      _ <- IO.blocking(os.write.append(tempRoot / "plugins" / "mcp-only" / "mcp.json", "\n"))
      after <- PluginRegistry.resolve("mcp-only")
    yield after match
      case Right(p) => fail(s"modified plugin must fall back to untrusted, got trusted digest=${p.digest.take(12)}")
      case Left(err) =>
        assert(err.contains("PLUGIN_UNTRUSTED"), s"stale digest must be refused with guidance, got: $err")
  }

  test("§B.3 撤审: revoke → 回落 untrusted（默认拒绝）") {
    for
      _ <- PluginRegistry.approve("skills-only")
      _ <- PluginRegistry.resolve("skills-only").map(r => assert(r.isRight, "pre-revoke must be trusted"))
      r <- PluginRegistry.revoke("skills-only")
      _ = assert(r.isRight, s"revoke must succeed: $r")
      after <- PluginRegistry.resolve("skills-only")
    yield assert(after.isLeft, "revoked plugin must be untrusted again")
  }

  // ── 分发器目录注入（§B.4 第 2 步）─────────────────────────

  test("目录注入: untrusted 不出现在 Plugin Catalog；approve 后以蓝图格式出现") {
    // 顺序无关起点：先撤审 full（前序测试可能已批）→ before 无 full → 审批后出现
    for
      _ <- PluginRegistry.revoke("full")
      before <- PluginRegistry.renderCatalog()
      _ <- PluginRegistry.approve("full")
      after <- PluginRegistry.renderCatalog()
    yield
      assert(after.contains("# Plugin Catalog"), s"catalog header must present, got: $after")
      assert(after.contains("- full: full fixture plugin [skills: howto | mcp: fetch | tools: WebSearch, WebFetch]"),
        s"catalog line format must match the blueprint (§B.4), got: $after")
      assert(!before.contains("- full:"), "untrusted plugin must NOT be in catalog before approval")
  }

  test("目录注入: 无受信插件但盘上有缺席包 → 不出插件行，只出段头 + 缺席注记（可见性批口径）") {
    // 空段口径（可见性批 2026-09-10）：无受信插件「且」无缺席包才返回 ""；本用例
    // 撤审 full 后受信集为空、盘上仍有 6 个缺席包（拒载/未批准/漂移）→ 段头 + 注记
    // 必须仍在（目录缩容到 0 也不许无声），但不得出现任何插件行。
    for
      _ <- PluginRegistry.revoke("full")
      after <- PluginRegistry.renderCatalog()
    yield
      assert(!after.contains("- full:"), "revoked plugin must disappear from catalog immediately")
      assert(!after.linesIterator.exists(_.startsWith("- ")),
        s"no plugin line may render when nothing is trusted: $after")
      assertEquals(after, PluginRegistry.CatalogHeader + "\n" + absenceNote6, s"header + absence note only: $after")
  }

  // ── feature flag（§G.2）────────────────────────────────

  test("§G.2 flag: plugins.enabled=false → catalog 为空（目录不注入）") {
    for
      _ <- IO.blocking(os.write.over(tempRoot / "nebflow.json", """{"plugins":{"enabled":false}}"""))
      catalog <- PluginRegistry.renderCatalog()
      enabled <- PluginsConfig.enabled
    yield
      assertEquals(enabled, false, "flag must read false from nebflow.json")
      assertEquals(catalog, "", "flag off must suppress the catalog injection entirely")
  }

  test("§G.2 flag 缺省: absent → enabled=true（fail-safe 方向 = 新能力开启）") {
    IO.blocking(os.write.over(tempRoot / "nebflow.json", "{}")).flatMap { _ =>
      PluginsConfig.enabled.map(e => assertEquals(e, true, "absent flag defaults to enabled"))
    }
  }

end PluginRegistrySpec
