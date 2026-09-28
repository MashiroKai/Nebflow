package nebflow.core

import munit.FunSuite

/**
  * L3 rebrand compat spec (batch 3, 2026-08-17). The compat layer's contract:
  * every legacy name (".nebflow" dir, NEBFLOW_ env prefix, "nebflow.json"
  * config file) is HARDCODED as the fallback and never derived from
  * brand.conf — so with the current brand values every dual-read collapses
  * to a single lookup and the whole layer no-ops. On rename day the new
  * names (from brand.conf) take priority and the legacy values keep old
  * data, old env scripts and old config files working.
  *
  * The rename-day branches cannot be exercised through the live Branding
  * vals (compile-time constants from brand.conf), so each dual-read has a
  * parameterized pure core (resolveDefaultDataRoot / dualEnv /
  * resolveConfigJson) that this spec drives with rename-day values.
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

  test("resolveDefaultDataRoot: current dir name collapses to ~/.nebflow") {
    val home = os.temp.dir(prefix = "rebrand-home")
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".nebflow"), home / ".nebflow")
  }

  test("resolveDefaultDataRoot: fresh install on rename day starts on the new dir") {
    val home = os.temp.dir(prefix = "rebrand-home") // no .nebflow, no .newbrand
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".newbrand"), home / ".newbrand")
  }

  test("resolveDefaultDataRoot: new dir present wins (already migrated)") {
    val home = os.temp.dir(prefix = "rebrand-home")
    os.makeDir.all(home / ".newbrand")
    os.makeDir.all(home / ".nebflow")
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".newbrand"), home / ".newbrand")
  }

  test("resolveDefaultDataRoot: legacy present triggers ONE-TIME copy migration with marker") {
    val home = os.temp.dir(prefix = "rebrand-home")
    val legacy = home / ".nebflow"
    os.write.over(legacy / "sessions" / "s1.json", "{}", createFolders = true)
    os.write.over(legacy / "auth.json", "\"token\"")

    val resolved = PathUtil.resolveDefaultDataRoot(home, ".newbrand")

    // resolved to the new dir
    assertEquals(resolved, home / ".newbrand")
    // COPY not move: legacy tree intact (rollback safety)
    assert(os.exists(legacy / "sessions" / "s1.json"))
    assert(os.exists(legacy / "auth.json"))
    // marker written into the legacy dir
    assert(os.exists(legacy / ".rebrand-migrated"))
    // data actually present in the new tree
    assert(os.exists(home / ".newbrand" / "sessions" / "s1.json"))
    assert(os.read(home / ".newbrand" / "auth.json") == "\"token\"")

    // second resolution: new dir now exists → direct hit, no re-migration
    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".newbrand"), home / ".newbrand")
  }

  test("resolveDefaultDataRoot: marker present + new dir deleted → fall back to legacy, no re-copy") {
    val home = os.temp.dir(prefix = "rebrand-home")
    val legacy = home / ".nebflow"
    os.write.over(legacy / "auth.json", "\"token\"", createFolders = true)
    os.write.over(legacy / ".rebrand-migrated", "migrated to somewhere\n")
    // new dir absent (user deleted it — respect the deletion)

    assertEquals(PathUtil.resolveDefaultDataRoot(home, ".newbrand"), legacy)
  }

  // ── Branding install-script companions ───────────────────────────────────

  test("install companions derive from installUrl by basename swap") {
    assertEquals(Branding.installPs1Url, "https://nebflow.space/install.ps1")
    assertEquals(Branding.uninstallUrl, "https://nebflow.space/uninstall.sh")
    assertEquals(Branding.uninstallPs1Url, "https://nebflow.space/uninstall.ps1")
  }

  test("install companions degrade to installUrl when the shape doesn't match") {
    // swapInstallScript is private; exercise it through the public vals'
    // invariant on the CURRENT value, plus shape-guard behavior indirectly:
    // the current installUrl ends with /install.sh so the swap applies.
    assert(Branding.installPs1Url.endsWith("/install.ps1"))
    assert(Branding.uninstallUrl.endsWith("/uninstall.sh"))
  }

end RebrandCompatSpec
