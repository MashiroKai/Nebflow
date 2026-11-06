package nebflow.llm

import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.*

/**
 * 可归因观测契约（cache-warmup 批 2026-11-06，作者补充令 §三）。
 *
 * 契约：
 *   C-OBS-1 总率口径 = sum(cacheRead)/sum(input)，**不是**逐请求百分比平均；
 *   C-OBS-2 缺失/未知缓存读数：usage=None ⇒ UsageMissing 单列，不进分母、
 *      不假装命中或免费；零命中如实进归因；
 *   C-OBS-3 warmup 类账目与业务类分列——保温次数/成本永不混入业务率；
 *   C-OBS-4 归因类别受控样本：NewSession / ConfigChanged（tools 轴）/ 
 *      HistoryChanged（首个差异块位）/ CompactionRebuild（前部重建签名）/
 *      EphemeralMarked（断点不在持久尾——旧装配缺陷签名）/ DomainSwitched /
 *      Unknown（客户端未观测到变化 ⇒ 供应商侧，不谎称驱逐）；
 *   C-OBS-5 有界性：miss 环封顶；摘要无正文（机密样文本不落任何记录字段）；
 *   C-OBS-6 混合长短会话：整体与单会话拆分可对账（sum 守恒）。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.llm.CacheAttributionSpec"`
 * 归属 CI：既有 `sbt test` 家族。
 */
class CacheAttributionSpec extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = CacheAttribution.resetForTest()

  // ── fixtures ──────────────────────────────────────────────────────────

  private def wireMsg(role: String, text: String, marker: Boolean = false): Json =
    val block =
      if marker then Json.obj("type" -> "text".asJson, "text" -> text.asJson, "cache_control" -> Json.obj("type" -> "ephemeral".asJson))
      else Json.obj("type" -> "text".asJson, "text" -> text.asJson)
    Json.obj("role" -> role.asJson, "content" -> Json.arr(block))

  /** 持久历史 wire：user → assistant(带尾断点)。 */
  private def persistedWire(turns: Int, frontText: String = "question", markTail: Boolean = true): List[Json] =
    val msgs = scala.collection.mutable.ListBuffer.empty[Json]
    (1 to turns).foreach { i =>
      msgs += wireMsg("user", s"$frontText-$i")
      val isTail = i == turns
      msgs += wireMsg("assistant", s"answer-$i", marker = markTail && isTail)
    }
    msgs.toList

  private def clockWire(epoch: Long): List[Json] = List(wireMsg("user", s"clock $epoch"))

  private def paramsOf(
    sid: String,
    tools: Option[List[ToolDefinition]] = Some(List(ToolDefinition("Read", "read", JsonObject.empty))),
    stable: Option[String] = Some("STABLE")
  ): SendMessageParams =
    SendMessageParams(
      messages = Nil,
      model = "glm-5.3-flash",
      tools = tools,
      systemStable = stable,
      sessionId = Some(sid),
      agentId = Some("test")
    )

  private val hit  = TokenUsage(inputTokens = 1000, outputTokens = 10, cacheReadTokens = Some(900)) // 90% 正常带
  private val miss = TokenUsage(inputTokens = 1000, outputTokens = 10, cacheReadTokens = Some(0)) // 0% 归因带

  // ── C-OBS-1：总率口径 ─────────────────────────────────────────────────

  test("⑦ total rate = sum(read)/sum(input), never per-request average"):
    CacheAttribution.observeUsage("s", "Zai", "glm", Some(TokenUsage(1000, 0, Some(0)))) // 0%
    CacheAttribution.observeUsage("s", "Zai", "glm", Some(TokenUsage(10, 0, Some(10)))) // 100%
    val snap = CacheAttribution.snapshot
    // 逐请求平均 = 50%；总量口径 = 10/1010 ≈ 0.99%（短会话不得稀释长会话的读数）
    assertEquals(snap.business.hitPct, 10.0 * 100.0 / 1010.0, 1e-9)

  // ── C-OBS-2：缺失 usage ───────────────────────────────────────────────

  test("⑦ missing usage: UsageMissing recorded, never enters the denominator"):
    val rec = CacheAttribution.observeUsage("s", "Zai", "glm", None)
    assertEquals(rec.map(_.category.name), Some("usage-missing"))
    val snap = CacheAttribution.snapshot
    assertEquals(snap.business.requests, 0L, "no fabricated accounting from missing usage")
    assertEquals(snap.business.inputTokens, 0L)
    assertEquals(snap.business.cacheRead, 0L)
    assertEquals(snap.missCounts("usage-missing"), 1)

  // ── C-OBS-3：warmup 分列 ──────────────────────────────────────────────

  test("⑦ warmup ledger stays out of the business rate"):
    CacheAttribution.observeUsage("s", "Zai", "glm", Some(TokenUsage(5000, 100, Some(0))), kind = "warmup")
    CacheAttribution.observeUsage("s", "Zai", "glm", Some(hit))
    val snap = CacheAttribution.snapshot
    assertEquals(snap.warmup.requests, 1L, "warmup counted separately")
    assertEquals(snap.warmup.cacheRead, 0L, "warmup zero-hit visible in its own ledger")
    assertEquals(snap.business.requests, 1L, "warmup must not inflate business request count")
    assertEquals(snap.business.inputTokens, 1000L, "warmup tokens must not enter business denominator")

  // ── C-OBS-6：混合会话对账 ─────────────────────────────────────────────

  test("⑦ mixed long/short sessions reconcile: total = sum of per-domain splits"):
    CacheAttribution.observeUsage("long", "Zai", "glm", Some(TokenUsage(900000, 10, Some(890000))))
    CacheAttribution.observeUsage("short", "Zai", "glm", Some(TokenUsage(1000, 5, Some(100))))
    val snap = CacheAttribution.snapshot
    assertEquals(snap.business.inputTokens, 901000L)
    assertEquals(snap.business.cacheRead, 890100L)
    assertEquals(snap.perDomain("Zai/glm").inputTokens, 901000L, "single-domain split matches total")

  // ── C-OBS-4：受控归因样本 ─────────────────────────────────────────────

  test("⑧ new session with zero hit → new-session (not claimed as eviction)"):
    CacheAttribution.observeWire("anthropic", paramsOf("s1"), "Zai", persistedWire(1), clockWire(1))
    val rec = CacheAttribution.observeUsage("s1", "Zai", "glm-5.3-flash", Some(miss))
    assertEquals(rec.map(_.category.name), Some("new-session"))

  test("⑧ healthy appending turns produce NO attribution record"):
    CacheAttribution.observeWire("anthropic", paramsOf("s2"), "Zai", persistedWire(1), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s2", "Zai", "glm-5.3-flash", Some(hit)), None)
    CacheAttribution.observeWire("anthropic", paramsOf("s2"), "Zai", persistedWire(2), clockWire(2))
    assertEquals(CacheAttribution.observeUsage("s2", "Zai", "glm-5.3-flash", Some(hit)), None)
    assertEquals(CacheAttribution.snapshot.missCounts, Map.empty)

  test("⑧ tools fingerprint change → config-changed"):
    CacheAttribution.observeWire("anthropic", paramsOf("s3"), "Zai", persistedWire(1), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s3", "Zai", "glm-5.3-flash", Some(hit)), None)
    val newTools = Some(List(ToolDefinition("Read", "read", JsonObject.empty), ToolDefinition("Edit", "edit", JsonObject.empty)))
    CacheAttribution.observeWire("anthropic", paramsOf("s3", tools = newTools), "Zai", persistedWire(1, markTail = true), clockWire(2))
    val rec = CacheAttribution.observeUsage("s3", "Zai", "glm-5.3-flash", Some(miss))
    assertEquals(rec.map(_.category.name), Some("config-changed"))
    assertEquals(rec.flatMap(_.firstDiffIdx), None)

  test("⑧ front content rewrite → history-changed with firstDiffIdx=0 (equal length)"):
    CacheAttribution.observeWire("anthropic", paramsOf("s4"), "Zai", persistedWire(2, frontText = "v1"), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s4", "Zai", "glm-5.3-flash", Some(hit)), None)
    // 等长前部改写（TTL 占位符类生命周期边界）
    CacheAttribution.observeWire("anthropic", paramsOf("s4"), "Zai", persistedWire(2, frontText = "v2"), clockWire(2))
    val rec = CacheAttribution.observeUsage("s4", "Zai", "glm-5.3-flash", Some(miss))
    assertEquals(rec.map(_.category.name), Some("history-changed"))
    assertEquals(rec.flatMap(_.firstDiffIdx), Some(0))

  test("⑧ front rebuilt + shrunk → compaction-rebuild"):
    CacheAttribution.observeWire("anthropic", paramsOf("s5"), "Zai", persistedWire(4), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s5", "Zai", "glm-5.3-flash", Some(hit)), None)
    CacheAttribution.observeWire("anthropic", paramsOf("s5"), "Zai", persistedWire(1, frontText = "summary"), clockWire(2))
    val rec = CacheAttribution.observeUsage("s5", "Zai", "glm-5.3-flash", Some(miss))
    assertEquals(rec.map(_.category.name), Some("compaction-rebuild"))

  test("⑧ marker leaked into the ephemeral section → ephemeral-marked (old-assembly defect signature)"):
    // 正常持久历史（断点在尾块）+ 旧装配形态：提醒块带 marker 漏进临时段
    CacheAttribution.observeWire("anthropic", paramsOf("s6"), "Zai", persistedWire(1), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s6", "Zai", "glm-5.3-flash", Some(hit)), None)
    CacheAttribution.observeWire("anthropic", paramsOf("s6"), "Zai", persistedWire(1),
      clockWire(2) :+ wireMsg("user", "EPHEMERAL-REMINDER", marker = true))
    val rec2 = CacheAttribution.observeUsage("s6", "Zai", "glm-5.3-flash", Some(miss))
    assertEquals(rec2.map(_.category.name), Some("ephemeral-marked"))
    assert(rec2.get.detail.contains("ephemeralMarked=true"))

  test("⑧ pure append + fingerprints identical + zero hit → unknown (provider-side, not claimed as eviction)"):
    CacheAttribution.observeWire("anthropic", paramsOf("s7"), "Zai", persistedWire(1), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s7", "Zai", "glm-5.3-flash", Some(hit)), None)
    CacheAttribution.observeWire("anthropic", paramsOf("s7"), "Zai", persistedWire(2), clockWire(2))
    val rec = CacheAttribution.observeUsage("s7", "Zai", "glm-5.3-flash", Some(TokenUsage(5000, 10, Some(0))))
    assertEquals(rec.map(_.category.name), Some("unknown"))
    assert(rec.get.detail.contains("not claimed as eviction"))

  test("⑧ same session switching provider domain → domain-switched"):
    CacheAttribution.observeWire("anthropic", paramsOf("s8"), "Zai", persistedWire(1), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s8", "Zai", "glm-5.3-flash", Some(hit)), None)
    CacheAttribution.observeWire("anthropic", paramsOf("s8"), "Deepseek", persistedWire(1), clockWire(2))
    val rec = CacheAttribution.observeUsage("s8", "Deepseek", "glm-5.3-flash", Some(miss))
    assertEquals(rec.map(_.category.name), Some("domain-switched"))

  // ── C-OBS-5：有界性 + 无正文 ─────────────────────────────────────────

  test("⑧ miss ring is bounded (512) and no message body ever lands in records"):
    val secret = "SECRET-BODY-do-not-leak-0xdeadbeef"
    val msgs   = List(wireMsg("user", secret, marker = false), wireMsg("assistant", "ok", marker = true))
    (1 to 520).foreach { i =>
      CacheAttribution.observeWire("anthropic", paramsOf(s"flood$i"), "Zai", msgs, clockWire(i.toLong))
      CacheAttribution.observeUsage(s"flood$i", "Zai", "glm-5.3-flash", Some(miss), nowMs = i.toLong)
    }
    val snap = CacheAttribution.snapshot
    assertEquals(snap.recentMisses.length, 512, "miss ring must be capped")
    assert(!snap.toString.contains(secret), "fingerprint/records must never contain message bodies")
    assert(!snap.missCounts.keys.mkString(",").contains(secret), "counts keyed by category name only")

  test("⑧ cache_control metadata movement is fingerprint-neutral (marker ≠ semantic change)"):
    CacheAttribution.observeWire("anthropic", paramsOf("s9"), "Zai", persistedWire(1, markTail = true), clockWire(1))
    assertEquals(CacheAttribution.observeUsage("s9", "Zai", "glm-5.3-flash", Some(hit)), None)
    // 同内容但 marker 挪位（滑动断点的中间形态）⇒ 指纹不变 ⇒ 不产生 config/history 归因
    CacheAttribution.observeWire("anthropic", paramsOf("s9"), "Zai", persistedWire(1, markTail = false), clockWire(2))
    CacheAttribution.observeUsage("s9", "Zai", "glm-5.3-flash", Some(TokenUsage(600, 5, Some(0)))) // miss，但历史指纹全同
    // 若 marker 参与指纹，这里会误报 ephemeral-marked（marker 从尾块消失 = 尾块指纹变）
    val names = CacheAttribution.snapshot.recentMisses.map(_.category.name)
    assert(!names.contains("history-changed"), "marker metadata movement must not be classified as history change")

end CacheAttributionSpec
