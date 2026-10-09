package nebflow.core.delegate

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.SubAgentTaskStore
import nebflow.core.project.ChainsReadFace
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * delegate-ui 批（2026-10-08）数据面补齐的机械自证（G1/G3/G5/G6 + G4 聚合投影）：
 *
 *  - **G1** 编排模式：`orchestrationMode` 往返；旧盘面缺键 = None（禁静默吞行——
 *    cliSessionId/plugins 同款纪律）；[[DelegateRegistry.upgradeOrchestrationMode]]
 *    只上不下（single < subagent-star < agentflow）、查无记录 = Left。
 *  - **G3** `SubAgentTaskStore.findByParent` 按父整表读 + recordTask 记账点的
 *    星型惰性升级挂钩（父为 delegate 记录 ⇒ 升 subagent-star）。
 *  - **G5** rows 排队条件键纪律：未排队行 `mergeQueue` / `mergeQueuePos` **缺键**
    * （「只收非空项」同款；排队态真源 = MergeQueue 进程内队列，spec 零注入）。
 *  - **G6** `archive` 单旗写幂等；rows 臂在册窗/历史窗互补零重叠（零删除）。
 *  - **G4** `ChainsReadFace.delegateJson` 聚合形状（record/live/verdict/task/childrenCount）。
 *
 * 隔离纪律：`PathUtil.setDataRoot(tempRoot)`（DelegateRegistryPluginsSpec 同款）；
 * 台账单例面零触碰（本 spec 全部 taskId=None，防共享单例读写真 HOME）。
 */
class DelegateUiDataPlaneSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-ui-dataplane"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "chains")
    ()

  private def put(id: String, mode: Option[String] = None, archivedAt: Option[Long] = None): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = "/tmp/w",
        project = None,
        taskId = None,
        parentSessionId = "root",
        title = "t",
        createdAt = 1L,
        orchestrationMode = mode,
        archivedAt = archivedAt
      )
    )

  // ── G1：编排模式 ─────────────────────────────────────────────────────────

  test("G1: orchestrationMode round-trips and the spawn initial value is queryable") {
    put("delegate-nebflow-g1spawn01", mode = Some(DelegateRegistry.ModeSingle))
    assertEquals(
      DelegateRegistry.find("delegate-nebflow-g1spawn01").flatMap(_.orchestrationMode),
      Some(DelegateRegistry.ModeSingle)
    )
  }

  test("G1: a pre-batch on-disk record without the new keys decodes with both None (never silently dropped)") {
    val dir = tempRoot / "delegates"
    os.makeDir.all(dir)
    os.write.over(
      dir / "delegate-nebflow-g1old001.json",
      """{"id":"delegate-nebflow-g1old001","executor":"nebflow","cwd":"/tmp/w","project":null,"taskId":null,"parentSessionId":"root","title":"legacy","createdAt":3}"""
    )
    val rec = DelegateRegistry.find("delegate-nebflow-g1old001")
    assert(rec.isDefined, "legacy record must decode")
    assertEquals(rec.flatMap(_.orchestrationMode), None)
    assertEquals(rec.flatMap(_.archivedAt), None)
  }

  test("G1: upgrade ladder only moves up (single < subagent-star < agentflow), idempotent at the top") {
    put("delegate-nebflow-g1lad0001")
    assertEquals(DelegateRegistry.upgradeOrchestrationMode("delegate-nebflow-g1lad0001", DelegateRegistry.ModeSubagentStar), Right(()))
    assertEquals(DelegateRegistry.find("delegate-nebflow-g1lad0001").flatMap(_.orchestrationMode), Some(DelegateRegistry.ModeSubagentStar))
    // 只上不下：star 不会被 single/subagent-star 降级或重写
    assertEquals(DelegateRegistry.upgradeOrchestrationMode("delegate-nebflow-g1lad0001", DelegateRegistry.ModeSingle), Right(()))
    assertEquals(DelegateRegistry.find("delegate-nebflow-g1lad0001").flatMap(_.orchestrationMode), Some(DelegateRegistry.ModeSubagentStar))
    assertEquals(DelegateRegistry.upgradeOrchestrationMode("delegate-nebflow-g1lad0001", DelegateRegistry.ModeAgentflow), Right(()))
    assertEquals(DelegateRegistry.find("delegate-nebflow-g1lad0001").flatMap(_.orchestrationMode), Some(DelegateRegistry.ModeAgentflow))
    assertEquals(DelegateRegistry.upgradeOrchestrationMode("delegate-nebflow-g1lad0001", DelegateRegistry.ModeSubagentStar), Right(()))
    assertEquals(DelegateRegistry.find("delegate-nebflow-g1lad0001").flatMap(_.orchestrationMode), Some(DelegateRegistry.ModeAgentflow))
  }

  test("G1: upgrade on an unknown id is an explicit Left (call sites are fail-soft by contract)") {
    assert(DelegateRegistry.upgradeOrchestrationMode("delegate-nebflow-nosuch01", DelegateRegistry.ModeAgentflow).isLeft)
  }

  // ── G3：findByParent + 记账点升级挂钩 ─────────────────────────────────────

  test("G3: findByParent reads the per-parent file (whole domain, source filter belongs to consumers)") {
    val store = new SubAgentTaskStore(tempRoot / "subagent-tasks")
    val t = (tid: String, src: String) =>
      SubAgentTask(
        taskId = tid,
        parentSessionId = "delegate-nebflow-g3par0001",
        agentName = "nebflow",
        prompt = "p",
        description = "d",
        status = "running",
        retryCount = 0,
        spawnedAt = 1L,
        completedAt = None,
        lastError = None,
        source = src
      )
    store.recordTask(t("subtask-a", "subtask")).unsafeRunSync()
    store.recordTask(t("subagent-b", "subagent")).unsafeRunSync()
    store.recordTask(t("delegate-c", "delegate")).unsafeRunSync()
    val kids = store.findByParent("delegate-nebflow-g3par0001").unsafeRunSync()
    assertEquals(kids.map(_.taskId).sorted, List("delegate-c", "subagent-b", "subtask-a"))
    assertEquals(store.findByParent("delegate-nebflow-nofile1").unsafeRunSync(), Nil)
  }

  test("G3/G1: recordTask with a delegate parent lazily upgrades the parent to subagent-star") {
    put("delegate-nebflow-g3hook001")
    val store = new SubAgentTaskStore(tempRoot / "subagent-tasks")
    store
      .recordTask(
        SubAgentTask(
          taskId = "subtask-kid-1",
          parentSessionId = "delegate-nebflow-g3hook001",
          agentName = "nebflow",
          prompt = "p",
          description = "d",
          status = "running",
          retryCount = 0,
          spawnedAt = 1L,
          completedAt = None,
          lastError = None,
          source = "subtask"
        )
      )
      .unsafeRunSync()
    assertEquals(
      DelegateRegistry.find("delegate-nebflow-g3hook001").flatMap(_.orchestrationMode),
      Some(DelegateRegistry.ModeSubagentStar)
    )
  }

  // ── G5/G6：rows 投影 ─────────────────────────────────────────────────────

  test("G5: an un-queued row carries NO mergeQueue/mergeQueuePos keys (missing key = not queued)") {
    put("delegate-nebflow-g5row0001")
    val rows = ChainsReadFace.rowsJson(Map.empty).unsafeRunSync()
    val row = rows.hcursor.downField("rows").downArray.downField("id").as[String]
    assertEquals(row, Right("delegate-nebflow-g5row0001"))
    val obj = rows.hcursor.downField("rows").downArray.as[io.circe.JsonObject]
    assert(obj.isRight)
    assert(!obj.toOption.get.contains("mergeQueue"), "un-queued row must not carry mergeQueue")
    assert(!obj.toOption.get.contains("mergeQueuePos"), "un-queued row must not carry mergeQueuePos")
  }

  test("G6: rows default window excludes archived; ?archived=1 window is exactly the archived set") {
    put("delegate-nebflow-g6live001")
    put("delegate-nebflow-g6arch01")
    val ts = DelegateRegistry.archive("delegate-nebflow-g6arch01")
    assert(ts.isRight)
    val live = ChainsReadFace.rowsJson(Map.empty, archived = false).unsafeRunSync()
    val hist = ChainsReadFace.rowsJson(Map.empty, archived = true).unsafeRunSync()
    def ids(j: io.circe.Json): List[String] =
      j.hcursor.downField("rows").as[List[io.circe.Json]].getOrElse(Nil).flatMap(_.hcursor.get[String]("id").toOption)
    assertEquals(ids(live), List("delegate-nebflow-g6live001"))
    assertEquals(ids(hist), List("delegate-nebflow-g6arch01"))
    assertEquals(
      DelegateRegistry.find("delegate-nebflow-g6arch01").flatMap(_.archivedAt),
      ts.toOption,
      "archivedAt survives the read-back (zero-delete: record stays queryable)"
    )
  }

  test("G6: archive is idempotent (second call returns the same timestamp, no rewrite)") {
    put("delegate-nebflow-g6idem001")
    val first = DelegateRegistry.archive("delegate-nebflow-g6idem001")
    val second = DelegateRegistry.archive("delegate-nebflow-g6idem001")
    assertEquals(first, second)
  }

  // ── G4：聚合单查投影 ─────────────────────────────────────────────────────

  test("G4: delegateJson aggregates record+live(null)+verdict(null)+task(null)+childrenCount") {
    put("delegate-nebflow-g4agg0001")
    val agg = ChainsReadFace.delegateJson("delegate-nebflow-g4agg0001", Map.empty, childrenCount = 3).unsafeRunSync()
    assert(agg.isDefined, "existing record must aggregate")
    val obj = agg.get.hcursor
    assertEquals(obj.downField("record").downField("id").as[String], Right("delegate-nebflow-g4agg0001"))
    assertEquals(obj.downField("childrenCount").as[Int], Right(3))
    assertEquals(obj.downField("live").focus, Some(io.circe.Json.Null))
    assertEquals(obj.downField("task").focus, Some(io.circe.Json.Null))
    assertEquals(obj.downField("verdict").focus, Some(io.circe.Json.Null))
  }

  test("G4: unknown/invalid id = None (HTTP 404/400 face)") {
    assertEquals(ChainsReadFace.delegateJson("delegate-nebflow-nosuch02", Map.empty, 0).unsafeRunSync(), None)
    assertEquals(ChainsReadFace.delegateJson("../escape", Map.empty, 0).unsafeRunSync(), None)
  }

end DelegateUiDataPlaneSpec
