package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * main-agent-nonblocking 批（2026-10-21 作者裁定重申「主 agent 一点不能阻塞」：
 * 之前的语义 = 不杀但自动转后台）。
 *
 * 背景（当日两次实撞）：前台克隆 1GB+ 大仓无输出运行，旧缺省前台预算 =
 * BashMaxTimeoutMs（60min）从未到点，600s 无输出停滞阈值
 * （ForegroundNoProgressTimeoutMs → shell.foregroundNoProgressWatch）先触发
 * **杀**树，主会话每次硬冻结 10 分钟。
 *
 * 裁定语义（本 spec 逐条验收）：
 *  - N-1 取值面：前台预算 = Defaults.ForegroundWaitBudgetMs（默认 120s，
 *    作者裁定「默认建议 60-120s 量级，可调」），三档配置面等价
 *    （nebflow.json `bashForegroundWaitBudgetMs` / system prop /
 *    BashResilienceConfig 注入）；BashMaxTimeoutMs（60min）退为入参钳 +
 *    remote-exec 面，缺省值不变。
 *  - N-2 预算判据 = **时间**，与输出活性无关：静默命令（克隆形态：零输出零
 *    CPU）与**持续吐输出**的命令同样在预算到点转后台。
 *  - N-3 今日案例回归：无输出长命令在预算到点（2s）转后台、**不杀**、完成
 *    照旧经查询面投递——旧面对照（不注入预算）证明同形命令在压缩停滞阈值下
 *    被 ceiling 杀（"[Command stalled]"，即事故路径）。
 *  - N-4 转后台回执契约：[Background job started] + Job ID + Auto-backgrounded
 *    说明 + **如何查询**（background_job_id / cancel_background_job）。
 *  - N-5 收编即退役：转后台后的任务不再被前台 no-progress ceiling 杀
 *    （JobHealth.adoptedAsBackground → watch 下一窗退出）。
 *  - N-6 显式 `timeout` 入参被配置预算钳制（min）——agent 不能越过作者的
 *    零阻塞裁定。
 *  - N-7 引擎既有命令族回归：短命令（echo）在预算内照常前台返回真实输出。
 */
class BashForegroundBudgetSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private val BudgetProp = "nebflow.bash.foregroundWaitBudgetMs"
  private val CeilingProp = "nebflow.shell.foregroundNoProgressTimeoutMs"
  private val SampleProp = "nebflow.shell.foregroundSampleIntervalMs"

  /** Set props for the duration of `io`, always cleared after (A-5 precedent). */
  private def withProps(props: (String, String)*)(io: IO[Unit]): IO[Unit] =
    IO(props.foreach((k, v) => System.setProperty(k, v)))
      .flatMap(_ => io)
      .guarantee(IO(props.foreach((k, _) => System.clearProperty(k))))

  private def jobIdOf(receipt: String): String =
    """Job ID: (\w+)""".r.findFirstMatchIn(receipt).map(_.group(1)).getOrElse(fail(s"no job id in receipt: $receipt"))

  /** Query a job through the tool face (same face the model uses). */
  private def query(jobId: String): IO[String] =
    BashTool.call(JsonObject("background_job_id" -> jobId.asJson), ToolContext(projectRoot = "/tmp")).map {
      case Right(out) => out
      case Left(e) => fail(s"background query failed: ${e.message}")
    }

  /** Poll the query face until `want` appears or the deadline passes. */
  private def pollUntil(jobId: String, want: String, deadline: FiniteDuration): IO[String] =
    def loop: IO[String] =
      query(jobId).flatMap { out =>
        if out.contains(want) then IO.pure(out) else IO.sleep(400.millis) *> loop
      }
    loop.timeout(deadline)

  /** 2s 预算的会话上下文（配置注入腿 = nebflow.json 键的同一落点）。 */
  private val budget2sCtx = ToolContext(
    projectRoot = "/tmp",
    bashConfig = nebflow.shared.BashResilienceConfig(foregroundWaitBudgetMs = 2000)
  )

  test("N-1 default face: budget default = 120s, prop-compressible, config default carries it; MAX_TIMEOUT unchanged") {
    withProps(BudgetProp -> "12345") {
      IO(assert(nebflow.shared.Defaults.ForegroundWaitBudgetMs == 12345L, "prop must be read per call"))
    } *> IO {
      // 缺省面（prop 已清）：120s（作者裁定「默认建议 60-120s 量级」）。
      assert(nebflow.shared.Defaults.ForegroundWaitBudgetMs == 120000L, "default must be 120s")
      // 配置面缺省 = Defaults 值（GatewayMain 键注入的落点）。
      assert(
        nebflow.shared.BashResilienceConfig().foregroundWaitBudgetMs == 120000L,
        "BashResilienceConfig default must carry the budget"
      )
      // MAX_TIMEOUT（入参钳 + remote-exec 面）缺省不变：60min。
      assert(BashTool.MAX_TIMEOUT == 3600000L, "MAX_TIMEOUT (parse clamp / remote face) must stay 60min")
    }
  }

  test(
    "N-2+N-4 today's clone regression: SILENT no-output command auto-backgrounds at the 2s budget (not stall-killed), receipt carries job id + query hint, result delivered on completion"
  ) {
    // 克隆形态：运行期零输出零 CPU（python sleep = poll 等待，进程字符串**不含**
    // `sleep <数字>`，不享受 SleepCommandRe 的 ceiling 豁免——真刀真枪过 ceiling 面；
    // 子进程 stdin=/dev/null，故不用 `read -t`——它会瞬间 EOF）。
    val input = JsonObject(
      "command" -> """python3 -c "import time; time.sleep(8); print('clone-done')"""".asJson,
      "description" -> "silent-clone-shape".asJson
    )
    val start = System.currentTimeMillis()
    (for
      result <- BashTool.call(input, budget2sCtx)
      receipt <- IO {
        val elapsed = System.currentTimeMillis() - start
        result match
          case Right(out) =>
            // 回执契约（裁定原文「已转后台+job 标识+如何查询」）：
            assert(out.contains("[Background job started]"), s"receipt head: $out")
            assert(out.contains("Job ID:"), s"job id: $out")
            assert(out.contains("Auto-backgrounded"), s"backgrounded note: $out")
            assert(out.contains("NOT killed"), s"no-kill note: $out")
            assert(out.contains("background_job_id"), s"query hint: $out")
            assert(elapsed < 8000, s"tool must return AT the budget (~2s), took ${elapsed}ms")
            out
          case Left(e) => fail(s"budget must NOT kill, got error: ${e.message}")
      }
      jobId = jobIdOf(receipt)
      _ <- (for
        health <- query(jobId)
        _ <- IO(
          assert(
            health.contains("[Background job running]") || health.contains("[Background job completed]"),
            s"process alive right after conversion: $health"
          )
        )
        completed <- pollUntil(jobId, "[Background job completed]", 30.seconds)
        _ <- IO(assert(completed.contains("clone-done"), s"result delivered on completion: $completed"))
      yield ()).guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
    end for
  }

  test(
    "N-3 OLD-face control (对照): same silent command with NO budget hits the 600s-class ceiling kill — the incident path"
  ) {
    // 压缩 ceiling（4000ms 判死 / 1s 采样），预算保持缺省（120s 未到）⇒
    // 旧面行为 = 前台阶段被 ceiling 杀。这就是当日事故的精确复现形态
    // （预算从未到点、停滞阈值先到、结果 = 杀）。
    withProps(CeilingProp -> "4000", SampleProp -> "1000") {
      val input = JsonObject(
        "command" -> """python3 -c "import time; time.sleep(12); print('should-never-see-this')"""".asJson,
        "description" -> "old-face-stall-kill".asJson
      )
      val start = System.currentTimeMillis()
      BashTool.call(input, ToolContext(projectRoot = "/tmp")).map {
        case Left(e) =>
          val elapsed = System.currentTimeMillis() - start
          assert(e.message.contains("[Command stalled]"), s"incident path = stall kill: ${e.message}")
          assert(elapsed < 10000, s"ceiling fires ~4s under compression, took ${elapsed}ms")
        case Right(out) => fail(s"OLD-face control must stall-kill (incident reproduction), got: $out")
      }
    }
  }

  test("N-5 adopted job survives past the (compressed) no-progress ceiling — adoption retires the ceiling") {
    // ceiling 4s 判死、预算 2s：转后台发生在 2s（ceiling 未到），收编置位
    // adoptedAsBackground ⇒ watch 下一采样窗退役 ⇒ 4s 判死点过去后进程仍活，
    // 最终自然完成并经查询面投递。
    withProps(CeilingProp -> "4000", SampleProp -> "1000") {
      val input = JsonObject(
        "command" -> """python3 -c "import time; time.sleep(9); print('adopted-survivor')"""".asJson,
        "description" -> "adopted-ceiling-retired".asJson
      )
      (for
        result <- BashTool.call(input, budget2sCtx)
        receipt <- IO {
          result match
            case Right(out) => assert(out.contains("[Background job started]"), s"adopted: $out"); out
            case Left(e) => fail(s"budget must adopt (ceiling must NOT win the race), got: ${e.message}")
        }
        jobId = jobIdOf(receipt)
        // 跨过 ceiling 判死点（4s）：若收编未退役 ceiling，此处已是失败回执。
        _ <- IO.sleep(5.seconds)
        health <- query(jobId)
        _ <- IO(
          assert(
            health.contains("[Background job running]") || health.contains("[Background job completed]"),
            s"must have survived past the ceiling kill point: $health"
          )
        )
        completed <- pollUntil(jobId, "[Background job completed]", 30.seconds)
          .map(out => assert(out.contains("adopted-survivor"), s"runs to completion: $out"))
          .guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
      yield ())
      end for
    }
  }

  test("N-2b chatty command backgrounds at the budget too — criterion is TIME, not output liveness") {
    // 持续吐输出的长命令（老语义会因「有进展」永占前台）：预算 2s 照样转后台。
    val input = JsonObject(
      "command" -> "for i in 1 2 3 4 5 6 7 8 9 10; do echo tick-$i; sleep 1; done".asJson,
      "description" -> "chatty-long-runner".asJson
    )
    val start = System.currentTimeMillis()
    (for
      result <- BashTool.call(input, budget2sCtx)
      receipt <- IO {
        val elapsed = System.currentTimeMillis() - start
        result match
          case Right(out) =>
            assert(out.contains("[Background job started]"), s"chatty command must background at budget: $out")
            assert(elapsed < 8000, s"must return at the budget despite live output, took ${elapsed}ms")
            out
          case Left(e) => fail(s"got error: ${e.message}")
      }
      jobId = jobIdOf(receipt)
      _ <- pollUntil(jobId, "[Background job completed]", 40.seconds)
        .map(out => assert(out.contains("tick-10"), s"ran to completion: $out"))
        .guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
    end for
  }

  test("N-6 explicit timeout hint is clamped to the configured budget (author's zero-blocking ruling wins)") {
    val input = JsonObject(
      "command" -> "sleep 8 && echo clamped-done".asJson,
      "description" -> "hint-clamped".asJson,
      "timeout" -> 3600000.asJson // agent 想等 60min —— 必须被 2s 预算钳住
    )
    val start = System.currentTimeMillis()
    (for
      result <- BashTool.call(input, budget2sCtx)
      receipt <- IO {
        val elapsed = System.currentTimeMillis() - start
        result match
          case Right(out) =>
            assert(out.contains("[Background job started]"), s"clamped at budget: $out")
            assert(elapsed < 8000, s"hint must be clamped to ~2s, took ${elapsed}ms")
            out
          case Left(e) => fail(s"got error: ${e.message}")
      }
      jobId = jobIdOf(receipt)
      _ <- pollUntil(jobId, "[Background job completed]", 30.seconds)
        .map(out => assert(out.contains("clamped-done"), s"result delivered: $out"))
        .guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
    end for
  }

  test("N-7 engine short-command family regression: fast commands stay foreground with real output (default config)") {
    BashTool
      .call(
        JsonObject("command" -> "echo short-cmd-ok".asJson, "description" -> "short-cmd".asJson),
        ToolContext(projectRoot = "/tmp")
      )
      .map {
        case Right(out) =>
          assert(out.contains("short-cmd-ok"), s"real output: $out")
          assert(!out.contains("[Background job started]"), s"must NOT background below the budget: $out")
        case Left(e) => fail(s"short command failed: ${e.message}")
      }
  }

end BashForegroundBudgetSpec
