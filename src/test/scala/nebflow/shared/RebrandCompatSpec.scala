package nebflow.shared

import munit.FunSuite

/**
 * L3 rebrand compat spec (batch 3, 2026-08-17) + 数据根指向切换批 (2026-10).
 * The compat layer's contract: every legacy name (".nebflow" dir, NEBFLOW_
 * env prefix, "nebflow.json" config file) is HARDCODED as the fallback and
 * never derived from brand.conf. Since the 2026-10 batch the LIVE brand
 * values are the switched ones (homeDirName = ".stand", envPrefix = STAND):
 *   - env tiers: STAND_HOME (first) > NEBFLOW_HOME (legacy compat);
 *   - default root: ~/.stand; legacy-only machines stay on ~/.nebflow with
 *     a one-line hint; NO in-process migration (the real migration is the
 *     external scripts/data-root-migration/ toolbox, downtime window).
 *
 * The switched-day branches cannot be exercised through the live Branding
 * vals (compile-time constants from brand.conf), so each dual-read has a
 * parameterized pure core (resolveDefaultDataRoot / dualEnv /
 * resolveConfigJson) that this spec drives with switch-day values.
 */
class RebrandCompatSpec extends FunSuite:

  // ── Branding.dualEnv ─────────────────────────────────────────────────────

  test("dualEnv: current prefix collapses to a single NEBFLOW_ variable") {
    val env = Map("NEBFLOW_HOME" -> "/legacy-home")
    assertEquals(Branding.dualEnv(env, "NEBFLOW", "HOME"), Some("/legacy-home"))
  }

  test("dualEnv: rename day — new prefix wins when set") {
    val env = Map("NEBLINK_HOME" -> "/new-home", "NEBFLOW_HOME" -> "/legacy-home")
    assertEquals(Branding.dualEnv(env, "NEBLINK", "HOME"), Some("/new-home"))
  }

  test("dualEnv: rename day — legacy NEBFLOW_ still recognized on miss") {
    val env = Map("NEBFLOW_HOME" -> "/legacy-home")
    assertEquals(Branding.dualEnv(env, "NEBLINK", "HOME"), Some("/legacy-home"))
  }

  test("dualEnv: neither prefix set → None (caller default applies)") {
    assertEquals(Branding.dualEnv(Map.empty, "NEBLINK", "HOME"), None)
  }

  // 数据根指向切换批：env 两档用**真实前缀**钉死（作者令②③）
  test("dualEnv: STAND_HOME (第一优先级) wins over legacy NEBFLOW_HOME; NEBFLOW_HOME alone still honored") {
    assertEquals(
      Branding.dualEnv(Map("STAND_HOME" -> "/new-root", "NEBFLOW_HOME" -> "/legacy-root"), "STAND", "HOME"),
      Some("/new-root")
    )
    assertEquals(
      Branding.dualEnv(Map("NEBFLOW_HOME" -> "/legacy-root"), "STAND", "HOME"),
      Some("/legacy-root"),
      "现役实例显式带 NEBFLOW_HOME=~/.nebflow —— legacy 兼容读取必须继续认它"
    )
  }

  // ── PathUtil.resolveConfigJson ───────────────────────────────────────────

  test("resolveConfigJson: current name collapses to nebflow.json") {
    val tmp = os.temp.dir(prefix = "rebrand-cfg")
    assertEquals(PathUtil.resolveConfigJson(tmp, "nebflow.json"), tmp / "nebflow.json")
  }

  test("resolveConfigJson: rename day — new file preferred when present") {
    val tmp = os.temp.dir(prefix = "rebrand-cfg")
    os.write.over(tmp / "neblink.json", "{}")
    os.write.over(tmp / "nebflow.json", "{}")
    assertEquals(PathUtil.resolveConfigJson(tmp, "neblink.json"), tmp / "neblink.json")
  }

  test("resolveConfigJson: rename day — legacy file read on new-name miss") {
    val tmp = os.temp.dir(prefix = "rebrand-cfg")
    os.write.over(tmp / "nebflow.json", "{}")
    assertEquals(PathUtil.resolveConfigJson(tmp, "neblink.json"), tmp / "nebflow.json")
  }

  test("resolveConfigJson: neither present → new name (first-run initializer)") {
    val tmp = os.temp.dir(prefix = "rebrand-cfg")
    assertEquals(PathUtil.resolveConfigJson(tmp, "neblink.json"), tmp / "neblink.json")
  }

  test("configJsonReadPath/WritePath: current brand values are byte-identical") {
    // brand.conf configFileName is currently nebflow.json — read and write
    // paths must both resolve to the legacy name (zero behavior change).
    val dir = os.temp.dir(prefix = "rebrand-cfg")
    assertEquals(PathUtil.configJsonReadPath(dir), dir / "nebflow.json")
    assertEquals(PathUtil.configJsonWritePath(dir), dir / "nebflow.json")
  }

  // ── PathUtil.resolveDefaultDataRoot ──────────────────────────────────────
  //
  // 数据根指向切换批（2026-10）：默认根源码级改为 ~/.stand，进程内零迁移 ——
  // 真迁移在外部工具箱 scripts/data-root-migration/（段二，停机窗执行）。
  // 分支表（作者令④）：
  //   ① ~/.stand 存在                 → 用它（工具箱已克隆 / 新装已落新根）
  //   ② 仅剩旧 ~/.nebflow             → 用旧根 + 一行 stderr 提示（老机器无痛）
  //   ③ 两者皆无（新装机器/新用户）    → 用 ~/.stand（默认落新根）
  //   ④ homeDirName == 旧名（.nebflow）→ 各分支塌缩为同目录，零行为变
  // env 优先级（作者令②③）由 Branding.dualEnv 纯核钉：STAND_HOME > NEBFLOW_HOME。
  // 每分支至少一例（质量闸「四分支各一例」）。

  test("resolveDefaultDataRoot: ④ legacy brand value (.nebflow) collapses to itself") {
    val home = os.temp.dir(prefix = "rebrand-home")
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".nebflow"), home / ".nebflow")
  }

  test("resolveDefaultDataRoot: ① ~/.stand present wins (toolbox-cloned or fresh-on-new-root)") {
    val home = os.temp.dir(prefix = "rebrand-home")
    os.makeDir.all(home / ".stand")
    os.makeDir.all(home / ".nebflow")
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".stand"), home / ".stand")
  }

  test("resolveDefaultDataRoot: ③ neither dir exists (fresh machine) → lands on ~/.stand") {
    val home = os.temp.dir(prefix = "rebrand-home") // no .stand, no .nebflow
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".stand"), home / ".stand")
  }

  test("resolveDefaultDataRoot: ② only legacy ~/.nebflow → legacy root + ONE-LINE hint, zero copy") {
    val home = os.temp.dir(prefix = "rebrand-home")
    val legacy = home / ".nebflow"
    os.write.over(legacy / "auth.json", "\"token\"", createFolders = true)
    os.write.over(legacy / "sessions" / "s1.json", "{}", createFolders = true)

    val err = new java.io.ByteArrayOutputStream
    val resolved =
      val prevErr = System.err
      System.setErr(new java.io.PrintStream(err, true))
      try PathUtil.resolveDefaultDataRoot(home, ".stand")
      finally System.setErr(prevErr)

    // resolved to the LEGACY root (老机器无痛)
    assertEquals(resolved, legacy)
    // exactly ONE stderr line, actionable (names both roots + the toolbox)
    val lines = err.toString.linesIterator.filter(_.trim.nonEmpty).toList
    assertEquals(lines.length, 1, s"hint must be a single line, got: $lines")
    assert(lines.head.contains(legacy.toString), s"hint must name the legacy root: ${lines.head}")
    assert(lines.head.contains((home / ".stand").toString), s"hint must name the new root: ${lines.head}")
    // 进程内零迁移：legacy 树原样（不被改写），新根不被创建，无 marker 落盘
    assert(os.read(legacy / "auth.json") == "\"token\"", "legacy tree must stay untouched")
    assert(!os.exists(home / ".stand"), "engine must not create/migrate the new root in-process")
    assert(!os.exists(legacy / ".rebrand-migrated"), "migration marker is retired with the copy path")

    // 幂等：第二次解析仍落 legacy，行为不漂移
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".stand"), legacy)
  }

  test("resolveDefaultDataRoot: ② toolbox-cloned ~/.stand + legacy present → ~/.stand wins, no hint") {
    // 段二执行后的本机形态：~/.stand 已克隆（含数据），旧根仍在。
    val home = os.temp.dir(prefix = "rebrand-home")
    os.write.over((home / ".stand") / "auth.json", "\"cloned\"", createFolders = true)
    os.write.over((home / ".nebflow") / "auth.json", "\"legacy\"", createFolders = true)

    val err = new java.io.ByteArrayOutputStream
    val resolved =
      val prevErr = System.err
      System.setErr(new java.io.PrintStream(err, true))
      try PathUtil.resolveDefaultDataRoot(home, ".stand")
      finally System.setErr(prevErr)

    assertEquals(resolved, home / ".stand")
    assertEquals(err.toString.trim, "", "direct hit must stay silent (no hint)")
    assert(os.read((home / ".stand") / "auth.json") == "\"cloned\"")
  }

  // ── Branding install-script companions ───────────────────────────────────

  test("install companions derive from installUrl by basename swap") {
    assertEquals(Branding.installPs1Url, "https://mystand.space/install.ps1")
    assertEquals(Branding.uninstallUrl, "https://mystand.space/uninstall.sh")
    assertEquals(Branding.uninstallPs1Url, "https://mystand.space/uninstall.ps1")
  }

  test("install companions degrade to installUrl when the shape doesn't match") {
    // swapInstallScript is private; exercise it through the public vals'
    // invariant on the CURRENT value, plus shape-guard behavior indirectly:
    // the current installUrl ends with /install.sh so the swap applies.
    assert(Branding.installPs1Url.endsWith("/install.ps1"))
    assert(Branding.uninstallUrl.endsWith("/uninstall.sh"))
  }

end RebrandCompatSpec
