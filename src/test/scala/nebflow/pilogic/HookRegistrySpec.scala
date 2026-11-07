package nebflow.pilogic

import cats.effect.IO
import munit.CatsEffectSuite

/** 注册面机械校验（A-7 装配期拒装）+ COW 快照语义（A-9）+ 总序确定性。 */
class HookRegistrySpec extends CatsEffectSuite:

  private val proc: Interceptor[IO] = _ => IO.pure(HookEffect.Proceed)
  private val noopObs: Observer[IO] = _ => IO.unit

  test("returnRead=false 位点只收 Observer：注册 Interceptor ⇒ 装配期拒（A-7）"):
    intercept[IllegalArgumentException] {
      HookRegistry.empty[IO].withInterceptor(HookPoint.MessageDelta, proc)
    }

  test("returnRead=true 位点只收 Interceptor：注册 Observer ⇒ 装配期拒（A-7）"):
    intercept[IllegalArgumentException] {
      HookRegistry.empty[IO].withObserver(HookPoint.ToolPre, noopObs)
    }

  test("错误档只可收紧不可放宽：ToolPre 缺省 Block，注册给 Continue ⇒ 拒（§Ⅲ.4）"):
    intercept[IllegalArgumentException] {
      HookRegistry.empty[IO]
        .withInterceptor(HookPoint.ToolPre, proc, errorPolicy = Some(HookErrorPolicy.Continue))
    }

  test("快照 COW：拍快照后再注册，旧快照不可见、新注册表可见（A-9）"):
    val r0 = HookRegistry.empty[IO]
    val snap0 = r0.snapshot
    val r1 = r0.withObserver(HookPoint.MessageDelta, noopObs)
    assertEquals(snap0.hookIdsAt(HookPoint.MessageDelta).size, 0)
    assertEquals(r1.snapshot.hookIdsAt(HookPoint.MessageDelta).size, 1)

  test("总序确定性：Priority(rank) 先于 Append，同档按注册序"):
    val r = HookRegistry.empty[IO]
      .withInterceptor(HookPoint.ToolPre, proc)                                 // Append, idx0
      .withInterceptor(HookPoint.ToolPre, proc, order = HookOrder.Priority(2))  // idx1
      .withInterceptor(HookPoint.ToolPre, proc, order = HookOrder.Priority(1))  // idx2
    assertEquals(
      r.snapshot.hookIdsAt(HookPoint.ToolPre),
      Vector(HookId("hook-2"), HookId("hook-1"), HookId("hook-0")),
    )

  test("without 注销 = 新快照，原注册表不受影响（COW）"):
    val r1 = HookRegistry.empty[IO].withInterceptor(HookPoint.ToolPre, proc)
    val r2 = r1.without(HookId("hook-0"))
    assertEquals(r1.snapshot.size, 1)
    assertEquals(r2.snapshot.size, 0)
