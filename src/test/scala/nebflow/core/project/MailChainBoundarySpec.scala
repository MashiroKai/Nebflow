package nebflow.core.project

import munit.FunSuite

/**
 * chainmodel 批三（chainmail）**链面边界**纯函数判据 spec —— 三个受judged面各一条：
 *
 *  - T1 **Mail 链号校验集合**（批三 ①）：声明链 ∪ 兜底派生链 ∪ 链级依赖目标链 ∪
 *    台账已登记面（条目标号 + 旧号别名）；**未登记号不在集合内**（负判据保留）。
 *  - T2 **Flow Map 载荷链边界**（批三 ②）：`chains[]` = 声明轨（不论成员数）∪ 兜底轨
 *    （未声明者需成员数 ≥2）+ 含活动成员约束 —— 即 [[FlowMapStore.payloadChains]] 单点。
 *    🔴 变异承重：把声明轨摘除（改回「成员 ≥2」纯兜底口径）⇒ 本测必红（复核位复跑点）。
 *  - T3 **merge 两值**（批三 ②）：`mergeChainAttrs` 同时给出「所属（声明）链」与
 *    「本次汇聚的上游链」；旧键 `chainIds` 语义**逐字保留**（所属链首项 + 全量成员链）。
 *  - T4 新键 `mergeUpstreamChains` 的载荷条件序列化（注入才带；缺省零字段漂移）。
 *
 * 端到端面（Mail 真投递 + 台账别名放行）在 `NodeMessageSpec` 的 S7/S7b/S7c 三条。
 */
class MailChainBoundarySpec extends FunSuite:

  private def n(
      id: String,
      createdAt: Long,
      in: List[String] = Nil,
      out: List[OutEdge] = Nil,
      deps: List[String] = Nil,
      chainId: Option[String] = None,
      merge: Boolean = false,
      status: String = NodeLifecycle.Completed
  ): NodeDef =
    NodeDef(id = id, name = id, agent = "general", status = status, in = in, out = out, deps = deps,
      createdAt = createdAt, chainId = chainId, merge = merge)

  private def mapOf(nodes: NodeDef*): Map[String, NodeDef] = nodes.map(x => x.id -> x).toMap

  // ── T1 Mail 链号校验集合（批三 ①）────────────────────────────────────────

  test("T1 mailChainIdSet: 声明链 ∪ 兜底派生链 ∪ 链级依赖目标 ∪ 台账登记面（含旧号别名）；未登记号仍不在集合内") {
    val nodes = List(
      n("u-1", 1), n("u-2", 2, in = List("u-1")),            // 兜底分量（≥2 成员）⇒ chain-u-1
      n("solo", 3),                                          // 兜底单成员分量 ⇒ chain-solo
      n("decl", 4, chainId = Some("chain-declared-fx")),     // 声明链（单成员）
      n("waiter", 5, deps = List("chain:chain-batch-x"))     // 链级依赖目标 ⇒ chain-batch-x
    )
    val combined = mapOf(nodes*)
    val cs = FlowMapStore.topologicalChains(nodes)
    val ledger = ChainLedger.State(
      entries = Map("chain-ledger-canon" -> ChainLedger.Entry(
        chainId = "chain-ledger-canon", anchor = "chain-ledger-canon", bornAt = 1L)),
      aliases = Map("chain-legacy-old" -> ChainLedger.AliasRow(
        alias = "chain-legacy-old", canonical = "chain-ledger-canon", createdAt = 1L))
    )
    val s = FlowMapStore.mailChainIdSet(combined, cs, ledger)
    assert(s.contains("chain-u-1"), "兜底派生链（分量 ≥2 成员）可达")
    assert(s.contains("chain-solo"), "兜底派生链（单成员分量）可达 —— 与改造前口径逐字同源")
    assert(s.contains("chain-declared-fx"), "声明链可达（声明即归属）")
    assert(s.contains("chain-batch-x"), "链级依赖目标链可达（deps `chain:<id>` 引用面）")
    assert(s.contains("chain-ledger-canon"), "台账已出生条目标号可达")
    assert(s.contains("chain-legacy-old"), "🔴 台账旧号别名可达（批三 ① 的核心正判据）")
    assert(!s.contains("chain-n-unknown"), "🔴 负判据：完全未登记号不在集合内（禁放宽为「未知也放行」）")
    assert(!s.contains("chain:chain-batch-x"), "目标值不含 `chain:` 前缀（值域与写路径判据同源）")
  }

  // ── T2 Flow Map 载荷链边界（批三 ② / 判据 4）────────────────────────────

  test("T2 payloadChains: 声明轨（不论成员数）∪ 兜底轨（≥2 成员）+ 含活动成员约束") {
    val nodes = List(
      n("d-1", 1, chainId = Some("chain-declared-fx")),      // 声明单成员链
      n("u-1", 2), n("u-2", 3, in = List("u-1")),            // 兜底分量 ⇒ chain-u-1
      n("alone", 4)                                          // 兜底单成员 ⇒ 不下发（payload 零膨胀）
    )
    val combined = mapOf(nodes*)
    val active = Set("d-1", "u-1", "u-2", "alone")
    val ids = FlowMapStore.payloadChains(combined, active).map(_.id)
    assert(ids.contains("chain-declared-fx"), "声明链不论成员数都进 chains[]（批一 ① 声明轨）")
    assert(ids.contains("chain-u-1"), "未声明者的兜底分量进 chains[]")
    assert(!ids.contains("chain-alone"), "兜底单成员分量不进 chains[]（成员数 ≥2 门槛逐字保留）")
    assert(!FlowMapStore.payloadChains(combined, Set("d-1")).map(_.id).contains("chain-u-1"),
      "无活动成员的分量不进主图旁挂（含活动成员约束）")

    // 🔴 变异承重（复核位复跑点）：把 chains[] 组装改回「成员 ≥2」纯兜底口径 ⇒ 下句失败
    val pureFallback = FlowMapStore.topologicalChains(nodes)
      .filter(c => c.memberIds.size >= 2 && c.memberIds.exists(active.contains)).map(_.id)
    assert(!pureFallback.contains("chain-declared-fx"),
      "纯兜底口径下声明单成员链缺席 ⇒ 判据 4 的承重红面（若本句失败，说明 chains[] 已不含声明轨）")
  }

  // ── T3 merge 两值（批三 ② / 判据 5）────────────────────────────────────

  test("T3 mergeChainAttrs: 两个值（所属声明链 / 本次汇聚上游链）+ 旧键 chainIds 逐字保留") {
    // 菱形：两条支线 e1/e2 汇聚到 merge m，再出 t
    val nodes = List(
      n("e1", 1), n("e2", 2),
      n("m", 3, in = List("e1", "e2"), merge = true, status = NodeLifecycle.Wiring),
      n("t", 4, in = List("m"))
    )
    val combined = mapOf(nodes*)
    val cs = FlowMapStore.topologicalChains(nodes)
    val attrs = FlowMapStore.mergeChainAttrs(combined, cs, "m")
    assert(attrs.isDefined, s"merge 节点应带两值，got None（chains=${cs.map(_.id)}）")
    val a = attrs.get
    assertEquals(a.ownChain, "chain-e1", "所属链 = 分量 id（未声明者 = chain-<分量最早 createdAt 节点>）")
    assertEquals(a.upstreamChains, List("chain-e1", "chain-e2"), "上游链 = 入口可达分解（分量 entries 序）")
    assertEquals(a.chainIds, List("chain-e1", "chain-e2"), "旧键 = 所属链首项 + 全量上游链（去重保序）")
    assertEquals(FlowMapStore.mergeChainIds(combined, cs, "m"), Some(List("chain-e1", "chain-e2")),
      "旧键投影与 MergeChainAttrs.chainIds 同源（改造前语义逐字保留）")
    assertEquals(FlowMapStore.mergeUpstreamChains(combined, cs, "m"), Some(List("chain-e1", "chain-e2")),
      "新键投影 = upstreamChains")
    assertEquals(FlowMapStore.mergeChainAttrs(combined, cs, "e1"), None, "非 merge 节点无值")
    assertEquals(FlowMapStore.mergeChainAttrs(combined, cs, "nope"), None, "查无节点无值")
  }

  test("T3b mergeChainAttrs 门控: 仅 1 条入口可达成员链 ⇒ None（禁「主链 + 1 条」凑够 2）") {
    val nodes = List(
      n("e1", 1), n("m", 2, in = List("e1"), merge = true, status = NodeLifecycle.Wiring)
    )
    val combined = mapOf(nodes*)
    val cs = FlowMapStore.topologicalChains(nodes)
    assertEquals(FlowMapStore.mergeChainAttrs(combined, cs, "m"), None)
    assertEquals(FlowMapStore.mergeChainIds(combined, cs, "m"), None)
  }

  // ── T4 新键的载荷条件序列化 ─────────────────────────────────────────────

  test("T4 buildNodeJson: mergeUpstreamChains 条件键（注入才带；缺省键集零漂移）") {
    val m = n("m", 1, merge = true, status = NodeLifecycle.Wiring)
    val withKey = NodePayload.buildNodeJson(m, 0L,
      chainId = Some("chain-e1"), chainIds = Some(List("chain-e1", "chain-e2")),
      mergeUpstreamChains = Some(List("chain-e1", "chain-e2")))
    val withoutKey = NodePayload.buildNodeJson(m, 0L,
      chainId = Some("chain-e1"), chainIds = Some(List("chain-e1", "chain-e2")))
    assertEquals(withKey.hcursor.downField("mergeUpstreamChains").as[List[String]].toOption,
      Some(List("chain-e1", "chain-e2")))
    assertEquals(withoutKey.asObject.get.keys.toSet, withKey.asObject.get.keys.toSet - "mergeUpstreamChains",
      "未注入 ⇒ 缺键（既有调用方字段集逐字零漂移）")
    val empty = NodePayload.buildNodeJson(m, 0L, mergeUpstreamChains = Some(Nil))
    assert(!empty.asObject.get.keys.toSet.contains("mergeUpstreamChains"), "空列表同样缺键（非命中不带）")
  }
