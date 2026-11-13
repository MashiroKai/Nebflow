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

  override def afterEach(context: munit.AfterEach): Unit =
    System.clearProperty(DelegateFlowMapLifecycle.SysPropGraceMs)
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def mkRecord(
    id: String,
    mode: Option[String],
    cwd: os.Path,
    projectWs: Option[String],
    archived: Boolean
  ): Unit =
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

  test("L1 applies：dispatcher 才真；kernel / 无记录 均假（双模式总闸已退役）") {
    val id = "delegate-nebflow-l1"
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), tmp, None, archived = true)
    assert(DelegateFlowMapLifecycle.applies(DelegateRegistry.find(id)), "gate on × dispatcher = applies")
    assert(
      !DelegateFlowMapLifecycle.applies(DelegateRegistry.find("delegate-nebflow-ghost")),
      "no record = no lifecycle"
    )
    mkRecord("delegate-nebflow-l1k", Some(DualModeConfig.ModeKernel), tmp, None, archived = true)
    assert(
      !DelegateFlowMapLifecycle.applies(DelegateRegistry.find("delegate-nebflow-l1k")),
      "kernel keeps the map (no orchestration face)"
    )
    mkRecord("delegate-nebflow-l1legacy", None, tmp, None, archived = true)
    assert(
      !DelegateFlowMapLifecycle.applies(DelegateRegistry.find("delegate-nebflow-l1legacy")),
      "legacy records (mode=None) never engage the dispatcher lifecycle"
    )
  }

  // ── L2 宽限窗旋钮 ───────────────────────────────────────────────────────────

  test("L2 graceMs：缺省 10min；sys prop 热读覆写") {
    assertEquals(DelegateFlowMapLifecycle.graceMs, 600_000L, "default grace = 10 minutes")
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "150")
    assertEquals(DelegateFlowMapLifecycle.graceMs, 150L, "the sys prop is the test/ops knob (hot read)")
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "garbage")
    assertEquals(DelegateFlowMapLifecycle.graceMs, 600_000L, "unparsable value falls back to the default")
  }

  // ── L3/L4 宽限退役 ─────────────────────────────────────────────────────────

  test("L3 退役=迁移而非删除：archivedAt 在 ∧ 未重挂 ∧ 盘面静默 ⇒ grace 后目录保留、迟到完成链入档") {
    val id = "delegate-nebflow-l3"
    val base = tmp / "ws"
    val scopeDir = base / ".nebflow" / "flow-maps" / id
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), base, None, archived = true)
    // 预置一条已完成链（挂载同式开 store 直种），退役迁移应把它扫进 flow-map-archive/。
    nebflow.core.project.FlowMapStore
      .openScope(id, id, base.toString)
      .flatMap { store =>
        store.mutate(st =>
          st.copy(nodes =
            st.nodes + ("n1" -> nebflow.core.project.NodeDef(
              id = "n1",
              name = "n1",
              agent = "nebflow",
              description = Some("late completion"),
              in = Nil,
              status = nebflow.core.project.NodeLifecycle.Completed,
              createdAt = System.currentTimeMillis() - 60_000L
            ))
          )
        )
      }
      .unsafeRunSync()
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "150")
    assert(os.exists(scopeDir), "precondition: the scope dir exists on disk")
    DelegateFlowMapLifecycle.scheduleScopeRemoval(id).unsafeRunSync()
    assert(os.exists(scopeDir), "retirement MIGRATES: the scope dir must be kept (never wholesale deleted)")
    assert(
      os.exists(scopeDir / "flow-map-archive") && os.list(scopeDir / "flow-map-archive").nonEmpty,
      "the late completed chain must be swept into the existing flow-map-archive/ channel"
    )
    // 迁移后活动区为空（迟到完成已出库）——目录成为健康退役形态（历史件原样）。
    val active = nebflow.core.project.FlowMapStore
      .openScope(id, id, base.toString)
      .flatMap(_.snapshot)
      .unsafeRunSync()
    assertEquals(active.nodes.size, 0, "the migration sweep empties the active region")
  }

  test("L4 双闸：未归档（archivedAt 缺）⇒ 永不退役，目录留存") {
    val id = "delegate-nebflow-l4"
    val base = tmp / "ws"
    val scopeDir = base / ".nebflow" / "flow-maps" / id
    os.makeDir.all(scopeDir)
    os.write.over(scopeDir / "flow-map.json", "{}")
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), base, None, archived = false)
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "100")
    DelegateFlowMapLifecycle.scheduleScopeRemoval(id).unsafeRunSync()
    assert(os.exists(scopeDir), "no archivedAt on record = the audit face wins, the dir stays")
  }

  // ── L6 终态腿盘面静默复核（迁移化批：终态腿与孤儿腿同过 scopeQuietRecheck）──

  test("L6 终态腿静默复核：archivedAt 在但盘面仍有非终态节点 ⇒ 跳过迁移，目录与节点原样保留") {
    val id = "delegate-nebflow-l6"
    val base = tmp / "ws"
    val scopeDir = base / ".nebflow" / "flow-maps" / id
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), base, None, archived = true)
    // 盘面复活形态：重开 store 直种一个 RUNNING 节点——终态证据（archivedAt）在，
    // 但盘面不静默 ⇒ 双保险拦截（复活窗/在飞优先于整洁）。
    nebflow.core.project.FlowMapStore
      .openScope(id, id, base.toString)
      .flatMap { store =>
        store.mutate(st =>
          st.copy(nodes =
            st.nodes + ("n6" -> nebflow.core.project.NodeDef(
              id = "n6",
              name = "n6",
              agent = "nebflow",
              description = Some("still running"),
              in = Nil,
              status = nebflow.core.project.NodeLifecycle.Running,
              createdAt = System.currentTimeMillis() - 60_000L
            ))
          )
        )
      }
      .unsafeRunSync()
    System.setProperty(DelegateFlowMapLifecycle.SysPropGraceMs, "100")
    DelegateFlowMapLifecycle.scheduleScopeRemoval(id).unsafeRunSync()
    assert(os.exists(scopeDir), "the terminal-leg quiet recheck must keep the dir while a node is non-terminal")
    val active = nebflow.core.project.FlowMapStore
      .openScope(id, id, base.toString)
      .flatMap(_.snapshot)
      .unsafeRunSync()
    assertEquals(
      active.nodes.get("n6").map(_.status),
      Some(nebflow.core.project.NodeLifecycle.Running),
      "the in-flight node must stay exactly as-is (revival window wins)"
    )
  }

  // ── L5 存储锚推导 ──────────────────────────────────────────────────────────

  test("L5 baseWorkspaceOf/scopeDirOf：worktree 腿 projectWorkspace 胜；就地腿回落 cwd（= mount 的存储锚）") {
    val id = "delegate-nebflow-l5"
    val seat = tmp / "wt-seat"
    val base = tmp / "project-ws"
    mkRecord(id, Some(DualModeConfig.ModeDispatcher), seat, Some(base.toString), archived = true)
    val rec = DelegateRegistry.find(id)
    assertEquals(
      DelegateFlowMapLifecycle.baseWorkspaceOf(rec),
      Some(base.toString),
      "worktree leg anchors at the project workspace"
    )
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
