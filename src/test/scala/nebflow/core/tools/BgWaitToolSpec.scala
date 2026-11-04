package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{BashResilienceConfig, Defaults}

import scala.concurrent.duration.*

/**
 * bash overhaul T1 红相（作者令 2026-11-04「组织优化 bash，用业界成熟做法完整
 * 实现，一步到位」）：**等待原语 `BgWait` 工具必须存在**且语义正确。
 *
 * 事实源：`docs/20261104_110500_our-bash-tool-defects.md` 缺陷 D3（提示词禁止
 * 轮询、工具回执教轮询、**没有任何等待原语**——turn 内合法的等待手段为零）；
 * 调研件 §0-1「有界等待（bounded wait）」业界共识 + §5.2 形态 (b)：
 * `BgWait(jobIds, deadlineMs)`——语义 = **至多一次 LLM 往返**；预算内等到就当场
 * 返回完成块（含退出码与尾段）；到 deadline 仍未完 = 返回「仍在跑」、**不杀**
 * job；阻塞上限钳到前台预算（同源 `ctx.bashConfig.foregroundWaitBudgetMs`，
 * **不许第二个上限**）。
 *
 * 全部断言在缺省类路径下即要求 `BgWaitTool` 符号存在——旧代码没有该工具 ⇒
 * **本文件整体编译红**（预期红：`sbt Test/compile` 失败 = 红证据本体）。
 */
class BgWaitToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private def budgetCtx(budgetMs: Long): ToolContext = ToolContext(
    projectRoot = "/tmp",
    bashConfig = BashResilienceConfig(foregroundWaitBudgetMs = budgetMs)
  )

  private def schemaField(name: String): Boolean =
    BgWaitTool.inputSchema("properties").exists(_.asObject.exists(_.contains(name)))

  private def startBg(cmd: String): IO[String] =
    BashTool
      .call(
        JsonObject(
          "command" -> cmd.asJson,
          "run_in_background" -> true.asJson,
          "description" -> "bgwait-t1-source-task".asJson
        ),
        ToolContext(projectRoot = "/tmp")
      )
      .map {
        case Right(out) =>
          """Job ID: (\w+)""".r.findFirstMatchIn(out).map(_.group(1)).getOrElse(fail(s"no job id: $out"))
        case Left(e) => fail(s"background start failed: ${e.message}")
      }

  test("T1: the wait primitive exists — name is BgWait, schema carries jobIds(required) + deadline") {
    IO {
      assertEquals(BgWaitTool.name, "BgWait", "the tool must be registered under the name BgWait")
      assert(schemaField("jobIds"), "schema must expose jobIds")
      assert(schemaField("deadline"), "schema must expose deadline")
      // 红相写法缺陷修正（green 相，2026-11-04）：`_.map(_.asString)` 得
      // `List[Option[String]]`，`contains("jobIds")` 在结构上恒为 false（`contains`
      // 取 `Any` 超类型 ⇒ 编译通过、永远不命中，任何合法 schema 都无法满足）。
      // `flatten` 后比较是**同义更强**的断言（真的检查 required 里有没有这个键）。
      val required =
        BgWaitTool.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Vector.empty)
      assert(required.contains("jobIds"), s"jobIds must be required, required=$required")
    }
  }

  test("T1: BgWait(jobIds=[id], deadline=10000) on a ~5s task returns the completed block with exit code + tail") {
    (for
      jobId <- startBg("sleep 5 && echo bgwait-done-payload")
      result <- BgWaitTool.call(
        JsonObject("jobIds" -> List(jobId).asJson, "deadline" -> 10000.asJson),
        ToolContext(projectRoot = "/tmp")
      )
      out <- IO {
        result match
          case Right(o) => o
          case Left(e) => fail(s"BgWait must not fail on a completing job: ${e.message}")
      }
      _ <- BgTaskRegistry.unregister(jobId).attempt.void
    yield
      assert(out.contains("[completed]"), s"the bounded wait must return the completed block: ${out.take(400)}")
      assert(out.contains(jobId), s"the completed block must name the job: ${out.take(400)}")
      assert(out.contains("exit"), s"the completed block must carry the exit code: ${out.take(400)}")
      assert(
        out.contains("bgwait-done-payload"),
        s"the completed block must carry the output tail: ${out.take(400)}"
      )
    )
  }

  test("T1: deadline hit on a still-running job returns [still running] and does NOT kill the job") {
    (for
      jobId <- startBg("sleep 5 && echo bgwait-still-running")
      result <- BgWaitTool.call(
        JsonObject("jobIds" -> List(jobId).asJson, "deadline" -> 300.asJson),
        ToolContext(projectRoot = "/tmp")
      )
      out <- IO {
        result match
          case Right(o) => o
          case Left(e) => fail(s"a deadline hit must be a normal receipt, not a tool error: ${e.message}")
      }
      row <- BgTaskRegistry.find(jobId)
      _ <- BashTool
        .call(JsonObject("background_job_id" -> jobId.asJson, "cancel_background_job" -> true.asJson), ToolContext(projectRoot = "/tmp"))
        .attempt
      _ <- BgTaskRegistry.unregister(jobId).attempt.void
    yield
      assert(out.contains("[still running]"), s"deadline hit must say the job is still running: ${out.take(400)}")
      assert(
        row.isDefined,
        s"the deadline must NOT kill the job — the registry row must survive: jobId=$jobId"
      )
    )
  }

  test(
    "T1: a huge deadline is clamped to the same foreground budget — BgWait must return within the ctx budget (single cap, no second ceiling)"
  ) {
    val budgetMs = 500L
    (for
      jobId <- startBg("sleep 6 && echo bgwait-clamp")
      start <- IO(System.currentTimeMillis())
      result <- BgWaitTool.call(
        JsonObject("jobIds" -> List(jobId).asJson, "deadline" -> 10000000.asJson),
        budgetCtx(budgetMs)
      )
      elapsed <- IO(System.currentTimeMillis() - start)
      _ <- BashTool
        .call(JsonObject("background_job_id" -> jobId.asJson, "cancel_background_job" -> true.asJson), ToolContext(projectRoot = "/tmp"))
        .attempt
      _ <- BgTaskRegistry.unregister(jobId).attempt.void
    yield
      // 「至多一次往返」：阻塞上限 = 前台预算（500ms），不是 deadline（2.8h）。
      assert(
        elapsed < budgetMs + 2000,
        s"BgWait must clamp to the ctx foreground budget (~${budgetMs}ms), took ${elapsed}ms"
      )
      result match
        case Right(out) => assert(out.contains("[still running]"), s"budget-hit receipt face: ${out.take(300)}")
        case Left(e) =>
          assert(
            e.message.contains("still running") || e.message.contains("budget"),
            s"clamped return must be the still-running face, got: ${e.message.take(300)}"
          )
    )
  }

  test("T1: the wait primitive rides the LoopGuard Wait class — repeated waits never feed the R-call counter") {
    // 防循环契约（§5.2-B2 的机制腿）：同参 BgWait 20 次 ⇒ Pass/Warn，永不 Terminate。
    // 判据单点 = CallKind.classify（查询类/等待类不计 R-call）。
    IO {
      val ev = nebflow.core.processor.LoopGuard.RoundEvent(
        "BgWait",
        JsonObject("jobIds" -> io.circe.Json.arr("abc".asJson), "deadline" -> 5000.asJson).asJson,
        isError = false,
        errorText = "",
        permissionDenied = false
      )
      val (_, v) = (1 to 20).foldLeft(
        (nebflow.shared.Counters.Empty, nebflow.core.processor.LoopGuard.Verdict.Pass: nebflow.core.processor.LoopGuard.Verdict)
      ) { case ((cnt, _), _) =>
        nebflow.core.processor.LoopGuard.evaluate(List(ev), "t1", cnt, nebflow.core.processor.LoopGuard.Config.Default)
      }
      v match
        case t: nebflow.core.processor.LoopGuard.Verdict.Terminate =>
          fail(s"repeated bounded waits must never Terminate: ${t.msg}")
        case f: nebflow.core.processor.LoopGuard.Verdict.Freeze =>
          fail(s"repeated bounded waits must never Freeze: ${f.msg}")
        case _ => ()
    }
  }

end BgWaitToolSpec
