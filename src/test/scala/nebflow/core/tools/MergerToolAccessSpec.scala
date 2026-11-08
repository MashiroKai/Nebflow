package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.shared.PathUtil
import nebflow.core.delegate.WorkspaceLedger

class MergerToolAccessSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd
  override def beforeAll(): Unit =
    root = os.temp.dir(prefix = "merger-tool-")
    PathUtil.setDataRoot(root)
  override def afterAll(): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  test("development delegates cannot submit merge requests or report merger state") {
    val ctx = ToolContext(projectRoot = root.toString, sessionId = Some("delegate-developer"))
    for
      request <- MergerRequestTool.call(JsonObject.empty, ctx)
      report <- MergerReportTool.call(JsonObject.empty, ctx)
    yield
      assert(request.isLeft)
      assert(report.isLeft)
  }
  test("only the recorded merger may inspect its requests") {
    WorkspaceLedger.default.merger("project", "repo", "delegate-merger")
    val ctx = ToolContext(projectRoot = root.toString, sessionId = Some("delegate-merger"))
    MergerReportTool.call(JsonObject("action" -> io.circe.Json.fromString("inspect")), ctx).map { result =>
      assert(result.isRight)
      assert(result.toOption.get.contains("requests"))
    }
  }
