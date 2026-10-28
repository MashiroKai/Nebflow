package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * 双模式批（2026-10-28 · 设计 §3.2/§8.2）闸面 spec：**总闸读法 + registry 读面 +
 * 分配端口 fail-open**。
 *
 * 红绿锚（闸 off = 现行为逐字节的机制化钉子）：
 *  - G1/G2：`enabledNow` 缺省 false；sys prop 热读覆写（优先于 config）；非法值
 *    回落 config 读法。闸关 ⇒ `modeFaceOf` 恒 None——**盘上有 mode 也不分叉**
 *    （Inert 单点——工具面/挂载面分叉的全部判据输入在此收口）。
 *  - G3：`failopenMode` 缺省 kernel（两错不对称取便宜的一侧）；非法值回落 kernel。
 *  - G4：闸开时读面三态——记录 mode=kernel/dispatcher 透传；无记录/非 delegate
 *    会话/非法词 ⇒ None（现行为腿）。
 *  - G5：`DelegateModeAllocator` 端口——未注册/face 抛异常 = fail-open kernel
 *    （leg=fail-open，绝不阻断派发）；注册面正常透传。
 */
class DualModeGateSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "dual-mode-gate")
    PathUtil.setDataRoot(tmp / "data")
    System.clearProperty(DualModeConfig.SysPropEnabled)

  override def afterEach(context: munit.AfterEach): Unit =
    System.clearProperty(DualModeConfig.SysPropEnabled)
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def setSysProp(v: String): Unit = System.setProperty(DualModeConfig.SysPropEnabled, v)

  private def writeConfig(json: String): Unit =
    os.makeDir.all(tmp / "data")
    os.write.over(PathUtil.configJsonReadPath(tmp / "data"), json)

  private def mkRecord(id: String, mode: Option[String]): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tmp.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-gate",
        title = s"gate spec $id",
        createdAt = System.currentTimeMillis() - 60_000L,
        mode = mode
      )
    )

  // ── G1/G2 总闸读法 ──────────────────────────────────────────────────────────

  test("G1 缺省 false：无 sys prop 无 config ⇒ enabledNow=false（Inert 现状逐字节）") {
    assertEquals(DualModeConfig.enabledNow, false, "the gate defaults OFF — dual mode is opt-in")
  }

  test("G2 sys prop 热读覆写：true 生效；false 压过 config true；非法值回落 config") {
    setSysProp("true")
    assertEquals(DualModeConfig.enabledNow, true, "sys prop true = the gate is on (hot read)")
    setSysProp("false")
    writeConfig("""{"delegate":{"dualMode":{"enabled":true}}}""")
    assertEquals(DualModeConfig.enabledNow, false, "sys prop false must override config true")
    setSysProp("garbage-not-a-bool")
    assertEquals(DualModeConfig.enabledNow, true, "unparsable sys prop falls back to the config reading")
    System.clearProperty(DualModeConfig.SysPropEnabled)
    assertEquals(DualModeConfig.enabledNow, true, "config true stands once the sys prop is cleared")
  }

  test("G2b IO 形态与同步读法同源（enabled ≡ enabledNow 的包裹）") {
    setSysProp("true")
    assertEquals(DualModeConfig.enabled.unsafeRunSync(), true)
    System.clearProperty(DualModeConfig.SysPropEnabled)
    assertEquals(DualModeConfig.enabled.unsafeRunSync(), DualModeConfig.enabledNow)
  }

  // ── G3 fail-open 方向旋钮 ───────────────────────────────────────────────────

  test("G3 failopenMode：缺省 kernel；config dispatcher 生效；非法/错误大小写回落 kernel") {
    assertEquals(DualModeConfig.failopenMode, DualModeConfig.ModeKernel, "default fail-open side = kernel (the cheap side)")
    writeConfig("""{"delegate":{"dualMode":{"failopenMode":"dispatcher"}}}""")
    assertEquals(DualModeConfig.failopenMode, DualModeConfig.ModeDispatcher)
    writeConfig("""{"delegate":{"dualMode":{"failopenMode":"Kernel"}}}""")
    assertEquals(DualModeConfig.failopenMode, DualModeConfig.ModeKernel, "value domain is exact-lowercase — 'Kernel' falls back")
  }

  test("G3b isModeWord 值域：kernel/dispatcher 合法，其余（含大小写变体/空串）非法") {
    assert(DualModeConfig.isModeWord("kernel"))
    assert(DualModeConfig.isModeWord("dispatcher"))
    assert(!DualModeConfig.isModeWord("Kernel"), "exact match — case variants are not mode words")
    assert(!DualModeConfig.isModeWord(""))
    assert(!DualModeConfig.isModeWord("both"))
  }

  // ── G4 modeFaceOf 读面 ──────────────────────────────────────────────────────

  test("G4a 闸关 ⇒ modeFaceOf 恒 None：盘上有 mode=dispatcher 也不分叉（Inert 单点）") {
    mkRecord("delegate-nebflow-g4a", Some(DualModeConfig.ModeDispatcher))
    assertEquals(
      DelegateRegistry.modeFaceOf(Some("delegate-nebflow-g4a")),
      None,
      "gate OFF = the mode face is None even when a mode is on record — Inert is a single point"
    )
  }

  test("G4b 闸开三态：记录 mode 透传；无记录/非 delegate 会话/非法词 ⇒ None") {
    setSysProp("true")
    mkRecord("delegate-nebflow-g4b", Some(DualModeConfig.ModeKernel))
    mkRecord("delegate-nebflow-g4b2", Some(DualModeConfig.ModeDispatcher))
    mkRecord("delegate-nebflow-g4b3", Some("kernelx"))
    assertEquals(DelegateRegistry.modeFaceOf(Some("delegate-nebflow-g4b")), Some(DualModeConfig.ModeKernel))
    assertEquals(DelegateRegistry.modeFaceOf(Some("delegate-nebflow-g4b2")), Some(DualModeConfig.ModeDispatcher))
    assertEquals(
      DelegateRegistry.modeFaceOf(Some("delegate-nebflow-g4b3")),
      None,
      "an out-of-domain mode word must never fork the face"
    )
    assertEquals(DelegateRegistry.modeFaceOf(Some("delegate-nebflow-ghost")), None, "no record = no mode")
    assertEquals(
      DelegateRegistry.modeFaceOf(Some("root-main")),
      None,
      "non-delegate sessions never carry a mode face"
    )
    assertEquals(DelegateRegistry.modeFaceOf(None), None)
  }

  // ── G5 分配端口 fail-open ───────────────────────────────────────────────────

  test("G5a 端口未注册 ⇒ fail-open kernel（leg=fail-open，绝不阻断派发）") {
    DelegateModeAllocator.clear()
    val d = DelegateModeAllocator.decideMode("delegate-x", "task").unsafeRunSync()
    assertEquals(d.mode, DualModeConfig.ModeKernel)
    assertEquals(d.leg, "fail-open")
  }

  test("G5b 端口注册面透传；face 抛异常 ⇒ fail-open kernel（fail-soft，不炸 spawn 链）") {
    DelegateModeAllocator.clear()
    DelegateModeAllocator.install((_, _) => IO.pure(ModeDecision(DualModeConfig.ModeDispatcher, "manual", "spec stub")))
    val d = DelegateModeAllocator.decideMode("delegate-x", "task").unsafeRunSync()
    assertEquals(d.mode, DualModeConfig.ModeDispatcher)
    assertEquals(d.leg, "manual")
    DelegateModeAllocator.install((_, _) => IO.raiseError(new RuntimeException("face exploded")))
    val d2 = DelegateModeAllocator.decideMode("delegate-x", "task").unsafeRunSync()
    assertEquals(d2.mode, DualModeConfig.ModeKernel, "a throwing face degrades to fail-open kernel, never blocks dispatch")
    assertEquals(d2.leg, "fail-open")
    DelegateModeAllocator.clear()
  }
end DualModeGateSpec
