package nebflow.core.project

import munit.FunSuite

/** NodePayload.buildNodeJson 的 plugins 条件序列化契约（Flow Map 节点卡显示插件分配批
  * 2026-09-06）。卡片副行 `preset · p1, p2` 的数据源 = 本字段；契约两点：
  *
  * 1. 带插件分配节点 → 载荷携带 "plugins" 名字数组（只放名字，禁塞描述全文）；
  * 2. 无插件节点 → 载荷不携带该键（条件字段与 deps/merge/loop 同构，字段集零漂移；
  *    精确键集另有 NodeEventPushSpec NodeListKeys 断言兜底）。
  *
  * 纯单元级（NodeDef → Json 单点序列化，无 engine/actor），任何环境可跑。 */
class NodePayloadSpec extends FunSuite:

  test("buildNodeJson: plugin-assigned node carries plugins name array") {
    val node = NodeDef(
      id = "n-p",
      name = "带插件",
      agent = "general",
      preset = Some("dev"),
      plugins = List("nebflow-frontend-dev", "nebflow-backend-dev"),
      createdAt = 1000L
    )
    val j = NodePayload.buildNodeJson(node, now = 2000L)
    assertEquals(
      j.hcursor.get[List[String]]("plugins").toOption,
      Some(List("nebflow-frontend-dev", "nebflow-backend-dev")),
      "payload must carry the plugins name array for plugin-assigned nodes"
    )
  }

  test("buildNodeJson: plugin-less node omits the plugins key (field-set zero drift)") {
    val node = NodeDef(id = "n-q", name = "无插件", agent = "general", createdAt = 1000L)
    val j = NodePayload.buildNodeJson(node, now = 2000L)
    assert(
      !j.asObject.exists(_.contains("plugins")),
      s"plugin-less node payload must NOT carry the plugins key, got keys: ${j.asObject.map(_.keys.toList.sorted)}"
    )
  }

  test("buildNodeJson: blocked node carries blockedFeedback; non-blocked terminals omit the key (裁定②)") {
    val bf = BlockedFeedback("task-underspecified", "缺交付物定义", "补充验收标准")
    val base = NodeDef(id = "n-bf", name = "bf", agent = "general", createdAt = 1000L)
    // blocked 态：键在 + 结构化三字段透传（封顶属 BlockedReader 入库单点）
    val jb = NodePayload.buildNodeJson(base.copy(status = NodeLifecycle.Blocked, blockedFeedback = Some(bf)), now = 2000L)
    val bfCur = jb.hcursor.downField("blockedFeedback")
    assertEquals(bfCur.get[String]("category").toOption, Some("task-underspecified"), "blocked node must carry structured feedback")
    assertEquals(bfCur.get[String]("detail").toOption, Some("缺交付物定义"))
    assertEquals(bfCur.get[String]("suggestion").toOption, Some("补充验收标准"))
    // 无反馈的 blocked 节点也不带键（字段存在才条件序列化）
    val jbNoFeedback = NodePayload.buildNodeJson(base.copy(status = NodeLifecycle.Blocked), now = 2000L)
    assert(!jbNoFeedback.asObject.exists(_.contains("blockedFeedback")), "blocked without feedback body must not carry the key")
    // 非 blocked 终态：历史残留不进默认载荷（裁定② 核心——审计实测 9 节点泄漏 11.6KB）
    for st <- List(NodeLifecycle.Completed, NodeLifecycle.Failed, NodeLifecycle.Cancelled) do
      val j = NodePayload.buildNodeJson(base.copy(status = st, blockedFeedback = Some(bf)), now = 2000L)
      assert(!j.asObject.exists(_.contains("blockedFeedback")), s"status=$st must NOT carry blockedFeedback (裁定②), got keys: ${j.asObject.map(_.keys.toList.sorted)}")
  }

end NodePayloadSpec
