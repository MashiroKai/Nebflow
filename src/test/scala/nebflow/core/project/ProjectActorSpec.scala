package nebflow.core.project

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * ProjectActor 回归测试（#28 0b QA 打回修复）。
 *
 * Bug 1（P1）：ttlScanner 的 `def loop` 递归构造 IO 无 IO.defer——旧版在
 * 真实网关启动（GatewayMain 启动即 `ttlScanner(30).start`）时 StackOverflowError
 * 崩溃（QA e2e：instance.log `java.lang.StackOverflowError` at loop$1）。
 * 本测试：interval=0 紧密循环（ProjectRuntimeRegistry 空 → 迭代体为空）——
 * 修复后不爆栈 + timeoutTo 可取消即通过；旧版（无 IO.defer）在此 runtime 下
 * 若同样爆栈则测试红（验红回归）。
 */
class ProjectActorSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  test("ttlScanner: recursive loop does not stack overflow (Bug 1 regression)") {
    // 用 10ms 真异步间隔：约 30 轮迭代 + timeoutTo 取消（interval=0 的 IO.sleep
    // 同步完成 → 同步紧密循环无法取消，故不用 0）。
    // 旧版（`*> loop` 无 IO.defer）构造期 eager 递归 → StackOverflowError（验红实证）。
    ProjectActor.ttlScanner(10.millis).timeoutTo(300.millis, IO.unit)
  }

end ProjectActorSpec
