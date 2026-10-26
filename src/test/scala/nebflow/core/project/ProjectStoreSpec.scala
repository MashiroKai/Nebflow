package nebflow.core.project

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * ProjectStore 单测（#28 阶段 0，§1.1）——项目定义 + 工作区脚手架：
 * - create：projects/<name>/project.json + 工作区根 AGENTS.md（agent 指令模板）+ .nebflow/（.gitignore）
 * - 重复 create 拒绝（防覆盖）
 * - load 往返
 */
class ProjectStoreSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-project-store"
  private val ws: os.Path = tempRoot / "workspace"

  // 每个 test 前重置 dataRoot + 清空工作区（setDataRoot 是全局态，类级设一次）
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(ws)

  private val template =
    """# demo — AGENTS.md
项目级 agent 指令。
"""

  test("create writes project.json + workspace .nebflow scaffolding") {
    for
      created <- ProjectStore.create("demo", ws.toString, Some("demo project"), template)
      pd <- ProjectStore.load("demo")
    yield
      assert(created.isRight)
      val right = created.toOption.get
      assertEquals(right.name, "demo")
      assertEquals(right.workspace, ws.toString)
      assert(os.exists(ProjectStore.projectJsonPath("demo")))
      assert(os.exists(ws / "AGENTS.md"), "AGENTS.md template must be at workspace root")
      assert(!os.exists(ws / ".nebflow" / ".gitignore"), ".gitignore must NOT be inside .nebflow/ (R6 position fix)")
      assert(os.exists(ws / ".gitignore"), ".gitignore must be at workspace root (R6)")
      assertEquals(os.read(ws / ".gitignore"), ".nebflow/\n")
      assertEquals(pd.map(_.name), Some("demo"))
  }

  test("duplicate create rejected (no overwrite)") {
    for
      _ <- ProjectStore.create("dup", ws.toString, None, template)
      second <- ProjectStore.create("dup", ws.toString, None, template)
    yield assert(second.isLeft)
  }

  test("invalid name (path traversal) rejected") {
    ProjectStore.create("../evil", ws.toString, None, template).map { r =>
      assert(r.isLeft)
    }
  }

  test("load: absent project → None") {
    ProjectStore.load("no-such-project").map(r => assertEquals(r, None))
  }

  test("list returns created projects") {
    for
      _ <- ProjectStore.create("listme", (ws / "listme").toString, None, template)
      all <- ProjectStore.list()
    yield assert(all.exists(_.name == "listme"))
  }

  test("R6: existing workspace .gitignore keeps user content and appends .nebflow/") {
    val ws2 = tempRoot / "ws-existing-gi"
    os.makeDir.all(ws2)
    os.write.over(ws2 / ".gitignore", "# user rules\nnode_modules/\n") // 无尾换行
    for created <- ProjectStore.create("gi-append", ws2.toString, None, template)
    yield
      assert(created.isRight)
      val content = os.read(ws2 / ".gitignore")
      assert(content.startsWith("# user rules\nnode_modules/\n"), "user content must be preserved")
      assert(content.contains(".nebflow/"), "must append .nebflow/")
      assertEquals(os.read(ws2 / "AGENTS.md"), template)
  }

  test("R6: existing workspace .gitignore already containing .nebflow/ is not duplicated") {
    val ws3 = tempRoot / "ws-has-gi"
    os.makeDir.all(ws3)
    os.write.over(ws3 / ".gitignore", "node_modules/\n.nebflow/\n")
    for created <- ProjectStore.create("gi-existing", ws3.toString, None, template)
    yield
      assert(created.isRight)
      val content = os.read(ws3 / ".gitignore")
      assertEquals(content, "node_modules/\n.nebflow/\n", "must not duplicate the .nebflow/ entry")
  }

  test("R6: fresh workspace gets root .gitignore with .nebflow/ entry") {
    val ws4 = tempRoot / "ws-ignore-check"
    os.makeDir.all(ws4)
    for created <- ProjectStore.create("gi-ignore-check", ws4.toString, None, template)
    yield
      assert(created.isRight)
      val gi = ws4 / ".gitignore"
      assert(os.exists(gi))
      assertEquals(os.read(gi), ".nebflow/\n")
  }

  // S2 2026-09-17 12:09 裁定单 ③-8：「缺件即补、既有永不覆盖」——既有件是**不可覆写**的
  // （变异面：若把 ensureScaffold 改成无条件覆写，本条必红）。
  test("③-8: pre-existing AGENTS.md is never overwritten (additive-only)") {
    val ws5 = tempRoot / "ws-existing-agents"
    os.makeDir.all(ws5)
    val userMd = "# 用户自定 AGENTS.md\n别动我\n"
    os.write.over(ws5 / "AGENTS.md", userMd)
    for created <- ProjectStore.create("agents-keep", ws5.toString, None, template)
    yield
      assert(created.isRight)
      assertEquals(os.read(ws5 / "AGENTS.md"), userMd, "既有 AGENTS.md 必须逐字节不变")
  }

  test("③-8: pre-existing .nebflow/ directory content is never touched by create") {
    val ws6 = tempRoot / "ws-existing-nebflow"
    os.makeDir.all(ws6 / ".nebflow")
    os.write.over(ws6 / ".nebflow" / "user-note.md", "keep\n")
    for created <- ProjectStore.create("nebflow-keep", ws6.toString, None, template)
    yield
      assert(created.isRight)
      assertEquals(os.read(ws6 / ".nebflow" / "user-note.md"), "keep\n", "既有 .nebflow/ 内容必须不动")
      assert(!os.exists(ws6 / ".nebflow" / "flow-map.json"), "创建面禁预写 flow-map.json（由挂载首写）")
  }

  // S2 2026-09-17 12:09 裁定单 ③-8/③-9：脚手架单点 + 逐件报告 + 占用扫描的全量读源。
  test("③-8/③-9: ensureScaffold is per-item additive — only the missing piece is backfilled") {
    val ws7 = tempRoot / "ws-ensure-additive"
    os.makeDir.all(ws7)
    for
      first <- ProjectStore.ensureScaffold(ws7, template)
      _ <- IO(os.remove(ws7 / "AGENTS.md"))
      second <- ProjectStore.ensureScaffold(ws7, template)
    yield
      assertEquals(
        first.items.map(i => i.item -> i.created),
        List(".nebflow/" -> true, "AGENTS.md" -> true, ".gitignore" -> true),
        "首次补缺：三件全 created"
      )
      assert(first.render.contains("3 created, 0 skipped"), first.render)
      assertEquals(
        second.items.map(i => i.item -> i.created),
        List(".nebflow/" -> false, "AGENTS.md" -> true, ".gitignore" -> false),
        "二次：只补缺的 AGENTS.md，其余 skipped"
      )
      assertEquals(os.read(ws7 / "AGENTS.md"), template)
      assertEquals(second.render.contains("AGENTS.md created"), true, second.render)
      assert(second.render.contains("1 created, 2 skipped"), second.render)
      assert(!os.exists(ws7 / ".nebflow" / "flow-map.json"), "创建面禁预写 flow-map.json（由挂载首写）")
    end for
  }

  test("③-9: .gitignore append / already-present is reported honestly (no duplicate line, user bytes kept)") {
    val ws8 = tempRoot / "ws-ensure-gitignore"
    os.makeDir.all(ws8)
    os.write.over(ws8 / ".gitignore", "node_modules/\n")
    for
      appended <- ProjectStore.ensureScaffold(ws8, template)
      afterFirst <- IO(os.read(ws8 / ".gitignore"))
      again <- ProjectStore.ensureScaffold(ws8, template)
      afterSecond <- IO(os.read(ws8 / ".gitignore"))
    yield
      val giFirst = appended.items.find(_.item == ".gitignore").getOrElse(fail("item missing"))
      assert(giFirst.created, "既有 .gitignore 缺行 ⇒ 追加 = created")
      assert(giFirst.detail.contains("appended"), giFirst.detail)
      assertEquals(afterFirst, "node_modules/\n.nebflow/\n", "用户内容零改动 + 恰一行 .nebflow/")
      val giSecond = again.items.find(_.item == ".gitignore").getOrElse(fail("item missing"))
      assert(!giSecond.created, "已含 .nebflow/ 行 ⇒ skipped")
      assert(giSecond.detail.contains("already lists"), giSecond.detail)
      assertEquals(afterSecond, afterFirst, "字节不变、不重复追加")
  }

  test("③-8: createWithScaffold reports per-item, keeps create's Left semantics (no exists-and-write)") {
    val ws9 = tempRoot / "ws-create-with-scaffold"
    os.makeDir.all(ws9)
    for
      first <- ProjectStore.createWithScaffold("cw-one", ws9.toString, None, template)
      second <- ProjectStore.createWithScaffold("cw-one", ws9.toString, None, template)
    yield
      assert(first.isRight)
      val (pd, report) = first.toOption.get
      assertEquals(pd.name, "cw-one")
      assertEquals(pd.workspace, ws9.toString)
      assertEquals(report.createdCount, 3)
      assert(report.render.contains("AGENTS.md created"), report.render)
      assert(second.isLeft, "定义已存在仍返 Left —— 防覆盖语义不变（禁改成「已存在也写」）")
      assert(second.swap.toOption.get.contains("already exists"), second.swap.toOption.get)
  }

  test("④-4: listAll() carries archived definitions (occupancy scan source) while list() still filters") {
    val wsA = tempRoot / "ws-listall-a"
    val wsB = tempRoot / "ws-listall-b"
    List(wsA, wsB).foreach(os.makeDir.all)
    for
      _ <- ProjectStore.create("la-one", wsA.toString, None, template)
      _ <- ProjectStore.create("la-two", wsB.toString, None, template)
      _ <- ProjectStore.archive("la-two")
      all <- ProjectStore.listAll()
      visible <- ProjectStore.list()
    yield
      assert(all.exists(_.name == "la-two"), "占用扫描必须看见归档定义（保守默认：宁误拒不误建）")
      assert(all.find(_.name == "la-two").exists(_.archived.contains(true)))
      assert(visible.exists(_.name == "la-one"), "list() 语义不变：非归档仍在列")
      assert(!visible.exists(_.name == "la-two"), "list() 语义不变：归档不在列")
  }

  // ── 台账 #913 ②：设备归属显式标注（device 字段）────────────────────

  test("#913②: create annotates local projects with device=self (explicit on disk)"):
    val wsD = tempRoot / "ws-dev-self"
    os.makeDir.all(wsD)
    for
      _ <- ProjectStore.create("dev-self", wsD.toString, None, template)
      onDisk <- IO(os.read(ProjectStore.projectJsonPath("dev-self")))
      loaded <- ProjectStore.load("dev-self")
    yield
      assert(onDisk.contains("\"device\" : \"self\"") || onDisk.contains("\"device\":\"self\""), onDisk)
      assertEquals(loaded.flatMap(_.device), Some("self"))

  test("#913②: create with remote origin annotates device=origin.device"):
    val wsR = tempRoot / "ws-dev-remote"
    os.makeDir.all(wsR)
    for
      r <- ProjectStore.createWithScaffold(
        "dev-remote",
        "/Users/kaiyu/remote-ws",
        None,
        template,
        origin = Some(ProjectOrigin("peer-device-uuid-1234", "/Users/kaiyu/remote-ws"))
      )
      loaded <- ProjectStore.load("dev-remote")
    yield
      assert(r.isRight)
      assertEquals(r.toOption.get._1.device, Some("peer-device-uuid-1234"))
      assertEquals(loaded.flatMap(_.device), Some("peer-device-uuid-1234"))

  test("#913②: load backfills the device key into a legacy project.json (surgical, other bytes stable)"):
    val wsL = tempRoot / "ws-dev-legacy"
    os.makeDir.all(wsL)
    val legacy =
      """{"name":"dev-legacy","description":"旧定义","workspace":"WORKSPACE","agentFile":"WORKSPACE/AGENTS.md","createdAt":1700000000000}"""
    val dir = tempRoot / "projects" / "dev-legacy"
    os.makeDir.all(dir)
    os.write.over(dir / "project.json", legacy.replace("WORKSPACE", wsL.toString.replace("\\", "\\\\")))
    val before = os.read(dir / "project.json")
    for loaded <- ProjectStore.load("dev-legacy")
    yield
      assertEquals(loaded.flatMap(_.device), Some("self"), "legacy local project backfills to self")
      val after = os.read(dir / "project.json")
      assert(after.contains("\"device\""), "device key written back")
      assert(after.contains("旧定义"), "existing description kept byte-for-byte")
      assert(after.contains("1700000000000"), "existing createdAt kept")
      // 幂等：二次 load 零重写（mtime 不再前进）
      val mtime1 = os.mtime(dir / "project.json")
      Thread.sleep(5)
      _ <- ProjectStore.load("dev-legacy")
      assertEquals(os.mtime(dir / "project.json"), mtime1, "second load must not rewrite (idempotent backfill)")

  test("#913②: effectiveDevice is the single normalization point (device field > origin > self)"):
    val wsE = tempRoot / "ws-eff"
    os.makeDir.all(wsE)
    val base = ProjectDef(name = "eff", workspace = wsE.toString, agentFile = "x", createdAt = 1L)
    // ① 无任何标注 → self
    assertEquals(ProjectStore.effectiveDevice(base), "self")
    // ② origin 回导（非 self 字面）
    val withOrigin =
      base.copy(origin = Some(ProjectOrigin("peer-origin-id", "/remote/ws")))
    assertEquals(ProjectStore.effectiveDevice(withOrigin), "peer-origin-id")
    // ③ 显式 device 字段优先于 origin
    assertEquals(ProjectStore.effectiveDevice(withOrigin.copy(device = Some("explicit-id"))), "explicit-id")
    // ④ self 字面 origin 不回导（本机项目）
    assertEquals(ProjectStore.effectiveDevice(base.copy(origin = Some(ProjectOrigin("self", wsE.toString)))), "self")
    // ⑤ 空串 device 视作缺席
    assertEquals(ProjectStore.effectiveDevice(base.copy(device = Some("  "))), "self")

  test("#913②: displayDevice resolves self→local, roster hit→name, miss→raw id (honest fallback)"):
    assertEquals(ProjectStore.displayDevice("self", _ => Some("X")), "local")
    assertEquals(ProjectStore.displayDevice("", _ => Some("X")), "local")
    assertEquals(ProjectStore.displayDevice("id-9", Map("id-9" -> "Mac").get), "Mac")
    assertEquals(ProjectStore.displayDevice("id-9", _ => None), "id-9")

  // ── 台账 #913 ④：description 单字段 update ────────────────────────

  test("#913④: updateDescription sets the field; every other project.json field is byte-stable"):
    val wsU = tempRoot / "ws-update"
    os.makeDir.all(wsU)
    for
      _ <- ProjectStore.create("upd-one", wsU.toString, Some("旧的"), template)
      before <- IO(os.read(ProjectStore.projectJsonPath("upd-one")))
      r1 <- ProjectStore.updateDescription("upd-one", "新的描述")
      after <- IO(os.read(ProjectStore.projectJsonPath("upd-one")))
      loaded <- ProjectStore.load("upd-one")
    yield
      assertEquals(r1, Right("updated"))
      assertEquals(loaded.flatMap(_.description), Some("新的描述"))
      // 其余字段逐字节稳定（除 description 外的键值对全部保留）
      def withoutDesc(s: String) = s.split(",").filterNot(_.trim.startsWith("\"description\"")).mkString(",")
      assert(
        withoutDesc(before).split(",").toSet.subsetOf(withoutDesc(after).split(",").toSet),
        s"non-description fields must survive: before=$before after=$after"
      )
      assert(after.contains("新的描述"), after)

  test("#913④: updateDescription is idempotent — same value returns unchanged with zero write"):
    val wsU2 = tempRoot / "ws-update-2"
    os.makeDir.all(wsU2)
    for
      _ <- ProjectStore.create("upd-two", wsU2.toString, Some("同一句"), template)
      r1 <- ProjectStore.updateDescription("upd-two", "同一句")
      mtime1 = os.mtime(ProjectStore.projectJsonPath("upd-two"))
      r2 <- ProjectStore.updateDescription("upd-two", "同一句")
      mtime2 = os.mtime(ProjectStore.projectJsonPath("upd-two"))
    yield
      assertEquals(r1, Right("updated"))
      assertEquals(r2, Right("unchanged"), "same value → idempotent no-op")
      assertEquals(mtime1, mtime2, "zero write on the idempotent path")

  test("#913④: empty description clears the field (decodes back to None)"):
    val wsU3 = tempRoot / "ws-update-3"
    os.makeDir.all(wsU3)
    for
      _ <- ProjectStore.create("upd-three", wsU3.toString, Some("将被清空"), template)
      r1 <- ProjectStore.updateDescription("upd-three", "   ")
      onDisk <- IO(os.read(ProjectStore.projectJsonPath("upd-three")))
      loaded <- ProjectStore.load("upd-three")
    yield
      assertEquals(r1, Right("updated"))
      assert(!onDisk.contains("\"description\""), "cleared = key removed (empty string never written)")
      assertEquals(loaded.flatMap(_.description), None)

  test("#913④: updateDescription errors follow the archive/markUnmounted family"):
    for
      missing <- ProjectStore.updateDescription("no-such-project", "x")
      invalid <- ProjectStore.updateDescription("../evil", "x")
    yield
      assert(missing == Left("Project 'no-such-project' not found"), missing.toString)
      assert(invalid.isLeft, "path traversal refused")

end ProjectStoreSpec
