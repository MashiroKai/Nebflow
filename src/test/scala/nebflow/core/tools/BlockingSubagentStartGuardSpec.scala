package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 子代理等待三缺口批（2026-10-25）缺口① 验收 spec：**子代理活性判定窗**。
 *
 * 990bd848 卡死链实证面：`BlockingSubagent` spawn 后对子会话零事件帧的形态
 * （子会话首个 LLM 请求 provider-hang 永不出声）原本只能干等 15min 硬顶。
 * 本批加两段等待（实现单点 = [[BlockingSubagent.awaitResult]]，spec 判红锚）：
 * 预算窗（`nebflow.subagent.startBudgetMs`，默认 120s）内零事件帧 = 判子死 →
 * 结构化失败 `subagent-never-started` 立即返回，父 agent 自行重试/绕过。
 * 哲学对齐 #692：有活性事实才继续等，等不到事实是「对端死亡判定」，不是超时杀。
 *
 * 四条读数：
 *  - 判死腿：零事件帧 + 真实计时——返回 Left 且带 token，耗时 ≥ 预算窗
 *    （真等了）且远小于 15min 硬顶（没干等）。
 *  - 活性腿：第一帧到场 ⇒ 完成段与改造前逐字（Right(finalText)）。
 *  - 活性间谍：[[BlockingSubagent.startSpy]] 任何首帧（agentStart 领衔）即落标记。
 *  - 旋钮：默认 120s / prop 现读可配。
 *
 * 端到端回归（真实子会话出帧 → 桥完成）由 [[BlockingSubagentEndToEndSpec]]
 * 承重——若活性桥接线断裂（首帧不落标记），判死腿将在预算窗处误开火，
 * 该 spec 的用例即转红。
 */
class BlockingSubagentStartGuardSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val budgetProp = "nebflow.subagent.startBudgetMs"

  /** prop 现读旋钮的测试臂（用后还原自己写过的值）。 */
  private def withBudgetMs[A](ms: String)(io: IO[A]): IO[A] =
    IO(System.setProperty(budgetProp, ms)).void *>
      io.guarantee(IO(System.clearProperty(budgetProp)).void)

  // ── ① 判死腿：零事件帧 + 真实计时 ──────────────────────────────────────────

  test("缺口①: 零事件帧 ⇒ 预算窗内判死返回 subagent-never-started（真实计时：≥预算、≪15min 硬顶）"):
    withBudgetMs("400") {
      for
        started <- Deferred[IO, Unit] // 子会话永不出声（永不 complete = 零事件帧）
        done <- Deferred[IO, Option[String]]
        _ <- done.complete(Some("LATE-RESULT-NEVER-CONSUMED")) // 完成段不应被到达
        stops <- IO.ref[List[String]](Nil)
        panels <- IO.ref[Int](0)
        t0 <- IO.monotonic
        result <- BlockingSubagent.awaitResult(
          started = started,
          done = done,
          childId = "subagent-guard01",
          stopChild = reason => stops.update(_ :+ reason),
          emitPanelDone = panels.update(_ + 1)
        )
        elapsed <- IO.monotonic.map(_ - t0)
        stopReasons <- stops.get
        panelFrames <- panels.get
      yield
        assert(result.isLeft, s"zero-event child must be judged dead, got $result")
        val err = result.left.toOption.map(_.message).getOrElse("")
        assert(
          err.contains(BlockingSubagent.NeverStartedToken),
          s"structured failure must carry the token '${BlockingSubagent.NeverStartedToken}': $err"
        )
        assert(err.contains("subagent-guard01"), s"failure must name the child session: $err")
        assert(elapsed >= 350.millis, s"the guard must REALLY wait the budget window (real timing), elapsed=$elapsed")
        assert(elapsed < 10.seconds, s"the guard must NOT wait the 15min hard cap, elapsed=$elapsed")
        assertEquals(stopReasons, List(BlockingSubagent.NeverStartedToken), s"judged-dead leg must stop the child, got $stopReasons")
        assertEquals(panelFrames, 1, "judged-dead leg must emit the panel terminal frame")
    }

  // ── ② 活性腿：首帧到场 ⇒ 完成段与改造前逐字 ────────────────────────────────

  test("缺口①: 首帧已落标记 ⇒ 完成桥照常交付 finalText（改造前行为零漂移）"):
    for
      started <- Deferred[IO, Unit]
      done <- Deferred[IO, Option[String]]
      stops <- IO.ref[List[String]](Nil)
      fiber <- BlockingSubagent
        .awaitResult(
          started = started,
          done = done,
          childId = "subagent-guard02",
          stopChild = reason => stops.update(_ :+ reason),
          emitPanelDone = IO.unit
        )
        .start
      _ <- started.complete(()) // agentStart 领衔的首帧
      _ <- done.complete(Some("  FINAL-TEXT  "))
      result <- fiber.joinWithNever
      stopsAfter <- stops.get
    yield
      assertEquals(result, Right("FINAL-TEXT"), "completion leg must deliver the trimmed final text")
      assertEquals(stopsAfter, Nil, "the completion leg must not stop the child")

  test("缺口①: 活性到场但完成帧无文本 ⇒ 「ended without a final text」（回归钉）"):
    for
      started <- Deferred[IO, Unit]
      done <- Deferred[IO, Option[String]]
      _ <- started.complete(())
      _ <- done.complete(None)
      result <- BlockingSubagent.awaitResult(
        started = started,
        done = done,
        childId = "subagent-guard03",
        stopChild = _ => IO.unit,
        emitPanelDone = IO.unit
      )
    yield assert(
      result.left.toOption.exists(_.message.contains("ended without a final text")),
      s"empty final text must keep the pre-existing failure face, got $result"
    )

  // ── ③ 活性间谍：任何首帧（agentStart 领衔）即落标记 ─────────────────────────

  test("缺口①: startSpy 首帧（agentStart）即落活性标记——零新协议，同一间谍链"):
    for
      started <- Deferred[IO, Unit]
      forwarded <- IO.ref[List[Json]](Nil)
      spy = BlockingSubagent.startSpy(started, json => forwarded.update(_ :+ json))
      _ <- spy(Json.obj("type" -> "agentStart".asJson, "agentId" -> "subagent-guard04".asJson))
      marked <- started.get.timeout(2.seconds).attempt
      fwd <- forwarded.get
      // 完成帧同样先落活性（天然蕴含）——二次标记幂等不炸
      _ <- spy(Json.obj("type" -> "agentDone".asJson))
      _ <- started.complete(()) // 幂等性经由 attempt 吞 AlreadyCompletedException
    yield
      assertEquals(marked.isRight, true, s"the first frame must mark liveness, got $marked")
      assertEquals(fwd.map(_.hcursor.get[String]("type").toOption), List(Some("agentStart")), "the spy must forward the frame to the routed base")

  // ── ④ 旋钮：默认 120s / prop 现读可配 ──────────────────────────────────────

  test("缺口①: 预算窗旋钮——默认 120000ms，prop 现读（kill-switch 先例）"):
    withBudgetMs("250") {
      for
        _ <- IO(assertEquals(BlockingSubagent.startBudget, 250.millis, "the knob must be read per-call from the system prop"))
        _ <- IO(System.clearProperty(budgetProp))
        v <- IO(BlockingSubagent.startBudget)
      yield assertEquals(v, 120000.millis, "cleared prop ⇒ default 120s")
    }

end BlockingSubagentStartGuardSpec
