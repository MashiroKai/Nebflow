package nebflow.cli

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.Defaults

/**
 * CLI 侧端口解析兜底钉（端口对调落地批 2026-10-25）：`GatewayClient.readPort`
 * 的解析链 = 显式 `--port` override ＞ `GATEWAY_PORT` env ＞ 引擎默认口，兜底
 * 档与 `GatewayConfig` 同源（`Defaults.GatewayPort` = 6138）——旧默认 8080 的
 * 分叉（ops 守卫探 6138、裸起引擎落 8080，微信入站全断的 2026-10-25 事故面）
 * 不允许在 CLI 侧复活。
 */
class GatewayClientPortSpec extends FunSuite:

  // readPort 的兜底档只在 env 沉默时可达；CI/本机若导出了端口 env，本档跳过
  // （env 生效优先级本身是既定语义，不是本钉对象）。
  private def envSilent: Boolean =
    sys.env.get("STAND_GATEWAY_PORT").orElse(sys.env.get("NEBFLOW_GATEWAY_PORT")).flatMap(_.toIntOption).isEmpty

  test("readPort falls back to Defaults.GatewayPort when neither --port nor env speaks"):
    assume(envSilent, "GATEWAY_PORT env is set in this environment — default leg unreachable")
    GatewayClient.resetPort()
    assertEquals(GatewayClient.readPort.unsafeRunSync(), Defaults.GatewayPort)

  test("readPort honours an explicit override above env and default (chain order pinned)"):
    try
      GatewayClient.setPort(8097)
      assertEquals(GatewayClient.readPort.unsafeRunSync(), 8097)
    finally GatewayClient.resetPort()

end GatewayClientPortSpec
