package nebflow.core.tools

import io.circe.Json
import munit.FunSuite
import nebflow.core.project.NodeLifecycle

/**
 * NodeTools 纯校验单测（#28 阶段 0，§2.4）——不依赖 runtime 的部分：
 * - parseOut：单值 string / "Nebula" / null 接受；数组（1 对多）拒绝
 * - NodeLifecycle 状态枚举与 Terminal 集合
 */
class NodeToolsSpec extends FunSuite:

  test("parseOut: single node id accepted") {
    assertEquals(NodeTools.parseOut(Some(Json.fromString("n-abc12345"))), Right(Some("n-abc12345")))
  }

  test("parseOut: Nebula accepted") {
    assertEquals(NodeTools.parseOut(Some(Json.fromString("Nebula"))), Right(Some("Nebula")))
  }

  test("parseOut: null → disconnect (Right(None))") {
    assertEquals(NodeTools.parseOut(Some(Json.Null)), Right(None))
  }

  test("parseOut: absent → unchanged (Right(None))") {
    assertEquals(NodeTools.parseOut(None), Right(None))
  }

  test("parseOut: array rejected (1-to-many not supported)") {
    val arr = Json.arr(Json.fromString("n1"), Json.fromString("n2"))
    assert(NodeTools.parseOut(Some(arr)).isLeft)
  }

  test("parseOut: empty string rejected") {
    assert(NodeTools.parseOut(Some(Json.fromString(""))).isLeft)
  }

  test("NodeLifecycle: Terminal = completed/failed/cancelled, excludes running/pending/wiring") {
    assertEquals(
      NodeLifecycle.Terminal,
      Set(NodeLifecycle.Completed, NodeLifecycle.Failed, NodeLifecycle.Cancelled)
    )
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Running))
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Pending))
    assert(!NodeLifecycle.Terminal.contains(NodeLifecycle.Wiring))
  }

end NodeToolsSpec
