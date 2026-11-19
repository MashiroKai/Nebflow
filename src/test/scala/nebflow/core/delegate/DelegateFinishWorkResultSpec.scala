package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * DelegateRegistry 申报槽的 preserve-before-overwrite 判读：`updateReport` 单槽
 * 覆写时旧 detail 留底进 `workReport`（工作详报不随后续申报覆写而丢失）；已留底槽
 * first-write-wins 不被顶掉；同 detail 重报零留底。
 */
class DelegateFinishWorkResultSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-finish-report"
  private val originalRoot = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)
    nebflow.ForceRemove.removeAll(tempRoot)
    os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")

  private def seedRecord(
    id: String,
    taskId: Option[String],
    branch: Option[String],
    workReport: Option[String] = None
  ): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tempRoot.toString,
        project = None,
        taskId = taskId,
        parentSessionId = "nebula-root",
        title = "seeded",
        createdAt = 1L,
        branch = branch,
        baseBranch = Some("main"),
        projectWorkspace = Some(tempRoot.toString),
        workReport = workReport
      )
    )

  test(
    "② updateReport preserve-before-overwrite: the prior detail survives the overwrite; a preserved slot is never displaced; identical re-reports seed nothing"
  ) {
    // 老形态（无留底槽）：详报被短报覆写 ⇒ 旧 detail 留底
    seedRecord("delegate-nebflow-wr2", Some("82"), Some("delegate/x"))
    DelegateRegistry.updateReport("delegate-nebflow-wr2", "standby", "WORK-PHASE DETAIL: root cause + evidence", "")
    DelegateRegistry.updateReport("delegate-nebflow-wr2", "finish", "wrapped up", "")
    val legacy = DelegateRegistry.find("delegate-nebflow-wr2")
    // 留底形态：workReport 已在槽 ⇒ 申报覆写绝不顶掉
    seedRecord("delegate-nebflow-wr3", Some("83"), Some("delegate/x"), workReport = Some("PRESERVED WORK TEXT"))
    DelegateRegistry.updateReport("delegate-nebflow-wr3", "finish", "wrapped up", "")
    val preserved = DelegateRegistry.find("delegate-nebflow-wr3")
    // 同 detail 重报：无留底（无信息可保）
    seedRecord("delegate-nebflow-wr4", Some("84"), Some("delegate/x"))
    DelegateRegistry.updateReport("delegate-nebflow-wr4", "standby", "same detail", "")
    DelegateRegistry.updateReport("delegate-nebflow-wr4", "standby", "same detail", "")
    val noSeed = DelegateRegistry.find("delegate-nebflow-wr4")
    assert(
      legacy.flatMap(_.workReport).contains("WORK-PHASE DETAIL: root cause + evidence"),
      s"the overwritten detail must be preserved into workReport; got: ${legacy.flatMap(_.workReport)}"
    )
    assertEquals(legacy.flatMap(_.reportDetail), Some("wrapped up"), "the finish detail still owns the slot")
    assertEquals(preserved.flatMap(_.workReport), Some("PRESERVED WORK TEXT"), "first-write-wins over the report slot")
    assertEquals(noSeed.flatMap(_.workReport), None, "identical re-reports must not seed a work report")
  }

end DelegateFinishWorkResultSpec
