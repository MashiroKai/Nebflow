package nebflow.core.tools

import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

class MergerToolAccessSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd
  override def beforeAll(): Unit =
    root = os.temp.dir(prefix = "merger-tool-")
    PathUtil.setDataRoot(root)
  override def afterAll(): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  test("only the main agent may submit or cancel merge requests") {
    val ctx = ToolContext(projectRoot = root.toString, sessionId = Some("delegate-developer"))
    for
      submit <- MergerRequestTool.call(
        JsonObject(
          "project" -> io.circe.Json.fromString("project"),
          "requestId" -> io.circe.Json.fromString("request"),
          "workspaceIds" -> io.circe.Json.arr(io.circe.Json.fromString("task")),
          "authorization" -> io.circe.Json.fromString("user authorized")
        ),
        ctx
      )
      cancel <- MergerRequestTool.call(
        JsonObject(
          "action" -> io.circe.Json.fromString("cancel"),
          "project" -> io.circe.Json.fromString("project"),
          "requestId" -> io.circe.Json.fromString("request")
        ),
        ctx
      )
    yield
      assert(submit.isLeft)
      assert(cancel.isLeft)
      assert(submit.left.toOption.get.message.contains("Only the main agent"))
      assert(cancel.left.toOption.get.message.contains("Only the main agent"))
  }
