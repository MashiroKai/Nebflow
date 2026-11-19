package nebflow.core.delegate

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.project.ChainsReadFace
import nebflow.shared.PathUtil

/**
 * delegate-lifecycle-reform **阶段② 退场面回归**（作者令：手动归档/反归档、5s 撤销、
 * 冻结期满自动归档**整体退役**——「用状态派生可见性替换存储式归档，零删除」）。
 *
 * 本 spec 是**退场面**的机械护栏，两类断言：
 *  1. **不再存在**（源面）：被删的写点/端点/前端交互在源码文本里必须**找不到**
 *     （删干净了），而替代面（`setDisposition` / `/disposition`）必须在场——「留着
 *     半截死交互」是最坏的中间态（前端点一下 = 静默 404，面板装作成功）；
 *  2. **不再出错**（行为面）：台面读面（`ChainsReadFace.rowsJson` 两窗）对
 *     带存量归档旗 / 表态旗的行照常返回合法 JSON，**不抛不 500**——退场只删写点，
 *     读面必须活着（零删除纪律：记录原样可查）。
 *
 * 🔴 改前必红：第 1 类断言在阶段② 实现前逐条失败（写点/端点/钮都还在）。第 2 类是
 * 控制面（两轮都绿，证明「退场 ≠ 读面塌」）。
 */
class DelegateArchiveRemovalSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-stage2-removal"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def src(rel: String): String =
    val p = os.pwd / os.RelPath(rel)
    assert(os.exists(p), s"source file missing: $p")
    os.read(p)

  /** 引擎侧（Scala）必须干净：无归档/反归档写点。 */
  private val EngineFiles = List(
    "src/main/scala/nebflow/core/delegate/DelegateRegistry.scala",
    "src/main/scala/nebflow/core/delegate/DelegateTerminal.scala",
    "src/main/scala/nebflow/core/delegate/DelegateFreeze.scala",
    "src/main/scala/nebflow/core/delegate/DelegateLedgerReconcile.scala",
    "src/main/scala/nebflow/core/delegate/SessionResumption.scala",
    "src/main/scala/nebflow/core/delegate/DelegateRoundLedger.scala"
  )

  // ── 1. 引擎写点退场 ──────────────────────────────────────────────────────

  test("removed: DelegateRegistry has no archive/unarchive write point any more (disposition is the only visibility write)") {
    val registry = src("src/main/scala/nebflow/core/delegate/DelegateRegistry.scala")
    assert(!registry.contains("def archive("), "DelegateRegistry.archive must be gone (storage-style archive retired)")
    assert(!registry.contains("def unarchive("), "DelegateRegistry.unarchive must be gone (manual unarchive retired)")
    assert(
      !registry.contains("copy(archivedAt = Some("),
      "no writer may stamp archivedAt any more (legacy slot is read-only compatibility)"
    )
    assert(registry.contains("def setDisposition("), "the nondestructive acknowledged/ignored write face must exist")
    assert(registry.contains("def clearDisposition("), "the disposition clear face must exist (nondestructive: row returns)")
  }

  test("removed: no engine call site invokes a delegate archive/unarchive writer") {
    val hits = EngineFiles.flatMap { rel =>
      val text = src(rel)
      List("DelegateRegistry.archive(", "DelegateRegistry.unarchive(", "clearArchive")
        .filter(text.contains)
        .map(w => s"$rel :: $w")
    }
    assertEquals(hits, Nil, s"dangling references to the retired archive writers: ${hits.mkString("; ")}")
  }

  test("removed: the terminal auto-archive leg (archivedAt stamp on every terminal) is gone") {
    val terminal = src("src/main/scala/nebflow/core/delegate/DelegateTerminal.scala")
    assert(!terminal.contains("autoArchive"), "DelegateTerminal.autoArchive must be gone")
    assert(
      !terminal.contains("archivedAt"),
      "the terminal cleanup chain must not touch the archive flag at all (state-derived visibility owns the window)"
    )
  }

  test("removed: the frozen-expiry auto-archive leg is gone (retention becomes a state-derived reading)") {
    val freeze = src("src/main/scala/nebflow/core/delegate/DelegateFreeze.scala")
    assert(
      !freeze.contains("DelegateRegistry.archive("),
      "the frozen-retention sweep must not write an archive flag any more"
    )
    assert(
      freeze.contains("def retentionExpired("),
      "the state-derived retention reading must exist (single point for the wake/revival/notice legs)"
    )
  }

  test("removed: the two REST endpoints are gone and the disposition endpoint is present") {
    val routes = src("src/main/scala/nebflow/gateway/PresenceRoutes.scala")
    assert(
      !routes.contains("""Root / "delegates" / id / "archive""""),
      "POST /delegates/:id/archive must be gone"
    )
    assert(
      !routes.contains("""Root / "delegates" / id / "unarchive""""),
      "POST /delegates/:id/unarchive must be gone"
    )
    assert(
      routes.contains("""Root / "delegates" / id / "disposition""""),
      "POST /delegates/:id/disposition must be the replacement write face"
    )
  }

  // ── 2. 前端交互退场（5s 撤销 / 抽屉恢复 / 死端点绑定）────────────────────

  test("removed: the panel has no 5s undo affordance and no manual archive/unarchive binding") {
    val view = src("src/main/resources/web/js/delegateChainView.js")
    val api = src("src/main/resources/web/js/delegateApi.js")
    assert(!view.contains("unarchiveDelegate"), "the undo/restore action (unarchive binding) must be gone from the view")
    assert(!view.contains("delegate.archive.undo"), "the 5s undo label must be gone from the view")
    assert(!view.contains("delegate-restore"), "the drawer's manual 'restore' action must be gone")
    assert(
      !api.contains("archive:") && !api.contains("unarchive:"),
      "delegateApi must not bind the retired archive/unarchive endpoints (comments may still narrate the retirement)"
    )
    assert(api.contains("/disposition"), "delegateApi must bind the disposition endpoint instead")
  }

  test("removed: the retired i18n keys are gone from BOTH locales (parity kept)") {
    val zh = src("src/main/resources/web/js/locales/zh-CN.js")
    val en = src("src/main/resources/web/js/locales/en.js")
    List("delegate.archive.undo", "delegate.archive.restore").foreach { key =>
      assert(!zh.contains(key), s"zh-CN still carries the retired key '$key'")
      assert(!en.contains(key), s"en still carries the retired key '$key'")
    }
  }

  // ── 3. 读面不因退场出错（控制面：两轮都绿）──────────────────────────────

  test("do not error: both panel windows answer cleanly for a legacy-archived row and a dismissed row (zero deletion)") {
    val id = "delegate-nebflow-s2rmguard1"
    os.makeDir.all(tempRoot / "general")
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = (tempRoot / "general").toString,
        project = None,
        taskId = None,
        parentSessionId = "root",
        title = "removal-guard",
        createdAt = System.currentTimeMillis() - 3600_000L,
        archivedAt = Some(System.currentTimeMillis() - 3600_000L)
      )
    )
    val active = ChainsReadFace.rowsJson(Map.empty, archived = false).unsafeRunSync()
    val history = ChainsReadFace.rowsJson(Map.empty, archived = true).unsafeRunSync()
    assert(active.hcursor.downField("rows").focus.isDefined, s"active window must stay a well-formed payload: $active")
    assert(history.hcursor.downField("rows").focus.isDefined, s"history window must stay a well-formed payload: $history")
    assert(DelegateRegistry.find(id).isDefined, "zero deletion: the row is still queryable after both windows are read")
  }

end DelegateArchiveRemovalSpec
