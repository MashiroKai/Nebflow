package nebflow.cli

import munit.FunSuite
import nebflow.shared.{Branding, Defaults}

/**
 * 双实例并存批（作者令 2026-10-30 08:29 逐字「开启动 stand 的命令是 nebflow。我们需要把 KAI
 * 迁移到正常的 stand 使用。和 nebflow 隔离。这样 nebflow 和 stand 可以同时使用。」；
 * 端口判据换代为双生分家批 2026-10-31 作者裁定「给 nebflow 独立默认端口，如 6140 之类」）：
 * 两个命令品牌的**默认值契约**钉。
 *
 *  命令       数据根（无 env）   默认端口   入口                       jar 位
 *  stand      ~/.stand          6138      ops/bin/stand              ENTRY_DIR/versions 指针
 *  nebflow    ~/.nebflow        6140      ops/bin/nebflow            ENTRY_DIR/nebflow-assembly-*
 *
 * 引擎侧品牌感知单源（[[Defaults.brandDefaultPortFor]]）：stand 构建 6138、nebflow
 * 构建 6140（其自有独立默认；考古：8080 是对调前的全引擎主默认，从非独立默认）。
 * 8080 自此只属 9.19 自有根实例（经 NEBFLOW_GATEWAY_PORT=8080 / --port 8080 管辖）。
 * CLI 侧真值住在 `ops/bin/stand-cli-lib.sh` 的 BRAND-MAP，本 spec 用**文本对账**
 * 把两侧钉在一起 —— 任一侧单边改默认值，这里先红。
 */
class CliBrandDefaultsSpec extends FunSuite:

  private val libSource: String =
    val p = java.nio.file.Path.of("ops/bin/stand-cli-lib.sh").toFile
    assert(p.exists(), s"CLI 权威副本缺失: ${p.getAbsolutePath}")
    scala.io.Source.fromFile(p).mkString

  test("engine native default port stays 6138 (the stand brand line)") {
    assertEquals(Defaults.GatewayPort, 6138)
  }

  test("nebflow brand default port is 6140 and MUST differ from the stand default") {
    assertEquals(Defaults.NebflowBrandGatewayPort, 6140)
    assertNotEquals(
      Defaults.GatewayPort,
      Defaults.NebflowBrandGatewayPort,
      "双生分家: 两命令品牌的缺省口必须错开"
    )
  }

  test("legacyPortOf maps both command brands and never invents a number") {
    assertEquals(Defaults.legacyPortOf("stand"), "6138")
    assertEquals(Defaults.legacyPortOf("nebflow"), "6140")
    assertEquals(Defaults.legacyPortOf("something-else"), "?")
  }

  test("data-root default derives from brand.conf and is ~/.stand (not the legacy ~/.nebflow)") {
    assertEquals(Branding.homeDirName, ".stand")
    assertNotEquals(Branding.homeDirName, "nebflow")
  }

  test("CLI BRAND-MAP defaults agree with the engine-side constants (text reconciliation)") {
    assert(libSource.contains("stand)   printf '6138\\n'"), "stand 缺省口 6138 在 CLI 库中缺失/漂移")
    assert(libSource.contains("nebflow) printf '6140\\n'"), "nebflow 缺省口 6140 在 CLI 库中缺失/漂移")
    assert(libSource.contains("${HOME:-}/.stand"), "stand 缺省家 ~/.stand 在 CLI 库中缺失/漂移")
    assert(libSource.contains("${HOME:-}/.nebflow"), "nebflow 缺省家 ~/.nebflow 在 CLI 库中缺失/漂移")
  }

  test("CLI entries exist as executable-by-shebang scripts, one per command brand") {
    for (f, brand) <- List(("ops/bin/stand", "stand"), ("ops/bin/nebflow", "nebflow")) do
      val src = scala.io.Source.fromFile(java.nio.file.Path.of(f).toFile).mkString
      assert(src.startsWith("#!/bin/bash"), s"$f 必须是 bash 入口")
      assert(
        src.contains(s"_STAND_CLI_BRAND=$brand"),
        s"$f 的品牌壳必须声明 _STAND_CLI_BRAND=$brand（两入口共用一份实现库）"
      )
  }

  test("CLI env isolation: each entry reads only its own home/port variables") {
    // stand 入口不得回落 NEBFLOW_HOME；nebflow 入口不得读 STAND_HOME（2026-10-27 隔离批契约，
    // 本批未改其语义 —— 这里只在默认值契约旁补一个文本钉子）。
    assert(libSource.contains("stand)   STAND_HOME=\"${STAND_HOME:-$HOME/.stand}\""))
    assert(libSource.contains("nebflow) STAND_HOME=\"${NEBFLOW_HOME:-$HOME/.nebflow}\""))
  }

end CliBrandDefaultsSpec