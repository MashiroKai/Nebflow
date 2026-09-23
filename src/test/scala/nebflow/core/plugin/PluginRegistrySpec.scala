package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * 阶段 2b Plugins——PluginRegistry spec（§B.2 装载校验 / §B.4 第 1-2 步 /
 * §B.8-5/7/8 + **无审批批 2026-09-13 内容面语义**）。
 *
 * 覆盖：
 * - 扫描/解析/digest：全量（skills+mcp+tools）/ 仅其一 / 双全无拒载 / manifest
 *   缺 name 拒载 / 白名单外工具拒载（§B.8-8）/ 未知字段目录宽容+告警（§B.8-5）
 * - 内容面判定（**在位即信任 + 点名封禁**，无审批批 2026-09-13）：
 *   ① 无审批记录 ⇒ 扫到即受信（resolve Right，default-deny 已取消——本条就是作者令的
 *      正面断言）；② 内容变更（digest 漂移）**不拦装载** ⇒ 非拦截可见性 contentChanged；
 *      ③ 封禁（deny-list）⇒ resolve Left(PLUGIN_BLOCKED) + 出目录，解封即恢复；
 *      ④ 封禁持久性：自动 approve（种子路径）**不得**抹掉封禁（独立命名空间硬约束）。
 * - 目录注入（§B.4 第 2 步）：封禁不进 catalog；段尾注记（缺席 / 内容已变更）；
 *   健康摘要（含 content-changed 明细）
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
    os.write.over(
      dir / "skills" / skill / "SKILL.md",
      s"""---
         |name: $skill
         |description: $skill test skill
         |---
         |# $skill
         |$body""".stripMargin
    )

  private def writeTools(dir: os.Path, tools: List[String]): Unit =
    os.makeDir.all(dir / "org.nebflow")
    os.write.over(dir / "org.nebflow" / "tools.json", Json.obj("tools" -> tools.asJson).noSpaces)

  // ── fixtures ─────────────────────────────────────────────
  // full：skills + mcp + tools（全量形态）
  // skills-only / mcp-only：裁定 12「可只用其一」
  // empty：双全无 → 拒载（§B.8-7）
  // bad-tools：申请白名单外工具 Mail → 拒载（§B.8-8）
  // messy：未知字段 + 未知目录 + 未知 org.nebflow 条目 → 宽容 + 告警（§B.8-5）

  private val full = pluginDir("full")
  writeManifest(full, "full")
  writeSkill(full, "howto", "read ${SKILL_DIR}/refs/spec.md for details")

  os.write.over(
    full / "mcp.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"fetch":{"type":"stdio","command":"python3","args":["-c","print(1)"]}}}"""
  )
  writeTools(full, List("WebSearch", "WebFetch"))

  private val skillsOnly = pluginDir("skills-only")
  writeManifest(skillsOnly, "skills-only")
  writeSkill(skillsOnly, "explore", "method body")

  private val mcpOnly = pluginDir("mcp-only")
  writeManifest(mcpOnly, "mcp-only")

  os.write.over(
    mcpOnly / "mcp.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio","command":"python3","args":["-c","print(1)"]}}}"""
  )

  private val empty = pluginDir("empty")
  writeManifest(empty, "empty")

  private val badTools = pluginDir("bad-tools")
  writeManifest(badTools, "bad-tools")

  os.write.over(
    badTools / "mcp.json",
    s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio","command":"python3"}}}"""
  )
  writeTools(badTools, List("Mail")) // 编排类工具永不进白名单（§B.6；R2 2026-09-12：Task 退役，改判 Mail）

  private val messy = pluginDir("messy")
  writeManifest(messy, "messy", extra = ""","unknownField": {"x": 1}""")
  writeSkill(messy, "s1", "body")
  os.makeDir.all(messy / "agents")
  os.write.over(messy / "agents" / "ghost.md", "# unknown component dir")
  os.makeDir.all(messy / "org.nebflow")
  os.write.over(messy / "org.nebflow" / "extra.json", "{}")

  // 隔离 nebflow.json（审计记录 / 封禁表读写落临时根）
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def scan: IO[List[PluginRegistry.PluginDef]] = PluginRegistry.scan()
  private def names: IO[Set[String]] = scan.map(_.map(_.name).toSet)

  private def reset: IO[Unit] = IO {
    os.write.over(tempRoot / "nebflow.json", "{}")
    PluginRegistry.invalidateCache()
  }

  /**
   * 本 suite 盘上 6 包的缺席注记（口径：本 spec 固定 fixtures——empty/bad-tools 载入
   * 校验拒载；其余 4 包**在位即信任**⇒ 恒在目录里，不再构成缺席）。只列非零分类。
   */
  private val absenceNote2 = "另有 2 个插件未载入（装载失败 2）"

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

  test("§B.8-8 白名单: 申请 Mail（编排类，R2 2026-09-12 取代已退役的 Task）→ 装载校验拒绝") {
    PluginRegistry.listWithRejected().map { case (loaded, rejected) =>
      assert(!loaded.exists(_.name == "bad-tools"), "bad-tools must NOT load")
      val entry = rejected.find(_._1 == "bad-tools")
      assert(entry.isDefined, s"bad-tools must be rejected, got $rejected")
      assert(
        entry.get._2.contains("Mail") && entry.get._2.contains("WebSearch"),
        "rejection must name the illegal tool and the whitelist"
      )
    }
  }

  test("§B.8-5 前向兼容: 未知 manifest 字段/未知组件目录 → 不崩溃、宽容跳过、各产生告警") {
    scan.map(_.find(_.name == "messy")).map {
      case Some(p) =>
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

  // ── 内容面判定（在位即信任 + 点名封禁；无审批批 2026-09-13）─────

  test("在位即信任（正面断言）: 无审批记录包 → resolve Right（default-deny 已取消）") {
    for
      _ <- reset
      resolved <- PluginRegistry.resolve("messy") // 本 spec 从未 approve 过 messy
      scanned <- scan.map(_.find(_.name == "messy"))
    yield
      assert(
        resolved.isRight,
        s"a package with NO approval record must resolve (presence = trust), got: ${resolved.swap.toOption}"
      )
      assert(
        scanned.exists(p => p.trust.trusted && !p.contentChanged),
        "no record ⇒ trusted=true, contentChanged=false (nothing to compare against)"
      )
      assert(
        !resolved.swap.toOption.getOrElse("").contains("default-deny"),
        "the default-deny wording must be gone from every path"
      )
  }

  test("在位即信任: 无记录包进分发给新节点的 Plugin Catalog（作者令最直观的可见效果）") {
    for
      _ <- reset
      catalog <- PluginRegistry.renderCatalog()
    yield
      for n <- List("full", "skills-only", "mcp-only", "messy")
      do assert(catalog.contains(s"- $n:"), s"record-less package '$n' must appear in the catalog:\n$catalog")
      assert(!catalog.contains("- empty:"), "load-failed package must not render a line")
      assertEquals(
        catalog.linesIterator.toList.last,
        absenceNote2,
        s"catalog tail must aggregate the load-failed absences: $catalog"
      )
  }

  test("§B.3 审计记录: approve 写记录（不再决定装载）、resolve 幂等仍 Right") {
    for
      _ <- reset
      before <- PluginRegistry.resolve("full")
      _ = assert(before.isRight, "presence trust must hold before any approve")
      r1 <- PluginRegistry.approve("full")
      _ = assert(r1.isRight, s"audit record write must succeed: $r1")
      after <- PluginRegistry.resolve("full")
      rec <- IO.blocking(PluginRegistry.trustRecordDigest("full"))
    yield
      assert(after.isRight, "an audit record must not change the loading verdict (already trusted)")
      assert(rec.isDefined, "audit record must be on disk (seed-reconcile baseline)")
  }

  test("内容变更非拦截（§B.8-3 语义取消）: 记录后改文件 → resolve 仍 Right + contentChanged=true") {
    for
      _ <- reset
      _ <- PluginRegistry.approve("mcp-only")
      clean <- scan.map(_.find(_.name == "mcp-only"))
      _ = assert(clean.exists(p => p.trust.trusted && !p.contentChanged), "approved & untouched ⇒ contentChanged=false")
      _ <- IO.sleep(20.millis) // mtime 粒度保险
      _ <- IO.blocking(os.write.append(tempRoot / "plugins" / "mcp-only" / "mcp.json", "\n"))
      _ <- IO(PluginRegistry.invalidateCache())
      after <- PluginRegistry.resolve("mcp-only")
      drifted <- scan.map(_.find(_.name == "mcp-only"))
      catalog <- PluginRegistry.renderCatalog()
      manifest <- PluginRegistry
        .listWithRejected()
        .map(_._1.find(_.name == "mcp-only").map(PluginRegistry.approvalManifest))
    yield
      assert(after.isRight, s"content change must NOT gate loading any more, got: ${after.swap.toOption}")
      assert(drifted.exists(_.contentChanged), "digest drift must surface as the non-blocking contentChanged flag")
      assert(catalog.contains("- mcp-only:"), "a content-changed package stays in the catalog (visible, not removed)")
      assert(catalog.contains("内容与上次记录的版本不同"), s"catalog tail must carry the content-changed note:\n$catalog")
      assert(
        manifest.exists(_.hcursor.downField("contentChanged").as[Boolean].getOrElse(false)),
        "GET /plugins item must expose contentChanged=true"
      )
  }

  test("封禁: block → resolve Left(PLUGIN_BLOCKED, 文案含 unblock) + 出目录 + 清单 blocked=true") {
    for
      _ <- reset
      _ <- PluginBlockPolicy.block("full", "spec denial", "spec")
      blocked <- PluginRegistry.resolve("full")
      catalog <- PluginRegistry.renderCatalog()
      item <- PluginRegistry
        .listWithRejected()
        .map(_._1.find(_.name == "full").map(PluginRegistry.approvalManifest))
      _ <- PluginBlockPolicy.unblock("full", "spec")
      restored <- PluginRegistry.resolve("full")
      catalogAfter <- PluginRegistry.renderCatalog()
    yield
      assert(blocked.isLeft, s"a blocked package must be refused, got: $blocked")
      val err = blocked.swap.toOption.getOrElse("")
      assert(err.contains("PLUGIN_BLOCKED"), s"block reason must carry its error code, got: $err")
      assert(err.contains("unblock"), s"block reason must point at the action that exists, got: $err")
      assert(
        !err.contains("never approved") && !err.contains("default-deny"),
        s"the retired default-deny wording must never come back, got: $err"
      )
      assert(!catalog.contains("- full:"), s"a blocked package must leave the catalog:\n$catalog")
      val it = item.getOrElse(fail("full must still be listed (blocked, not hidden)"))
      assertEquals(it.hcursor.downField("blocked").as[Boolean].toOption, Some(true), "item.blocked must be true")
      assertEquals(
        it.hcursor.downField("trusted").as[Boolean].toOption,
        Some(false),
        "item.trusted must be false for a blocked package"
      )
      assertEquals(it.hcursor.downField("trust").downField("status").as[String].toOption, Some("blocked"))
      assert(restored.isRight, "unblock must restore presence trust")
      assert(catalogAfter.contains("- full:"), "an unblocked package must re-enter the catalog")
  }

  test("封禁持久性（硬约束）: 自动 approve（种子自愈路径）不得抹掉封禁") {
    for
      _ <- reset
      _ <- PluginBlockPolicy.block("skills-only", "spec: survive auto-approve", "spec")
      _ <- PluginRegistry.approve("skills-only") // 种子路径的等价动作（writeTrustEntry 整对象替换）
      stillBlocked <- PluginRegistry.resolve("skills-only")
      revokedOnDisk <- IO.blocking(os.read(tempRoot / "nebflow.json").contains("revoked"))
      _ <- PluginBlockPolicy.unblock("skills-only", "spec")
      restored <- PluginRegistry.resolve("skills-only")
    yield
      assert(
        stillBlocked.isLeft,
        s"approve must NOT clear a block (independent namespace): ${stillBlocked.swap.toOption}"
      )
      assert(revokedOnDisk, "the block record must live in its own `plugins.revoked` namespace")
      assert(restored.isRight, "unblock restores availability")
  }

  test("目录段尾注记: 封禁包计入缺席注记（点名不出行）") {
    for
      _ <- reset
      _ <- PluginBlockPolicy.block("messy", "spec", "spec")
      catalog <- PluginRegistry.renderCatalog()
      _ <- PluginBlockPolicy.unblock("messy", "spec")
    yield
      assert(!catalog.contains("- messy:"), s"blocked package must not render a line:\n$catalog")
      assertEquals(
        catalog.linesIterator.toList.last,
        "另有 3 个插件未载入（装载失败 2 / 已封禁 1）",
        s"absence note must count the blocked package:\n$catalog"
      )
  }

  test("健康摘要: 拒载 + 封禁 + 内容变更各一行（内容变更不缺席）") {
    for
      _ <- reset
      _ <- PluginRegistry.approve("skills-only")
      _ <- IO.sleep(20.millis)
      _ <- IO.blocking(
        os.write.append(tempRoot / "plugins" / "skills-only" / "skills" / "explore" / "SKILL.md", "\nmod\n")
      )
      _ <- PluginBlockPolicy.block("messy", "spec health", "spec")
      _ <- IO(PluginRegistry.invalidateCache())
      health <- PluginRegistry.healthSummary()
      _ <- PluginBlockPolicy.unblock("messy", "spec")
    yield
      val text = health.getOrElse(fail("anomalous registry must produce a health summary"))
      val lines = text.linesIterator.toList
      assert(
        lines.head.startsWith(
          "6 package(s) on disk, 4 loaded, 3 catalog-visible, 3 absent (load-failed 2 / blocked 1), "
        ),
        s"head counts must be complete: ${lines.head}"
      )
      assert(
        lines.head.endsWith("content-changed") && lines.head.matches(".*, \\d+ content-changed$"),
        s"head must count content changes: ${lines.head}"
      )
      assert(
        lines.exists(l => l.contains("[load-failed] empty:") && l.contains("neither")),
        s"rejected package must be listed with its reason: $text"
      )
      assert(
        lines.exists(l => l.contains("[blocked] messy:") && l.contains("spec health")),
        s"blocked package must be listed with its block reason: $text"
      )
      assert(
        lines.exists(l => l.contains("[content-changed] skills-only:") && l.contains("not intercepted")),
        s"content-changed package must be listed as NON-blocking: $text"
      )
  }

  // ── feature flag（§G.2）────────────────────────────────

  test("§G.2 flag: plugins.enabled=false → catalog 为空（目录不注入）") {
    for
      _ <- IO.blocking(os.write.over(tempRoot / "nebflow.json", """{"plugins":{"enabled":false}}"""))
      _ <- IO(PluginRegistry.invalidateCache())
      catalog <- PluginRegistry.renderCatalog()
      enabled <- PluginsConfig.enabled
      _ <- IO.blocking(os.write.over(tempRoot / "nebflow.json", "{}"))
      _ <- IO(PluginRegistry.invalidateCache())
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
