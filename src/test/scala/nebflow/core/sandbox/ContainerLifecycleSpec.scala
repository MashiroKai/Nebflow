package nebflow.core.sandbox

import nebflow.core.tools.ToolContext

/**
 * per-task 容器生命周期骨架的**纯逻辑定向测试**（方案 §4 T6 验收口径）。
 *
 * 环境纪律：**无 docker、本机不起任何容器**（`ContainerBackend.Fake` 内存实现）；
 * 时间由注入时钟驱动（无 sleep、无真实等待）——全部用例同步执行，无 IO。
 *
 * 覆盖点：
 *  - taskKey 映射 = `ctx.sessionId`（无用例依赖全局状态）；
 *  - 状态迁移：`absent→active`（幂等）→`retained`→重激活（暖复用 id 不变）→`destroy`→`absent`；
 *  - idle 闸（命令/心跳续期）+ hard 墙钟闸（**无视状态**到点销毁；**续期只延 idle 不延 hard**）；
 *  - **「TTL 窗口外零残留」**（L9）——关掉 `sweep` 的 TTL 到期分支，本用例必须红。
 */
class ContainerLifecycleSpec extends munit.FunSuite:

  private val IdleTtl = 1000L
  private val HardTtl = 60_000L

  /** 可控时钟：测试自己推进时间，永不真实等待。 */
  private final class TestClock(var now: Long = 0L):
    def advance(ms: Long): Unit = now += ms

  private def fixture(
      idleTtl: Long = ContainerTtl.DefaultIdleTtlMs,
      hardTtl: Long = ContainerTtl.DefaultHardTtlMs
  ): (TestClock, ContainerBackend.Fake, InMemoryContainerLifecycle) =
    val clock = new TestClock
    val backend = new ContainerBackend.Fake
    val lc = new InMemoryContainerLifecycle(backend, ContainerTtl(idleTtl, hardTtl), () => clock.now)
    (clock, backend, lc)

  private def state(lc: ContainerLifecycle, key: TaskKey): ContainerState =
    lc.stateOf(key).fold(err => fail(s"stateOf 失败: $err"), identity)

  private def ok[A](e: Either[String, A]): A = e.fold(err => fail(s"意外失败: $err"), identity)

  private val key = TaskKey("node-abc")

  // ------------------------------------------------------------------
  // ① taskKey = ctx.sessionId 映射
  // ------------------------------------------------------------------

  test("taskKey: ctx.sessionId 映射（有会话即有键；同会话稳定）") {
    val ctx = ToolContext(projectRoot = ".", sessionId = Some("node-abc"))
    assertEquals(ContainerLifecycle.taskKeyOf(ctx), Right(TaskKey("node-abc")))
    assertEquals(ContainerLifecycle.taskKeyOf(Some("node-abc")), ContainerLifecycle.taskKeyOf(ctx))
    assertEquals(ContainerLifecycle.taskKeyOf(Some("  node-abc  ")), Right(TaskKey("node-abc")), "两侧空白裁剪")
  }

  test("taskKey: 无 sessionId ⇒ 显式失败（不静默回落到全局容器）") {
    val bare = ToolContext(projectRoot = ".")
    val r = ContainerLifecycle.taskKeyOf(bare)
    assert(r.isLeft, "sessionId=None 必须显式失败（U7 同款：不隐式降级）")
    assert(r.swap.exists(_.contains("sessionId")), s"错误文案须指出缺 sessionId: $r")
    assert(TaskKey.fromSession(Some("   ")).isLeft, "空白 sessionId 同样不得静默接受")
  }

  // ------------------------------------------------------------------
  // 状态机：absent → active → retained → destroy 与重激活
  // ------------------------------------------------------------------

  test("状态机: absent → active（ensure 建容器一次；重复 ensure 幂等不重建）") {
    val (_, backend, lc) = fixture()
    assertEquals(state(lc, key), ContainerState.Absent, "起始态必须 absent")
    val first = ok(lc.ensure(key))
    assertEquals(first.state, ContainerState.Active)
    assertEquals(backend.created, 1)
    val again = ok(lc.ensure(key))
    assertEquals(again.containerId, first.containerId, "同 taskKey 复用同一容器")
    assertEquals(backend.created, 1, "重复 ensure 不得新建容器")
    assertEquals(lc.stateTrail(key), List(ContainerState.Absent, ContainerState.Active))
  }

  test("状态机: active → retained（归还租约归零）→ 重激活回 active 且容器 id 不变（暖复用）") {
    val (_, backend, lc) = fixture()
    val created = ok(lc.acquire(key))
    assertEquals(created.leases, 1)
    val released = ok(lc.release(key))
    assertEquals(released.state, ContainerState.Retained)
    assertEquals(released.retainedAtMs.isDefined, true, "保留窗口起点必须落账")
    val reactivated = ok(lc.ensure(key))
    assertEquals(reactivated.state, ContainerState.Active)
    assertEquals(reactivated.containerId, created.containerId, "重激活 = 复用暖容器（id 不变）")
    assertEquals(backend.created, 1, "重激活不得新建容器")
    assertEquals(
      lc.stateTrail(key),
      List(ContainerState.Absent, ContainerState.Active, ContainerState.Retained, ContainerState.Active)
    )
  }

  test("状态机: 显式 destroy ⇒ Destroy → absent，后端零残留，trail 完整") {
    val (_, backend, lc) = fixture()
    ok(lc.acquire(key))
    ok(lc.release(key))
    ok(lc.destroy(key))
    assertEquals(state(lc, key), ContainerState.Absent, "销毁后稳态 = absent")
    assertEquals(backend.liveIds, List.empty[String], "零残留：后端无存活容器")
    assertEquals(backend.destroyed, 1)
    assertEquals(
      lc.stateTrail(key),
      List(
        ContainerState.Absent,
        ContainerState.Active,
        ContainerState.Retained,
        ContainerState.Destroy,
        ContainerState.Absent
      )
    )
    // absent 后再 ensure ⇒ 新容器（旧容器已销毁，非暖复用）
    val fresh = ok(lc.ensure(key))
    assertNotEquals(fresh.containerId, "fake-node-abc-1")
    assertEquals(backend.created, 2)
  }

  test("destroy: 无容器时显式失败（不静默成功）；重复 destroy 同样失败") {
    val (_, _, lc) = fixture()
    assert(lc.destroy(key).isLeft, "absent 上 destroy 必须显式失败")
    ok(lc.ensure(key))
    ok(lc.destroy(key))
    assertEquals(state(lc, key), ContainerState.Absent, "首次 destroy 须成功")
    assert(lc.destroy(key).isLeft, "重复 destroy 必须显式失败")
  }

  // ------------------------------------------------------------------
  // ④ 双 TTL：idle 闸（可续期）+ hard 墙钟闸（无视状态）
  // ------------------------------------------------------------------

  test("idle 闸: 命令/心跳续期可推迟回收；超 idleTtl 必回收（含恰好到期边界）") {
    val (clock, backend, lc) = fixture(idleTtl = IdleTtl, hardTtl = HardTtl)
    ok(lc.acquire(key))
    clock.advance(IdleTtl - 1)
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed], "窗口内不得误杀")
    ok(lc.heartbeat(key)) // 心跳续期
    clock.advance(IdleTtl - 1)
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed], "续期后仍在 idle 窗口内")
    clock.advance(1) // 恰好 lastActivity + IdleTtl
    val destroyed = lc.sweep(clock.now)
    assertEquals(destroyed.map(_.reason), List(DestroyReason.IdleTtlExpired), "idle 到期即回收（>= 语义）")
    assertEquals(backend.liveIds, List.empty[String])
    assertEquals(state(lc, key), ContainerState.Absent)
  }

  test("idle 闸: retained 容器同样受 idle 闸管辖（保留窗口到期 ⇒ 回收）") {
    val (clock, backend, lc) = fixture(idleTtl = IdleTtl, hardTtl = HardTtl)
    ok(lc.acquire(key))
    ok(lc.release(key))
    assertEquals(state(lc, key), ContainerState.Retained)
    clock.advance(IdleTtl - 1)
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed])
    clock.advance(1)
    assertEquals(lc.sweep(clock.now).map(_.reason), List(DestroyReason.IdleTtlExpired))
    assertEquals(backend.liveIds, List.empty[String])
  }

  test("hard 墙钟闸: 持续 heartbeat（idle 一直新鲜）也挡不住 hard 到点销毁——续期只延 idle 不延 hard") {
    val (clock, backend, lc) = fixture(idleTtl = IdleTtl, hardTtl = HardTtl)
    ok(lc.acquire(key))
    var t = 0L
    while t + (IdleTtl - 1) < HardTtl do
      clock.advance(IdleTtl - 1)
      assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed], s"hard 窗口内 + idle 续期 ⇒ 不得回收（t=$t）")
      ok(lc.heartbeat(key))
      t += IdleTtl - 1
    // hard 边界前一毫秒：idle 仍新鲜（心跳刚落）⇒ 仍存活
    clock.advance(HardTtl - clock.now - 1)
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed], "hard 边界前一毫秒不得回收")
    // 此处：lastActivity 距 now 仅数十毫秒（idle 闸不触发），但 createdAt 已到 hardTtl
    clock.advance(1)
    val destroyed = lc.sweep(clock.now)
    assertEquals(destroyed.map(_.reason), List(DestroyReason.HardTtlExpired), "hard 闸无视状态到点必杀")
    assertEquals(destroyed.map(_.taskKey), List(key))
    assertEquals(backend.destroyed, 1)
    assertEquals(state(lc, key), ContainerState.Absent)
  }

  test("hard 墙钟闸: retained 状态同受 hard 闸（保留窗口跨过 hardTtl ⇒ 仍被销毁）") {
    val (clock, backend, lc) = fixture(idleTtl = 10L * HardTtl, hardTtl = HardTtl)
    ok(lc.acquire(key))
    ok(lc.release(key))
    clock.advance(HardTtl - 1)
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed])
    clock.advance(1)
    assertEquals(lc.sweep(clock.now).map(_.reason), List(DestroyReason.HardTtlExpired))
    assertEquals(backend.liveIds, List.empty[String])
  }

  // ------------------------------------------------------------------
  // L9 零残留（验红主用例）
  // ------------------------------------------------------------------

  test("TTL 窗口外零残留: 混合 active/retained/硬续期容器，推进到窗口外 ⇒ 后端零存活且 created == destroyed") {
    val (clock, backend, lc) = fixture(idleTtl = IdleTtl, hardTtl = HardTtl)
    val a = TaskKey("node-a") // 有在跑命令（租约持有），无续期 ⇒ idle 回收
    val b = TaskKey("node-b") // 任务终局 ⇒ retained，无续期 ⇒ idle 回收
    val c = TaskKey("node-c") // 持续心跳（idle 一直新鲜）⇒ 只能被 hard 闸收

    ok(lc.acquire(a))
    ok(lc.acquire(b))
    ok(lc.release(b))
    ok(lc.acquire(c))

    clock.advance(IdleTtl - 1)
    ok(lc.heartbeat(c))
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed], "窗口内不得误杀")

    clock.advance(1) // a/b 的 idle 到期（c 刚续期）
    val idleRound = lc.sweep(clock.now)
    assertEquals(idleRound.map(_.taskKey).toSet, Set(a, b))
    assertEquals(idleRound.map(_.reason).toSet, Set(DestroyReason.IdleTtlExpired))
    assertEquals(backend.liveIds.size, 1, "c 靠心跳存活（idle 闸被续期推迟）")

    clock.advance(HardTtl) // 越过 c 的 hard 闸
    val hardRound = lc.sweep(clock.now)
    assertEquals(hardRound.map(_.taskKey), List(c))
    assertEquals(hardRound.map(_.reason), List(DestroyReason.HardTtlExpired))

    // ——零残留断言（L9）：窗口外不得有任何存活容器 ——
    assertEquals(backend.liveIds, List.empty[String], "TTL 窗口外零残留")
    assertEquals(lc.live, List.empty[ContainerRecord], "生命周期表内无残留记录")
    assertEquals(backend.created, 3)
    assertEquals(backend.destroyed, 3, "created == destroyed（每个创建都被收尸）")
    List(a, b, c).foreach { k =>
      assertEquals(state(lc, k), ContainerState.Absent, s"$k 必须回 absent")
      assert(lc.stateTrail(k).contains(ContainerState.Destroy), s"$k 的轨迹须含 Destroy 迁移态")
    }
  }

  test("零残留的反面: 后端销毁失败 ⇒ 记录保留（不静默遗忘活容器）") {
    val clock = new TestClock
    val failing = new ContainerBackend:
      val name = "failing"
      def create(taskKey: TaskKey): Either[String, String] = Right("c1")
      def destroy(containerId: String): Either[String, Unit] = Left("docker rm -f 失败")
    val lc = new InMemoryContainerLifecycle(failing, ContainerTtl(IdleTtl, HardTtl), () => clock.now)
    ok(lc.ensure(key))
    clock.advance(IdleTtl)
    assertEquals(lc.sweep(clock.now), List.empty[ContainerDestroyed], "销毁失败不得伪报成功")
    assertEquals(lc.live.size, 1, "记录必须保留（零残留断言因此会响）")
    assertEquals(state(lc, key), ContainerState.Active)
  }
