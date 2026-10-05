package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.PathUtil

/**
 * delegate_report 判读（worktree-judge-fifo 批 #1224 增补 §3.4，S7/S8）：
 *
 *  - 身份双保险之工具内侧：非 delegate 会话（engine-side 判据
 *    DelegateSessions.isDelegateSession）→ DELEGATE_REPORT_FORBIDDEN；
 *  - 值域 finish | blocked：非法值拒；detail 空 = 拒（对齐 node_report 门形）；
 *  - **仅状态单写**：合法申报只落 DelegateRegistry 状态槽（reportStatus/Detail/
 *    Suggestion），其余字段（cwd/branch/…）原样保真——不携汇报面；
 *  - 未 spawn（查无记录）= 显式拒，不凭空造状态。
 */
class DelegateReportToolSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-report"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def ctx(sessionId: Option[String]): ToolContext =
    ToolContext(projectRoot = tempRoot.toString, sessionId = sessionId)

  private def call(sessionId: Option[String] = Some("delegate-nebflow-abc"))(input: (String, Json)*): IO[Either[String, String]] =
    DelegateReportTool.call(Json.obj(input.toList*).asObject.get, ctx(sessionId)).map(_.left.map(_.message))

  private def seed(id: String): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id, executor = "nebflow", cwd = tempRoot.toString, project = None, taskId = Some("77"),
        parentSessionId = "nebula-root", title = "seeded", createdAt = 1L
      )
    )

  test("identity gate: a non-delegate session is refused (mount face + in-tool check twin safeguard)") {
    for
      denied <- call(sessionId = Some("root-session"))("category" -> "finish".asJson, "detail" -> "x".asJson)
      none <- call(sessionId = None)("category" -> "finish".asJson, "detail" -> "x".asJson)
    yield
      assert(denied.left.exists(_.contains("DELEGATE_REPORT_FORBIDDEN")), s"got: $denied")
      assert(none.left.exists(_.contains("DELEGATE_REPORT_FORBIDDEN")))
  }

  test("value domain: invalid category and empty detail are refused with actionable codes") {
    for
      badCat <- call()("category" -> "pass".asJson, "detail" -> "x".asJson)
      emptyDetail <- call()("category" -> "finish".asJson, "detail" -> "  ".asJson)
    yield
      assert(badCat.left.exists(_.contains("DELEGATE_REPORT_CATEGORY")))
      assert(emptyDetail.left.exists(_.contains("DELEGATE_REPORT_DETAIL")))
  }

  test("status single-write: a legal report lands ONLY in the DelegateRegistry status slot, other fields untouched") {
    val id = "delegate-nebflow-rw"
    seed(id)
    for
      res <- call(sessionId = Some(id))(
        "category" -> "blocked".asJson,
        "detail" -> "missing credentials".asJson,
        "suggestion" -> "ask for the key".asJson
      )
      rec = DelegateRegistry.find(id)
    yield
      assert(res.isRight, s"legal report must succeed, got: $res")
      assertEquals(rec.map(_.reportStatus), Some(Some("blocked")))
      assertEquals(rec.map(_.reportDetail), Some(Some("missing credentials")))
      assertEquals(rec.map(_.reportSuggestion), Some(Some("ask for the key")))
      // 其余字段原样保真（仅状态单写——不携汇报、不动元数据）
      assertEquals(rec.map(_.cwd), Some(tempRoot.toString))
      assertEquals(rec.map(_.taskId), Some(Some("77")))
      assertEquals(rec.map(_.title), Some("seeded"))
      assertEquals(rec.map(_.executor), Some("nebflow"))
  }

  test("no record, no write: reporting from an unspawned session id is refused, not fabricated") {
    for res <- call(sessionId = Some("delegate-nebflow-ghost"))("category" -> "finish".asJson, "detail" -> "x".asJson)
    yield assert(res.left.exists(_.contains("no registry record")))
  }

  test("backward-compatible decode: a legacy record JSON without the new fields still decodes (deriveDecoder would drop it)") {
    seed("delegate-nebflow-legacy")
    // 模拟旧版记录：重写为无 report*/branch/worktree/baseBranch/projectWorkspace 键的 JSON
    val legacy = io.circe.parser.parse(
      """{"id":"delegate-nebflow-legacy","executor":"nebflow","cwd":"/tmp","project":null,"taskId":"1","parentSessionId":"p","title":"old","createdAt":5}"""
    ).toOption.get
    os.write.over(PathUtil.dataRoot / "delegates" / "delegate-nebflow-legacy.json", legacy.noSpaces)
    val rec = DelegateRegistry.find("delegate-nebflow-legacy")
    assertEquals(rec.map(_.title), Some("old"), "legacy record must survive the tolerant decoder")
    assertEquals(rec.map(_.reportStatus), Some(None))
  }

end DelegateReportToolSpec
