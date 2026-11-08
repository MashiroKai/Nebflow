package nebflow.core.dataroot

import java.net.InetSocketAddress
import java.nio.file.Files

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import nebflow.core.root.{AccountRoot, ActiveAccount, LoginGuard}
import nebflow.gateway.GatewayConfig
import nebflow.shared.{Branding, PathUtil}

/**
 * 三层数据根批 · b1-guards（tenant-full-flow-batch2）—— **T1 的守卫判定链 spec**：
 * `LoginGuard.checkLoginAllowed` 的拒绝臂/放行臂全链（指针 + 机器端口活实例判据）。
 *
 * == 断言原文（派单 T1 / 设计 §8.1(c)「换账号硬拒」行）==
 * 另一账号引擎在跑（端口活实例 + 指针=A）时执行 `stand login`（B）⇒ 拒，
 * refusal 含 `account-switch-requires-logout`；文案点名两账号 + 含「先登出再登录」建议
 * （设计 §4.4）。放行臂三条（同 §4.4）：无指针 / 同账号 / 端口上没有本品牌活实例 ⇒ 放行；
 * 其中「无指针直接放行」是 Guards.scala:66-67 的既有语义，本件钉住不退化。
 *
 * == 红绿态登记（测试纪律）==
 * 守卫判定符号（`LoginGuard.checkLoginAllowed`）在 B1 契约腿已实现（本件引用的符号全部已存在）
 * ⇒ 本件自诞生即绿，角色 = **判定语义的回归钉**；T1 的红态（现状登录照常放行）在 CLI 接线面
 * —— `nebflow.cli.LoginGateCliSpec` 引用尚未存在的 `signInWith` 门参数 ⇒ `Test/compile` 红，
 * 接线后转绿。本件同时填 `OwnerGuardSpec.scala:26-29` 留白的「拒绝臂」：其注明该臂留给
 * 隔离实例腿（不许起实例、不联网）—— 本件以**环回临时口 + 健康面假活实例**实现同一覆盖
 * （不起真实例、零外联，端口用后即关）。
 *
 * == 隔离纪律 ==
 * 机器层根/有效根一律 `Files.createTempDirectory`（绝不用 `~/.stand`，用毕恢复原值并删净）；
 * 端口 = 临时环回口（`GatewayConfig.setPort` 进程级覆盖，是 Main 生产启动序同一面
 * Main.scala:199；探活判定链 `checkPort`/`probeOnce`/`probeNebflow` 全部走真实实现）。
 * 覆盖无复位 API ⇒ 本件会残留一次临时口覆盖 —— 测试 JVM 内零影响（全仓测试面零处调
 * `GatewayConfig.load`，端口链断言走的都是纯函数 `resolvePort`/`configFilePort`，逐字核实过）。
 */
class LoginGuardLoginGateSpec extends munit.FunSuite:

  private val Ts = "2026-11-06T04:00:00Z"
  private val SubA = "logto|user-A"
  private val TargetB = "b-user@example.com"

  /** 样本根：临时目录（绝不用 ~/.stand），用毕删净。 */
  private def tempRoot(): os.Path = os.Path(Files.createTempDirectory("b1-gate-"))

  /** 把进程级机器层根/有效根钉到样本根跑用例，结束恢复原值（启动序的等价面）。 */
  private def withPinnedRoots[T](f: os.Path => T): T =
    val prevData = PathUtil.dataRoot
    val prevMachine = PathUtil.machineRoot
    val root = tempRoot()
    try
      PathUtil.setMachineRoot(root)
      PathUtil.setDataRoot(root)
      f(root)
    finally
      PathUtil.setDataRoot(prevData)
      PathUtil.setMachineRoot(prevMachine)
      os.remove.all(root)

  /** 拿一个当前空闲的临时端口（用后本用例独占它）。 */
  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  /**
   * 环回假活实例：在本品牌健康面（`/api/health`）上应答本品牌 `product` 词
   * ⇒ `SingleInstanceGuard.checkPort` 的探活链（strictBindOk 失败 → connectAccepted →
   * probeNebflow 200 + 已知品牌词）把它归为 NebflowInstance —— 与真实例同判据。
   * listener pid = 本测试 JVM（被 occupantHomeOf 的自身过滤剔除）⇒ 无 home 证据 ⇒ 同家。
   */
  private def withFakeLiveInstance[T](port: Int)(f: => T): T =
    val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/api/health", exchange =>
      val body =
        s"""{"product":"${Branding.lowerName}","status":"ok","version":"b1-guards-spec"}""".getBytes("UTF-8")
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.length.toLong)
      val out = exchange.getResponseBody
      try out.write(body)
      finally
        out.close()
        exchange.close()
    )
    server.setExecutor(null)
    server.start()
    try f
    finally server.stop(0)

  /** 预置「机器已登录 A」：机器层指针 = A（登录腿 writePointer 的等价面）。 */
  private def signedInAs(root: os.Path, sub: String, email: String): Unit =
    AccountRoot.writePointer(root, ActiveAccount(sub, email, Ts))

  // ── 拒绝臂（T1 本体）：指针=A + 端口本品牌活实例 ⇒ B 登录必须拒 ─────────────

  test("T1: 指针=A + 端口本品牌活实例 ⇒ B 登录拒（account-switch-requires-logout，双点名 + 先登出建议）"):
    withPinnedRoots { root =>
      signedInAs(root, SubA, "a@example.com")
      val port = freePort()
      GatewayConfig.setPort(port) // 探活口 = 真实解析链（--port 覆盖腿，Main.scala:199 同面）
      withFakeLiveInstance(port) {
        LoginGuard.checkLoginAllowed(TargetB).unsafeRunSync() match
          case Left(refusal) =>
            assertEquals(refusal.code, "account-switch-requires-logout", "稳定码逐字（§4.4）")
            assert(refusal.message.contains(SubA), s"文案点名机器在跑的账号: ${refusal.message}")
            assert(refusal.message.contains(TargetB), s"文案点名目标账号: ${refusal.message}")
            assert(refusal.message.contains("stand logout"), s"先登出建议必须可行动: ${refusal.message}")
            assert(
              refusal.message.contains("stand login"),
              s"再登录建议必须可行动（「先登出再登录」逐字建议集）: ${refusal.message}"
            )
          case Right(_) =>
            fail("同机 A 在跑（活实例+指针=A）时 B 登录必须拒 —— 放行 = 登录即换根的谎话报错缺口")
      }
    }

  // ── 放行臂三条（§4.4：无指针 / 同账号 / 端口无本品牌活实例 ⇒ 放行） ──────────

  test("T1 放行臂: 无指针 ⇒ 直接放行（Guards.scala:66-67 既有语义，不退化）"):
    withPinnedRoots { root =>
      val port = freePort()
      GatewayConfig.setPort(port)
      // 无指针 + 端口上摆着假活实例：短路口在指针判据（None ⇒ Right），根本不探端口
      withFakeLiveInstance(port) {
        assertEquals(
          LoginGuard.checkLoginAllowed(TargetB).unsafeRunSync(),
          Right(()),
          "无指针 = 没有任何账号可切 ⇒ 放行（既有语义）"
        )
      }
    }

  test("T1 放行臂: 同账号（target == 指针 accountSub）⇒ 放行（短路与端口无关）"):
    withPinnedRoots { root =>
      signedInAs(root, SubA, "a@example.com")
      val port = freePort()
      GatewayConfig.setPort(port)
      withFakeLiveInstance(port) {
        assertEquals(
          LoginGuard.checkLoginAllowed(SubA).unsafeRunSync(),
          Right(()),
          "同账号重登不拒（§4.4 同账号 ⇒ 放行）"
        )
      }
    }

  test("T1 放行臂: 端口空闲（无活实例）⇒ 放行（指针在也不拒 —— 没有实例占端口就没有谎话报错）"):
    withPinnedRoots { root =>
      signedInAs(root, SubA, "a@example.com")
      val port = freePort()
      GatewayConfig.setPort(port)
      // 不摆假活实例：strictBindOk 成功 ⇒ PortFree ⇒ 放行
      assertEquals(
        LoginGuard.checkLoginAllowed(TargetB).unsafeRunSync(),
        Right(()),
        "无活实例 ⇒ 放行（§4.4）"
      )
    }

end LoginGuardLoginGateSpec
