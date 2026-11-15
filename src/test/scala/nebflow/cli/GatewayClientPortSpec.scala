package nebflow.cli

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{PathUtil, PortRefusal}

/** Port resolution must respect explicit configuration and refuse unconfigured isolated roots. */
class GatewayClientPortSpec extends FunSuite:

  private def envSilent: Boolean =
    sys.env.get("STAND_GATEWAY_PORT").orElse(sys.env.get("NEBFLOW_GATEWAY_PORT")).flatMap(_.toIntOption).isEmpty

  private def withTempRoot[A](configJson: Option[String])(f: => A): A =
    val tmp = os.temp.dir(prefix = "gw-client-port-spec")
    val saved = PathUtil.dataRoot
    try
      PathUtil.setDataRoot(tmp)
      configJson.foreach(j => os.write.over(tmp / nebflow.shared.Branding.configFileName, j, createFolders = true))
      f
    finally
      PathUtil.setDataRoot(saved)
      os.remove.all(tmp)

  test("readPort refuses the brand default for an unconfigured isolated root"):
    assume(envSilent, "GATEWAY_PORT env is set in this environment — default leg unreachable")
    withTempRoot(None) {
      GatewayClient.resetPort()
      intercept[PortRefusal](GatewayClient.readPort.unsafeRunSync())
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
