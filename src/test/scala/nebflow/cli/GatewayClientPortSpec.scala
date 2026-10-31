package nebflow.cli

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{Defaults, PathUtil}

/**
 * CLI 侧端口解析兜底钉（端口对调落地批 2026-10-25；链换代为双生分家批 2026-10-31）：
 * `GatewayClient.readPort` 的解析链 = 显式 `--port` override ＞ `GATEWAY_PORT` env ＞
 * config `port` 键 ＞ 品牌缺省口（`Defaults.brandDefaultPort` = stand 构建 6138）——
 * 与引擎绑定面 `GatewayConfig.resolvePort` 同链同序。旧默认 8080 的分叉（ops 守卫探
 * 6138、裸起引擎落 8080，微信入站全断的 2026-10-25 事故面）不允许在 CLI 侧复活。
 *
 * config 腿读**真实数据根**的配置文件，故本 spec 全程把 dataRoot 换到临时空目录，
 * 保证「兜底档」前提（env/config 均沉默）可复现，不依赖宿主机的 ~/.stand 内容。
 */
class GatewayClientPortSpec extends FunSuite:

  private def envSilent: Boolean =
    sys.env.get("STAND_GATEWAY_PORT").orElse(sys.env.get("NEBFLOW_GATEWAY_PORT")).flatMap(_.toIntOption).isEmpty

  private def withTempRoot[A](configJson: Option[String])(f: => A): A =
    val tmp = os.temp.dir(prefix = "gw-client-port-spec")
    val saved = PathUtil.dataRoot
    try
      PathUtil.setDataRoot(tmp)
      configJson.foreach(j => os.write.over(tmp / "nebflow.json", j, createFolders = true))
      f
    finally
      PathUtil.setDataRoot(saved)
      os.remove.all(tmp)

  test("readPort falls back to the brand default when --port / env / config all stay silent"):
    assume(envSilent, "GATEWAY_PORT env is set in this environment — default leg unreachable")
    withTempRoot(None) {
      GatewayClient.resetPort()
      assertEquals(GatewayClient.readPort.unsafeRunSync(), Defaults.brandDefaultPort)
    }

  test("readPort honours the config 'port' key below --port and above the brand default"):
    // env 腿优先于 config 腿 —— 引擎会话常注入 STAND_GATEWAY_PORT，此时本用例的
    // config 腿不可达（链序覆盖在 GatewayPortDefaultSpec.resolvePort 纯矩阵钉）。
    assume(envSilent, "GATEWAY_PORT env is set in this environment — config leg unreachable here")
    withTempRoot(Some("""{"port": 6199}""")) {
      GatewayClient.resetPort()
      assertEquals(GatewayClient.readPort.unsafeRunSync(), 6199, "config port key must be honoured")
      GatewayClient.setPort(8097)
      assertEquals(GatewayClient.readPort.unsafeRunSync(), 8097, "explicit --port override beats config")
      GatewayClient.resetPort()
    }

  test("readPort honours an explicit override above env and default (chain order pinned)"):
    try
      GatewayClient.setPort(8097)
      assertEquals(GatewayClient.readPort.unsafeRunSync(), 8097)
    finally GatewayClient.resetPort()

end GatewayClientPortSpec
