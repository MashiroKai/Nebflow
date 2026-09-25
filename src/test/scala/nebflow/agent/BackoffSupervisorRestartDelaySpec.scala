/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.agent

import munit.FunSuite

import scala.concurrent.duration.*

/**
 * BackoffSupervisor.restartDelayMs 纯函数钉(Phase 3 去重迁移的活跃覆盖)。
 *
 * 背景:BackoffSupervisorCancelSpec 只测 Cancelled 路径,crash 退避路径此前
 * 零活跃覆盖;本 spec 是纯函数确定性测试(注入 jitterMs),不跑 actor 系统。
 *
 * 语义对照(迁移前原式 BackoffSupervisor.scala:192-197,行为逐值保持):
 *   delay = min(minBackoff × 2^currentRestarts, maxBackoff) + 抖动[0,1000)ms
 * 即:初值 = minBackoff(生产默认 5s);倍率 ×2(1L << n);封顶 maxBackoff
 * (默认 60s)只钳确定性部分;抖动在封顶**之后**相加——总延迟可超 maxBackoff
 * 至多 999ms(与 shared Retry Jitter.Additive「先加抖动再封顶」不同,故抖动
 * 不迁公共层)。生产可达域:唯一调用方 NodeRunner.spawnSupervisedAdapter 用
 * 默认 maxRestarts=2 → currentRestarts ∈ {0, 1};本 spec 同时钉全公式。
 */
class BackoffSupervisorRestartDelaySpec extends FunSuite:

  private val noJitter: () => Long = () => 0L

  test("指数序列(默认参数面 5s/60s,抖动=0): 5s,10s,20s,40s,60s,60s") {
    assertEquals(BackoffSupervisor.restartDelayMs(0, 5.seconds, 60.seconds, noJitter), 5000L)
    assertEquals(BackoffSupervisor.restartDelayMs(1, 5.seconds, 60.seconds, noJitter), 10000L)
    assertEquals(BackoffSupervisor.restartDelayMs(2, 5.seconds, 60.seconds, noJitter), 20000L)
    assertEquals(BackoffSupervisor.restartDelayMs(3, 5.seconds, 60.seconds, noJitter), 40000L)
    assertEquals(BackoffSupervisor.restartDelayMs(4, 5.seconds, 60.seconds, noJitter), 60000L, "min(5s×16, 60s) 封顶")
    assertEquals(BackoffSupervisor.restartDelayMs(9, 5.seconds, 60.seconds, noJitter), 60000L)
  }

  test("非默认参数面(1s/8s): 1s,2s,4s,8s,8s") {
    assertEquals(BackoffSupervisor.restartDelayMs(0, 1.second, 8.seconds, noJitter), 1000L)
    assertEquals(BackoffSupervisor.restartDelayMs(1, 1.second, 8.seconds, noJitter), 2000L)
    assertEquals(BackoffSupervisor.restartDelayMs(2, 1.second, 8.seconds, noJitter), 4000L)
    assertEquals(BackoffSupervisor.restartDelayMs(3, 1.second, 8.seconds, noJitter), 8000L)
    assertEquals(BackoffSupervisor.restartDelayMs(5, 1.second, 8.seconds, noJitter), 8000L)
  }

  test("抖动在封顶之后相加: 总延迟可超 maxBackoff(至多 +999ms)") {
    assertEquals(
      BackoffSupervisor.restartDelayMs(9, 5.seconds, 60.seconds, () => 999L),
      60999L,
      "确定性部分封顶 60s 后 +999ms——「先加再封顶」口径会得 60000"
    )
    assertEquals(BackoffSupervisor.restartDelayMs(0, 5.seconds, 60.seconds, () => 1L), 5001L)
  }

end BackoffSupervisorRestartDelaySpec
