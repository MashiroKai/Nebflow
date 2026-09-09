package nebflow.core.project

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * NodeReportRegistry 单测（blocked 结构化信号批 20260909，spec §5.2 #1/#10；同日作者裁定泛化 NodeReport 统一三语义命名，形态与语义不变）：
 * - register → drain 消费语义（take-and-remove，跨消费不残留）
 * - last-write-wins：同会话重复申报覆盖（最终意图优先，spec §6）
 * - remove 清理钩子（不消费；幂等）
 * - 会话键隔离（A 的申报不影响 B）
 * 真实 Ref 语义（无 mock），BgTaskRegistrySpec 同款测法。
 */
class NodeReportRegistrySpec extends CatsEffectSuite:

  private val fbA = BlockedFeedback("upstream-incomplete", "上游 X 未完成", "需上游先完成")
  private val fbB = BlockedFeedback("external-dependency", "等待 API key", "提供凭据后重派")

  override def munitIOTimeout: FiniteDuration = 30.seconds

  test("register then drain: returns the feedback and clears the slot (take-and-remove)") {
    for
      _ <- NodeReportRegistry.register("sid-1", fbA)
      first <- NodeReportRegistry.drain("sid-1")
      second <- NodeReportRegistry.drain("sid-1")
    yield
      assertEquals(first, Some(fbA), "first drain must return the registered feedback")
      assertEquals(second, None, "drain must remove the entry (no cross-consumption residue)")
  }

  test("last-write-wins: repeated registration overwrites (final intent wins, spec §6)") {
    for
      _ <- NodeReportRegistry.register("sid-2", fbA)
      _ <- NodeReportRegistry.register("sid-2", fbB)
      drained <- NodeReportRegistry.drain("sid-2")
    yield assertEquals(drained, Some(fbB), "second declaration must win (last-write-wins)")
  }

  test("session isolation: A's declaration never leaks into B's drain") {
    for
      _ <- NodeReportRegistry.register("sid-a", fbA)
      bDrain <- NodeReportRegistry.drain("sid-b")
      aDrain <- NodeReportRegistry.drain("sid-a")
    yield
      assertEquals(bDrain, None, "B must not consume A's declaration")
      assertEquals(aDrain, Some(fbA), "A's own declaration must be intact")
  }

  test("remove: cleanup hook drops the entry without consuming; idempotent") {
    for
      _ <- NodeReportRegistry.register("sid-3", fbA)
      _ <- NodeReportRegistry.remove("sid-3")
      _ <- NodeReportRegistry.remove("sid-3")
      drained <- NodeReportRegistry.drain("sid-3")
    yield assertEquals(drained, None, "removed entry must not be drainable (cancel/fail sweep path)")
  }

  test("drain after remove: drain is also an idempotent no-op on empty slot") {
    for
      drained <- NodeReportRegistry.drain("never-registered")
    yield assertEquals(drained, None)
  }
end NodeReportRegistrySpec
