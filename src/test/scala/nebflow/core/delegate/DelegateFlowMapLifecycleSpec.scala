package nebflow.core.delegate

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * 双模式批（2026-10-28 · 设计 §6 第 4 步）**delegate 作用域图生命周期** spec：
 *
 *  - L1 触发判据单点：闸开 ∧ mode=dispatcher 才 applies（kernel/Inert/闸关零触碰）。
 *  - L2 宽限窗旋钮：缺省 10min；sys prop `nebflow.delegate.flowmap.graceMs` 热读。
 *  - L3 宽限删除 happy path：archivedAt 在 ∧ 未重挂 ⇒ grace 后作用域目录整删。
 *  - L4 双闸：未归档（archivedAt 缺）⇒ 永不删（审计面优先于整洁）；目录留存。
 *  - L5 存储锚推导：worktree 腿 projectWorkspace 胜；就地腿回落 cwd（与 mount
 *    的 baseWorkspace = workspace 参数同式——scopeDirOf 读数即挂载真目录）。
 */
class DelegateFlowMapLifecycleSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "fm-lifecycle")
    PathUtil.setDataRoot(tmp / "data")
    System.clearProperty(DelegateFlowMapLifecycle.SysPropGraceMs)
    System.clearProperty(DualModeConfig.SysPropEnabled)

  override def afterEach(context: munit.AfterEach): Unit =
    System.clearProperty(DelegateFlowMapLifecycle.SysPropGraceMs)
    System.clearProperty(DualModeConfig.SysPropEnabled)
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def gateOn(): Unit = System.setProperty(DualModeConfig.SysPropEnabled, "true")

  private def mkRecord(id: String, mode: Option[String], cwd: os.Path, projectWs: Option[String], archived: Boolean): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-fm",
        title = s"fm lifecycle $id",
        createdAt = System.currentTimeMillis() - 60_000L,
        projectWorkspace = projectWs,
        mode = mode,
        archivedAt = if archived then Some(System.currentTimeMillis()) else None
      )
    )

  // ── L1 判据单点 ─────────────────────────────────────────────────────────────

  test("L1 applies：闸开 × dispatcher 才真；kernel / 闸关 / 无记录 均假") {
    val id = "delegate-nebflow-l1"
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), tmp, None, archived = true)
    gateOn()
    assert(DelegateFlowMapLifecycle.applies(DelegateRegistry.find(id)), "gate on × dispatcher = applies")
    assert(!DelegateFlowMapLifecycle.applies(DelegateRegistry.find("delegate-nebflow-ghost")), "no record = no lifecycle")
    mkRecord("delegate-nebflow-l1k", Some(DualModeConfig.ModeKernel), tmp, None, archived = true)
    assert(!DelegateFlowMapLifecycle.applies(DelegateRegistry.find("delegate-nebflow-l1k")), "kernel keeps the map (no orchestration face)")
    System.clearProperty(DualModeConfig.SysPropEnabled)
    assert(!DelegateFlowMapLifecycle.applies(DelegateRegistry.find(id)), "gate off = byte-identical inert (never applies)")
  }

  // ── L2 宽限窗旋钮 ───────────────────────────────────────────────────────────

  test("L2 graceMs：缺省 10min；sys prop 热读覆写") {
    assertEquals(DelegateFlowMapLifecycle.graceMs, 600_000L, "default grace = 10 minutes")
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "150")
    assertEquals(DelegateFlowMapLifecycle.graceMs, 150L, "the sys prop is the test/ops knob (hot read)")
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "garbage")
    assertEquals(DelegateFlowMapLifecycle.graceMs, 600_000L, "unparsable value falls back to the default")
  }

  // ── L3/L4 宽限删除 ─────────────────────────────────────────────────────────

  test("L3 宽限删除：archivedAt 在 ∧ 未重挂 ⇒ grace 后作用域目录整删") {
    val id = "delegate-nebflow-l3"
    val base = tmp / "ws"
    val scopeDir = base / ".nebflow" / "flow-maps" / id
    os.makeDir.all(scopeDir)
    os.write.over(scopeDir / "flow-map.json", "{}")
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), base, None, archived = true)
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "150")
    assert(os.exists(scopeDir), "precondition: the scope dir exists on disk")
    DelegateFlowMapLifecycle.scheduleScopeRemoval(id).unsafeRunSync()
    assert(!os.exists(scopeDir), s"the scope dir must be removed after the grace window: $scopeDir")
  }

  test("L4 双闸：未归档（archivedAt 缺）⇒ 永不删，目录留存") {
    val id = "delegate-nebflow-l4"
    val base = tmp / "ws"
    val scopeDir = base / ".nebflow" / "flow-maps" / id
    os.makeDir.all(scopeDir)
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), base, None, archived = false)
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "100")
    DelegateFlowMapLifecycle.scheduleScopeRemoval(id).unsafeRunSync()
    assert(os.exists(scopeDir), "no archivedAt on record = the audit face wins, the dir stays")
  }

  // ── L5 存储锚推导 ──────────────────────────────────────────────────────────

  test("L5 baseWorkspaceOf/scopeDirOf：worktree 腿 projectWorkspace 胜；就地腿回落 cwd（= mount 的存储锚）") {
    val id = "delegate-nebflow-l5"
    val seat = tmp / "wt-seat"
    val base = tmp / "project-ws"
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), seat, Some(base.toString), archived = true)
    val rec = DelegateRegistry.find(id)
    assertEquals(DelegateFlowMapLifecycle.baseWorkspaceOf(rec), Some(base.toString), "worktree leg anchors at the project workspace")
    assertEquals(
      DelegateFlowMapLifecycle.scopeDirOf(base.toString, id),
      base / ".nebflow" / "flow-maps" / id,
      "the scope-dir readout matches the mount-time layout (<base>/.nebflow/flow-maps/<id>/)"
    )
    mkRecord("delegate-nebflow-l5b", Some(DualModeConfig.ModeDispatcher), seat, None, archived = true)
    assertEquals(
      DelegateFlowMapLifecycle.baseWorkspaceOf(DelegateRegistry.find("delegate-nebflow-l5b")),
      Some(seat.toString),
      "in-place leg falls back to cwd (the mount-time baseWorkspace)"
    )
  }
end DelegateFlowMapLifecycleSpec
