package nebflow.core.project

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * FeedbackRouter 决策链单测（blocked 反馈重入设计 §2.2–§2.4 + §3）：
 * - 节点级循环上限（§7.2）：blockCount 1/2 → Reenter；3 → Escalate(LoopCap)
 * - 档位（§7.1）：escalate-only → Escalate(Mode)
 * - 项目级频率保护（§7.3）：10min 窗口 ≥5 → cooldown-on 单条合并升级；期内 Suppress；到期恢复
 * - 执行：escalate 经注入通道投递（[Node 'x' blocked] 头 + 全轮次历史）；审计落 JSONL
 *
 * 守卫状态 = 路由器自持 Ref（选型理由见 FeedbackRouter 类注释）；tiny 窗口/cooldown
 * 时长注入验证状态机（真实时长常量另有断言）。
 */
class FeedbackRouterSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-feedback-router"

  os.remove.all(tempRoot) // 跨 run 清理（审计 JSONL append-only，残留会累积计数）

  private def mkNode(id: String, blockCount: Int): NodeDef =
    NodeDef(id = id, name = s"节点-$id", agent = "test-agent", blockCount = blockCount, createdAt = 0L)

  private def mkRouter(
    ws: os.Path,
    mode: String = FeedbackRouter.ModeAuto,
    escalate: (String, String) => IO[Unit] = (_, _) => IO.unit,
    windowMs: Long = FeedbackRouter.WindowMs,
    cooldownMs: Long = FeedbackRouter.CooldownMs
  ): FeedbackRouter =
    FeedbackRouter("router-spec-project", ws.toString, mode, escalate, windowMs, cooldownMs)

  private def readAuditEvents(ws: os.Path): IO[List[(String, String)]] = // (type, nodeId)
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))))
      .handleError(_ => Nil) // 无审计文件的场景（纯 Reenter 路径不落盘）返回空

  // ── 节点级循环上限（§7.2：MaxBlockRoundsPerNode = 2）─────────────

  test("cap: blockCount 1 → Reenter; 2 → Reenter (第二次重入机会); 3 → Escalate(LoopCap)") {
    val r = mkRouter(tempRoot / "ws-cap")
    for
      v1 <- r.decide(mkNode("n-1", 1))
      v2 <- r.decide(mkNode("n-1", 2))
      v3 <- r.decide(mkNode("n-1", 3))
    yield
      assertEquals(v1._1, FeedbackDecision.Reenter: FeedbackDecision, "round 1 must reenter")
      assertEquals(v2._1, FeedbackDecision.Reenter: FeedbackDecision, "round 2 must reenter")
      assertEquals(v3._1, FeedbackDecision.Escalate(FeedbackDecision.EscalateReason.LoopCap): FeedbackDecision,
        "round 3 must escalate (no more reentry)")
    // 常量哨兵：上限 = 2（§7.2 建议值）
    assertEquals(NodeEngine.MaxBlockRoundsPerNode, 2)
  }

  test("cap: escalate-only mode escalates even at round 1 (§7.1 档位 B)") {
    val r = mkRouter(tempRoot / "ws-mode", mode = FeedbackRouter.ModeEscalateOnly)
    r.decide(mkNode("n-1", 1)).map(v =>
      assertEquals(v._1, FeedbackDecision.Escalate(FeedbackDecision.EscalateReason.Mode): FeedbackDecision))
  }

  // ── 项目级频率保护（§7.3：10min ≥5 → 30min cooldown）─────────────

  test("window: 4 blocked → Reenter; 5th → Escalate(CooldownOn); during cooldown → Suppress; expiry → Reenter again") {
    val ws = tempRoot / "ws-window"
    os.makeDir.all(ws / ".nebflow")
    // windowMs < cooldownMs（镜像生产参数关系 10min < 30min——cooldown 期内窗口自然清空）
    val r = mkRouter(ws, windowMs = 150L, cooldownMs = 300L)
    val node = mkNode("n-w", 1)
    for
      verdicts <- (1 to 4).toList.traverse(_ => r.decide(node).map(_._1))
      fifth <- r.decide(node)
      during <- r.decide(node)
      _ <- IO.sleep(400.millis) // cooldown 过期
      after <- r.decide(node)
    yield
      assert(verdicts.forall(v => v == FeedbackDecision.Reenter), s"first 4 must reenter, got $verdicts")
      assertEquals(fifth._1, FeedbackDecision.Escalate(FeedbackDecision.EscalateReason.CooldownOn): FeedbackDecision,
        "5th blocked must trigger cooldown-on escalation")
      assert(fifth._2, "cooldown-on flag must be set on transition")
      assertEquals(during._1, FeedbackDecision.Suppress: FeedbackDecision, "during cooldown must suppress")
      assertEquals(after._1, FeedbackDecision.Reenter: FeedbackDecision, "cooldown expiry must restore auto")
      // 真实时长常量哨兵（§7.3 建议值）
      assertEquals(FeedbackRouter.WindowMs, 10 * 60 * 1000L)
      assertEquals(FeedbackRouter.CooldownMs, 30 * 60 * 1000L)
      assertEquals(FeedbackRouter.WindowThreshold, 5)
  }

  // ── 执行：escalate 通道 + cooldown 合并单条（验收⑤）─────────────

  test("route: loop-cap escalation delivers via injected channel with round count + full history") {
    val ws = tempRoot / "ws-route-cap"
    os.makeDir.all(ws / ".nebflow")
    val delivered = Ref.unsafe[IO, List[(String, String)]](Nil) // (text, nodeName)
    val r = mkRouter(ws, escalate = (text, name) => delivered.update(_ :+ (text -> name)))
    val node = mkNode("n-cap", 3)
    for
      _ <- r.route(node, BlockedFeedback("task-underspecified", "缺交付物定义", "补充验收标准"))
      _ <- r.route(node, BlockedFeedback("agent-mismatch", "能力不匹配", "换 agent"))
      texts <- delivered.get
    yield
      assertEquals(texts.size, 2, "each loop-cap escalation is delivered immediately (§7.4)")
      val (text, nodeName) = texts(1)
      assertEquals(nodeName, "节点-n-cap")
      assert(text.startsWith("[Node '节点-n-cap' blocked]"), s"escalation must carry [Node 'x' blocked] head, got: $text")
      assert(text.contains("第 3 次 blocked") && text.contains("上限 2"), s"loop-cap text must carry round+cap, got: $text")
      assert(text.contains("历史轮次反馈") && text.contains("缺交付物定义") && text.contains("能力不匹配"),
        s"escalation must carry full-round feedback history, got: $text")
  }

  test("route: cooldown merges blocked events into a SINGLE escalation (验收⑤) + suppress silent") {
    val ws = tempRoot / "ws-route-cooldown"
    os.makeDir.all(ws / ".nebflow")
    val delivered = Ref.unsafe[IO, List[(String, String)]](Nil)
    val r = mkRouter(ws, windowMs = 60_000L, cooldownMs = 60_000L, escalate = (text, name) => delivered.update(_ :+ (text -> name)))
    val node = mkNode("n-merge", 1)
    for
      _ <- (1 to 7).toList.traverse(_ => r.route(node, BlockedFeedback("other", "blocked in cooldown", "")))
      texts <- delivered.get
      events <- readAuditEvents(ws)
    yield
      assertEquals(texts.size, 1, "N blocked during cooldown must merge into exactly ONE escalation")
      val (text, _) = texts.head
      assert(text.contains("冷却") && text.contains("不再自动重入"), s"cooldown-on message must announce cooldown, got: $text")
      assert(events.count((t, _) => t == "cooldown-on") == 1, "exactly one cooldown-on audit line")
      assert(events.count((t, _) => t == "escalated") == 1, "exactly one escalated audit line (merged)")
  }

  test("route: reentry decision with unmounted project degrades gracefully (warn, no crash)") {
    val ws = tempRoot / "ws-route-reenter"
    os.makeDir.all(ws / ".nebflow")
    val delivered = Ref.unsafe[IO, List[(String, String)]](Nil)
    val r = mkRouter(ws, escalate = (text, name) => delivered.update(_ :+ (text -> name)))
    for
      _ <- r.route(mkNode("n-r", 1), BlockedFeedback("needs-split", "任务应拆分", "拆为 A+B"))
      texts <- delivered.get
      events <- readAuditEvents(ws)
    yield
      assertEquals(texts.size, 0, "no escalation for reentry path")
      assert(!events.exists((t, _) => t == "escalated"), "no escalated audit for reentry path")
  }

  test("route: audit JSONL carries escalated/cooldown-on lines with ts/project/nodeId/summary") {
    val ws = tempRoot / "ws-route-audit"
    os.makeDir.all(ws / ".nebflow")
    val r = mkRouter(ws, mode = FeedbackRouter.ModeEscalateOnly)
    for
      _ <- r.route(mkNode("n-a", 1), BlockedFeedback("external-dependency", "缺外部条件", "需提供 API key"))
      raw <- IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      lines = raw.linesIterator.toList.filter(_.trim.nonEmpty)
      parsed = lines.flatMap(l => jsonParse(l).toOption)
    yield
      assert(parsed.nonEmpty, "audit file must contain JSON lines")
      parsed.foreach { j =>
        assert(j.hcursor.get[Long]("ts").isRight, s"line must carry ts: $j")
        assert(j.hcursor.get[String]("type").isRight, s"line must carry type: $j")
        assertEquals(j.hcursor.get[String]("project").toOption, Some("router-spec-project"))
        assert(j.hcursor.get[String]("nodeId").isRight, s"line must carry nodeId: $j")
        assert(j.hcursor.get[String]("summary").isRight, s"line must carry summary: $j")
      }
      assert(parsed.exists(_.hcursor.get[String]("type").toOption.contains("escalated")),
        s"escalated line must exist, got: ${parsed.map(_.hcursor.get[String]("type").toOption)}")
  }

end FeedbackRouterSpec
