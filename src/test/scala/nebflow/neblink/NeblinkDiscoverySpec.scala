package nebflow.neblink

import munit.FunSuite
import scala.concurrent.duration.*

/**
 * 退避曲线钉子（2026-09-13 重写：**测真实现**，不再复制一份阈值）。
 *
 * 修前本 spec 在文件内**复制**了一份 `delayForFailures` 来断言，等于「测了个副本」——
 * 源码改成任何值它都照绿。实现已收归伴生对象 `NeblinkDiscovery.delayForFailures`
 * （纯函数，无需构造 NeblinkService/presence/discovery），此处直接钉它。
 */
class NeblinkDiscoverySpec extends FunSuite:

  test("0-2 failures → 30s (normal interval, a few retries allowed)"):
    assertEquals(NeblinkDiscovery.delayForFailures(0), 30.seconds)
    assertEquals(NeblinkDiscovery.delayForFailures(1), 30.seconds)
    assertEquals(NeblinkDiscovery.delayForFailures(2), 30.seconds)

  test("3-5 failures → 60s (slow down)"):
    assertEquals(NeblinkDiscovery.delayForFailures(3), 60.seconds)
    assertEquals(NeblinkDiscovery.delayForFailures(4), 60.seconds)
    assertEquals(NeblinkDiscovery.delayForFailures(5), 60.seconds)

  test("6+ failures → 45s cap（RC-3d 客户端半边：必须 < 服务端会话 TTL 90s）"):
    assertEquals(NeblinkDiscovery.delayForFailures(6), 45.seconds)
    assertEquals(NeblinkDiscovery.delayForFailures(100), NeblinkDiscovery.HeartbeatBackoffCap)
    // 口径钉子（数值硬断言之一，防「把封顶改回 ≥ TTL」的静默回归）：
    assert(
      NeblinkDiscovery.HeartbeatBackoffCap < 90.seconds,
      "心跳退避封顶必须严格小于服务端 DEVICE_LIVENESS_TTL(90s)，否则故障期会把在册设备挤出推送扇出表"
    )
end NeblinkDiscoverySpec
