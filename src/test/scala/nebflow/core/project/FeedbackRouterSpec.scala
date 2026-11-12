package nebflow.core.project

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * FeedbackRouter 单测（Retry-cap 升级路由，spec §2.3 gate G12）：
 * - routeRetryCap 经注入的 failed 通道投递（[Node 'x' failed] 头 + retry 预算读数）；
 * - 审计落 JSONL（escalated，reason=RetryCap）；
 * - 无窗口/cooldown 门（Nebula 每条都要可见——不 Suppress 不合并）。
 *
 * blocked 反馈决策链（重入轮次 / 档位 / 滚动窗口冷却）已随 blocked 终态退役删除；
 * 本 spec 不再覆盖它们。
 */
class FeedbackRouterSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-feedback-router"

  os.remove.all(tempRoot) // 跨 run 清理（审计 JSONL append-only，残留会累积计数）

  private def mkNode(id: String, gen: Int = 3, retryMax: Int = 2): NodeDef =
    NodeDef(
      id = id,
      name = s"节点-$id",
      agent = "test-agent",
      gen = gen,
      retry = Some(RetryPolicy(upstream = "n-up", max = retryMax)),
      createdAt = 0L
    )

  private def mkRouter(
    ws: os.Path,
    escalateFailed: (String, String) => IO[Unit] = (_, _) => IO.unit
  ): FeedbackRouter =
    FeedbackRouter("router-spec-project", ws.toString, escalateFailed)

  private def readAuditEvents(ws: os.Path): IO[List[(String, String)]] = // (type, summary)
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines =>
        lines.flatMap(l =>
          jsonParse(l).toOption.map(j =>
            (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("summary").getOrElse(""))
          )
        )
      )
      .handleError(_ => Nil)

  test("routeRetryCap: delivers via the injected failed channel with [Node 'x' failed] head + budget readout") {
    val ws = tempRoot / "ws-retrycap"
    os.makeDir.all(ws / ".nebflow")
    val delivered = Ref.unsafe[IO, List[(String, String)]](Nil) // (text, nodeName)
    val r = mkRouter(ws, escalateFailed = (text, name) => delivered.update(_ :+ (text -> name)))
    val node = mkNode("n-cap", gen = 3, retryMax = 2)
    for
      _ <- r.routeRetryCap(node, "compile red: Boom.scala:42")
      texts <- delivered.get
    yield
      assertEquals(texts.size, 1, "exactly one escalation is delivered")
      val (text, nodeName) = texts.head
      assertEquals(nodeName, "节点-n-cap")
      assert(text.startsWith("[Node '节点-n-cap' failed]"), s"escalation must carry the failed head, got: $text")
      assert(
        text.contains("回跳重试 2 次") && text.contains("gen=3"),
        s"escalation must carry retry budget + gen, got: $text"
      )
      assert(text.contains("compile red"), s"escalation must carry the last error, got: $text")
      assert(
        text.contains("已停止自动回跳"),
        s"escalation must state that auto-retries stopped, got: $text"
      )
  }

  test("routeRetryCap: no window/cooldown gate — repeated escalations are ALL delivered (never suppressed/merged)") {
    val ws = tempRoot / "ws-retrycap-nogate"
    os.makeDir.all(ws / ".nebflow")
    val delivered = Ref.unsafe[IO, List[(String, String)]](Nil)
    val r = mkRouter(ws, escalateFailed = (text, name) => delivered.update(_ :+ (text -> name)))
    val node = mkNode("n-seq")
    for
      _ <- (1 to 5).toList.traverse(i => r.routeRetryCap(node, s"failure-$i"))
      texts <- delivered.get
      events <- readAuditEvents(ws)
    yield
      assertEquals(texts.size, 5, "every retry-cap exhaustion must be delivered (no suppression, no merge)")
      events.filter((t, _) => t == "escalated").foreach((_, summary) =>
        assert(summary.contains("reason=RetryCap"), s"escalated audit line must carry reason=RetryCap, got: $summary")
      )
      assertEquals(events.count((t, _) => t == "escalated"), 5, "one escalated audit line per escalation")
      assertEquals(events.count((t, _) => t == "cooldown-on"), 0, "no cooldown-on lines — the gate is gone")
  }

  test("routeRetryCap: audit JSONL lines carry ts/project/nodeId/summary") {
    val ws = tempRoot / "ws-retrycap-audit"
    os.makeDir.all(ws / ".nebflow")
    val r = mkRouter(ws)
    for
      _ <- r.routeRetryCap(mkNode("n-a"), "缺外部条件")
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
      assert(
        parsed.exists(_.hcursor.get[String]("type").toOption.contains("escalated")),
        s"escalated line must exist, got: ${parsed.map(_.hcursor.get[String]("type").toOption)}"
      )
    end for
  }

  test("no window/cooldown surface remains: the window constants only anchor DispatchNotify's failed guard") {
    // blocked 决策链删除后，WindowMs/CooldownMs/WindowThreshold 只作为 DispatchNotify
    // failed 守卫的参数锚存在（生产参数关系 10min < 30min 不变）。
    assertEquals(FeedbackRouter.WindowMs, DispatchNotify.FailedWindowMs)
    assertEquals(FeedbackRouter.CooldownMs, DispatchNotify.FailedCooldownMs)
    assertEquals(FeedbackRouter.WindowThreshold, DispatchNotify.FailedWindowThreshold)
  }

end FeedbackRouterSpec
