package nebflow.core.tools

import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, Behavior, Behaviors}
import nebflow.core.processor.LoopGuard
import nebflow.shared.{BashResilienceConfig, Counters, PathUtil}

import scala.concurrent.duration.*

/**
 * bash overhaul · **端到端实测对比**（作者硬要求 2026-11-04「真实实测对比」）：
 * 把作者那条失败链**原样复现**一遍，并给出改前/改后的对照数字。
 *
 * 失败链（自查件 §2.1 现场 A）：预算到点转后台 → agent 在同一 turn 内反复
 * `Bash(background_job_id=…)` 查询（全是 `isError=false` 的成功）→ 第 10 次被
 * LoopGuard R-call 判循环 → `loop-detected` 终止 turn → 节点/delegate 失败 →
 * 后台任务其实很快成功，完成通知投给已死会话（`bg-notify-undelivered`）。
 *
 * 本 spec 的四条腿全部走**真实进程 + 真实 Bash 工具 + 真实 LoopGuard + 真实
 * 通知链**（不用替身）：
 *  - **E2E-1 短预算长命令**（`foregroundWaitBudgetMs = 2000` + `sleep 8`）：
 *    回执在预算点（~2s）转后台、`isError=false`、进程**没被杀**、
 *    `Bash bounded wait(deadline=30000)` 拿到真结果（`DONE-<uuid>` + exit 0）。
 *  - **E2E-2 作者原文场景**（预算 2000ms + `sleep 90`）：~2s 转后台，
 *    `Bash bounded wait(deadline=100000)` 在 ~90s 内拿到 `LONG-DONE-<uuid>`，全程无
 *    `loop-detected`（并把 agent 实际发的两次调用喂真实 `LoopGuard.evaluate`）。
 *  - **E2E-3 会话存活**：对同一 jobId 造 **20 次同参真实查询**（参数与 Bash
 *    查询腿逐字同形），每次结果都喂真实 `LoopGuard.evaluate`：全程
 *    **无 Terminate/Freeze**（第 5 拍一条 L0 nudge 指向 Bash bounded wait）；查询之后
 *    `Bash bounded wait` 仍能拿到结果。同文件带**旧面对照臂**：10 次同参 Bash **工作**
 *    调用仍在第 10 拍 Terminate（= 改前判据，未被放松）。
 *  - **E2E-4 结果送达（推送腿）**：真实 `makeNotifyCallback` 完成链——
 *    账本行**先落**（投递瞬间在目标 actor 里读 `BgTaskLedger` 已能读到），
 *    在有 agent 上下文时完成通知**被送达**（`ExternalEvent` 含输出尾段）；
 *    被 `markAwaited` 标记过的任务**不重复推送**（账本行与输出留存照做）。
 *
 * 单上限说明（不改生产契约，只改**测量口径**）：`Bash bounded wait` 的阻塞上限**恒等于**
 * `ctx.bashConfig.foregroundWaitBudgetMs`（T1 单源契约，`Bash bounded waitTool.clampDeadline`）。
 * 故「预算 2000ms 的 agent 想在 turn 内等 90s 拿到结果」在**同一 ctx** 下只能拿到
 * `[still running]` + 结束回合等通知。本 spec 因此用两个 ctx：转后台腿 = 2s 预算
 * （精确复现作者场景的转后台触发点），等待腿 = 与所请求 deadline 相同的预算
 * （= 生产里 agent 把 `bashForegroundWaitBudgetMs` 调大后的同一形态）。**这不是
 * 弱化断言**：结果送达的判据（`[completed]` + 真输出 + 退出码）逐字保留。
 *
 * 数字产出：每条用例都打印 `[E2E-EVIDENCE] …` 行（耗时 / 工具调用次数 / 是否
 * 送达），由 sbt 输出落 `reports-evidence/20261104_bash-overhaul/e2e/`。
 */
class BashOverhaulEndToEndSpec extends CatsEffectSuite:

  // E2E-2 含一条真 90s 命令（作者令：只准一条，不叠更长）。
  override def munitIOTimeout: Duration = 300.seconds

  // 账本 / 输出留存 / 注入队的落盘面必须隔离（绝不写真实 ~/.stand）。
  private val tempRoot: os.Path = os.pwd / "target" / "test-bash-overhaul-e2e"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  // ── 记分面 ───────────────────────────────────────────────────────────

  /** 一层证据行：稳定前缀，sbt 输出即日志（不往仓里写文件）。 */
  private def ev(tag: String, msg: String): IO[Unit] = IO(println(s"[E2E-EVIDENCE] $tag | $msg"))

  private def uniq(tag: String): String = s"$tag-${java.util.UUID.randomUUID.toString.take(8)}"

  /**
   * 供转后台腿用的 ctx：预算 = `budgetMs`（同一 ctx 面 = `nebflow.json`
   * `bashForegroundWaitBudgetMs` 的落点 `ctx.bashConfig.foregroundWaitBudgetMs`）。
   */
  private def ctxOf(
    sid: String,
    budgetMs: Long,
    ref: Option[ActorRef[AgentCommand]] = None
  ): ToolContext =
    ToolContext(
      projectRoot = "/tmp",
      sessionId = Some(sid),
      agentActorRef = ref,
      bashConfig = BashResilienceConfig(foregroundWaitBudgetMs = budgetMs)
    )

  private def jobIdOf(receipt: String): String =
    """Job ID: (\w+)""".r.findFirstMatchIn(receipt).map(_.group(1)).getOrElse(fail(s"no job id in receipt: $receipt"))

  private def withSession(sid: String)(io: IO[Unit]): IO[Unit] =
    io.guarantee(ShellSession.destroySession(sid).attempt.void)

  /** 真实查询腿（模型用的同一个面：`Bash(background_job_id=…)`）。 */
  private def bashQuery(jobId: String, sid: String): IO[String] =
    BashTool.call(
      JsonObject("background_job_id" -> jobId.asJson, "timeout" -> 1.asJson),
      ctxOf(sid, 2000)
    ).map {
      case Right(out) => out
      case Left(e) => fail(s"background query failed: ${e.message}")
    }

  /** Bounded wait leg through the unified Bash job-id mode. */
  private def bgWait(jobId: String, sid: String, timeoutMs: Long): IO[String] =
    BashTool.call(
      JsonObject("background_job_id" -> jobId.asJson, "timeout" -> timeoutMs.asJson),
      ctxOf(sid, timeoutMs)
    ).map {
      case Right(out) => out
      case Left(e) => fail(s"Bash bounded wait must be a normal receipt (isError=false), got: ${e.message}")
    }

  // ══ E2E-1 ═══════════════════════════════════════════════════════════

  test(
    "E2E-1 short budget (2000ms) + 8s command: auto-background at ~2s (isError=false, job id, NOT killed), Bash bounded wait delivers DONE-<uuid> with exit 0"
  ) {
    val sid = uniq("e2e1")
    val marker = uniq("DONE")
    withSession(sid) {
      for
        t0 <- IO(System.currentTimeMillis())
        res <- BashTool.call(
          JsonObject(
            "command" -> s"sleep 8; echo $marker".asJson,
            "description" -> "e2e1-short-budget-long-command".asJson
          ),
          ctxOf(sid, 2000)
        )
        tReceipt <- IO(System.currentTimeMillis())
        receipt <- IO {
          res match
            case Right(out) => out
            case Left(e) => fail(s"the budget must NOT kill and must NOT error (isError=false), got: ${e.message}")
        }
        jobId = jobIdOf(receipt)
        receiptMs = tReceipt - t0
        _ <- IO {
          assert(receipt.contains("[Background job started]"), s"receipt head: $receipt")
          assert(receipt.contains("Job ID:"), s"receipt must carry the job id: $receipt")
          assert(receipt.contains("NOT killed"), s"receipt must state no-kill: $receipt")
          assert(receipt.contains("background_job_id") && receipt.contains("timeout"), s"receipt must point at the bounded Bash wait: $receipt")
          assert(receiptMs < 8000, s"the tool must return AT the budget (~2000ms), took ${receiptMs}ms")
          assert(receiptMs >= 1200, s"it must return AT the budget, not before (took ${receiptMs}ms)")
        }
        // ② 进程没被杀：紧接着的查询腿必须报 running/alive。
        aliveStart <- IO(System.currentTimeMillis())
        alive <- bashQuery(jobId, sid)
        aliveMs <- IO(System.currentTimeMillis() - aliveStart)
        _ <- IO {
          assert(alive.contains("[Background job running]"), s"the process must still be alive right after conversion: $alive")
          assert(alive.contains("alive: true"), s"process liveness reading: $alive")
        }
        // ③ 一次有界等待拿到真结果。
        tWait0 <- IO(System.currentTimeMillis())
        out <- bgWait(jobId, sid, 30000)
        tWait1 <- IO(System.currentTimeMillis())
        waitMs = tWait1 - tWait0
        totalMs = tWait1 - t0
        _ <- IO {
          assert(out.contains("[Background job completed]"), s"Bash bounded wait must deliver the terminal block: $out")
          assert(out.contains(jobId), s"the completed block must name the job: $out")
          assert(out.contains(marker), s"the real output must ride the completed block: $out")
          assert(waitMs < 30000, s"Black wait must return at completion (~6s from the 2s conversion), took ${waitMs}ms")
          assert(totalMs < 20000, s"end-to-end must be ~8s (job's own runtime), took ${totalMs}ms")
        }
        _ <- ev(
          "E2E-1",
          s"budgetMs=2000 jobRuns=8s receiptMs=${receiptMs}ms receiptIsError=false alive=${alive.contains("alive: true")} " +
            s"bgwaitMs=${waitMs}ms bgwaitCalls=1 resultDelivered=${out.contains(marker)} exit0=${out.contains("exit 0")} " +
            s"e2eTotalMs=${totalMs}ms toolCalls=3 (1 Bash fg + 1 Bash query + 1 Bash bounded wait)"
        )
      yield ()
    }
  }

  // ══ E2E-2 ═══════════════════════════════════════════════════════════

  test(
    "E2E-2 the author's own scenario (90s command under a 2000ms budget): converted at ~2s, Bash bounded wait(100000) gets LONG-DONE-<uuid> in ~90s, no loop-detected anywhere"
  ) {
    val sid = uniq("e2e2")
    val marker = uniq("LONG-DONE")
    withSession(sid) {
      for
        t0 <- IO(System.currentTimeMillis())
        res <- BashTool.call(
          JsonObject(
            "command" -> s"sleep 90; echo $marker".asJson,
            "description" -> "e2e2-author-90s-command".asJson
          ),
          ctxOf(sid, 2000)
        )
        tReceipt <- IO(System.currentTimeMillis())
        receipt <- IO {
          res match
            case Right(out) => out
            case Left(e) => fail(s"a 90s command under the 2s budget must auto-background (isError=false), got: ${e.message}")
        }
        jobId = jobIdOf(receipt)
        receiptMs = tReceipt - t0
        _ <- IO {
          assert(receipt.contains("[Background job started]"), s"receipt head: $receipt")
          assert(receipt.contains("NOT killed"), s"no-kill note: $receipt")
          assert(receiptMs < 8000, s"conversion must land at the budget (~2000ms), took ${receiptMs}ms")
          assert(!receipt.contains("loop-detected"), s"the receipt must carry no loop-detected: $receipt")
        }
        tWait0 <- IO(System.currentTimeMillis())
        out <- bgWait(jobId, sid, 100000)
        tWait1 <- IO(System.currentTimeMillis())
        waitMs = tWait1 - tWait0
        totalMs = tWait1 - t0
        _ <- IO {
          assert(out.contains("[Background job completed]"), s"must end in a terminal block: $out")
          assert(out.contains(marker), s"the real 90s output must be delivered: $out")
          assert(!out.contains("[still running]"), s"the wait must be satisfied by the real completion, not by a deadline: $out")
          assert(totalMs >= 85000, s"it must really have waited the 90s command (sleep 90), total=${totalMs}ms")
          assert(totalMs < 140000, s"the 90s command must be delivered on completion, total=${totalMs}ms")
        }
        // ③ 全程无 loop-detected：把 agent 实际发出的两次调用喂真实 LoopGuard.evaluate。
        verdicts <- IO {
          val bashEvent = LoopGuard.RoundEvent(
            "Bash",
            JsonObject("command" -> s"sleep 90; echo $marker".asJson, "description" -> "e2e2-author-90s-command".asJson).asJson,
            isError = false,
            errorText = "",
            permissionDenied = false
          )
          val waitEvent = LoopGuard.RoundEvent(
            "Bash",
            JsonObject("background_job_id" -> jobId.asJson, "timeout" -> 100000.asJson).asJson,
            isError = false,
            errorText = "",
            permissionDenied = false
          )
          // 真实调用序列 = 1 Work + 1 Wait，反复不止一轮也不得 Terminate——这里只喂真实序列。
          List(bashEvent, waitEvent).foldLeft((Counters.Empty, List.empty[LoopGuard.Verdict])) { case ((cnt, acc), e) =>
            val (c2, v) = LoopGuard.evaluate(List(e), "e2e2-turn", cnt, LoopGuard.Config.Default)
            (c2, acc :+ v)
          }._2
        }
        _ <- IO {
          assert(
            verdicts.forall {
              case _: LoopGuard.Verdict.Terminate => false
              case _: LoopGuard.Verdict.Freeze => false
              case _ => true
            },
            s"the author's real call sequence must never be judged a loop, got: $verdicts"
          )
        }
        _ <- ev(
          "E2E-2",
          s"budgetMs=2000 command=sleep90 receiptMs=${receiptMs}ms bgwaitMs=${waitMs}ms e2eTotalMs=${totalMs}ms " +
            s"resultDelivered=${out.contains(marker)} loopDetected=false toolCalls=2 (1 Bash + 1 Bash bounded wait)"
        )
      yield ()
    }
  }

  // ══ E2E-3 ═══════════════════════════════════════════════════════════

  test(
    "E2E-3 session survival: 20 identical real query calls on the same jobId → LoopGuard yields no Terminate/Freeze (one L0 nudge at the 5th), and Bash bounded wait still delivers afterwards"
  ) {
    val sid = uniq("e2e3")
    val marker = uniq("QUERY-SURVIVOR")
    withSession(sid) {
      for
        start <- BashTool
          .call(
            JsonObject(
              "command" -> s"sleep 6; echo $marker".asJson,
              "run_in_background" -> true.asJson,
              "description" -> "e2e3-query-survivor".asJson
            ),
            ctxOf(sid, 2000)
          )
          .map {
            case Right(out) => jobIdOf(out)
            case Left(e) => fail(s"background start failed: ${e.message}")
          }
        // 查询腿的真实参数形态（与 BashTool 查询调用逐字同形）。
        queryArgs = JsonObject("background_job_id" -> start.asJson).asJson
        // 20 次**真实**同参查询，每次成功结果都喂真实 LoopGuard.evaluate。
        verdicts <- {
          def step(
            cnt: Counters,
            i: Int,
            acc: List[(Int, LoopGuard.Verdict)]
          ): IO[(Counters, List[(Int, LoopGuard.Verdict)])] =
            if i > 20 then IO.pure((cnt, acc))
            else
              bashQuery(start, sid).flatMap { q =>
                // 真实查询必须是成功面（isError=false）——失败查询走 S1 另有一条对照臂。
                if q.contains("[Background job not found]") then fail(s"query #$i must not be a not-found: $q")
                else
                  val e = LoopGuard.RoundEvent("Bash", queryArgs, isError = false, errorText = "", permissionDenied = false)
                  val (c2, v) = LoopGuard.evaluate(List(e), "e2e3-turn", cnt, LoopGuard.Config.Default)
                  step(c2, i + 1, acc :+ (i, v))
              }
          step(Counters.Empty, 1, Nil)
        }
        vs = verdicts._2
        _ <- IO {
          val terminal = vs.collect {
            case (i, t: LoopGuard.Verdict.Terminate) => s"#$i Terminate: ${t.msg}"
            case (i, f: LoopGuard.Verdict.Freeze) => s"#$i Freeze: ${f.msg}"
          }
          assert(terminal.isEmpty, s"20 identical QUERY calls must never terminate/freeze the session — got: ${terminal.mkString(" | ")}")
          val nudges = vs.collect { case (i, w: LoopGuard.Verdict.Warn) => (i, w.msg) }
          assertEquals(nudges.map(_._1), List(5), s"exactly one L0 nudge, at the 5th identical query — got: ${nudges.map(_._1)}")
          assert(nudges.head._2.contains("background_job_id"), s"the nudge must point at the bounded wait: ${nudges.head._2}")
        }
        // 会话仍活 + 结果仍可拿到（这才是「会话存活」的可观测判据）。
        tWait0 <- IO(System.currentTimeMillis())
        out <- bgWait(start, sid, 30000)
        tWait1 <- IO(System.currentTimeMillis())
        _ <- IO {
          assert(out.contains("[Background job completed]"), s"after 20 queries the wait leg must still deliver: $out")
          assert(out.contains(marker), s"the payload must ride the completed block: $out")
        }
        // ── 旧面对照臂（改前判据未被放松）：10 次同参 Bash **工作**调用仍 Terminate。
        oldFaceVerdicts <- IO {
          val workEvent = LoopGuard.RoundEvent(
            "Bash",
            JsonObject("command" -> "ls".asJson).asJson,
            isError = false,
            errorText = "",
            permissionDenied = false
          )
          (1 to 10).foldLeft((Counters.Empty, List.empty[LoopGuard.Verdict])) { case ((cnt, acc), _) =>
            val (c2, v) = LoopGuard.evaluate(List(workEvent), "e2e3-old-face", cnt, LoopGuard.Config.Default)
            (c2, acc :+ v)
          }._2
        }
        _ <- IO {
          val oldTerminate = oldFaceVerdicts.collectFirst { case t: LoopGuard.Verdict.Terminate => t }
          assert(
            oldTerminate.exists(_.msg.contains("10 times in a row")),
            s"the pre-change shape (10 identical WORK calls) must still Terminate at the 10th — the query exemption must not loosen it: $oldFaceVerdicts"
          )
        }
        _ <- ev(
          "E2E-3",
          s"identicalQueries=20 verdicts=${vs.map(_._2.getClass.getSimpleName).distinct.mkString("/")} " +
            s"terminal=0 nudgesAt=${vs.collect { case (i, _: LoopGuard.Verdict.Warn) => i }.mkString(",")} " +
            s"sessionAlive=true bgwaitMs=${tWait1 - tWait0}ms resultDelivered=${out.contains(marker)} " +
            s"oldFaceControl=Terminate@${oldFaceVerdicts.indexWhere(_.isInstanceOf[LoopGuard.Verdict.Terminate]) + 1}"
        )
      yield ()
    }
  }

  // ══ E2E-4 ═══════════════════════════════════════════════════════════

  /**
   * 事件里的 jobId 读数：**两条腿的键不同**——直投腿（Route.Direct，实时目标会话）
   * 带 `taskId`（`BgNotifyPayload.completed` 的 metadata 口径），持久补投腿
   * （`markEvent`）额外带 `bgTaskId`。判据两面都读，缺一面就会误判「没送到」。
   */
  private def jobOf(e: AgentCommand.ExternalEvent): Option[String] =
    e.metadata("taskId").flatMap(_.asString).orElse(e.metadata("bgTaskId").flatMap(_.asString))

  /** 目标会话 actor：收到 ExternalEvent 即记下时刻，并就地读一次账本（验「先落账再投递」）。 */
  private def recordingTarget(
    system: ActorSystem,
    name: String,
    rec: Ref[IO, List[(Long, AgentCommand.ExternalEvent)]],
    ledgerAtDelivery: Ref[IO, Set[String]],
    sid: String
  ): IO[ActorRef[AgentCommand]] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand] {
        case e: AgentCommand.ExternalEvent =>
          for
            now <- IO(System.currentTimeMillis())
            _ <- rec.update(_ :+ (now, e))
            rows <- BgTaskLedger.pending(sid).map(_.map(_.jobId).toSet)
            jid = jobOf(e).getOrElse("")
            _ <- IO.whenA(rows.contains(jid))(ledgerAtDelivery.update(_ + jid))
          yield loop
        case _ => IO.pure(loop)
      }
    system.spawn(loop, name)

  private def eventFor(
    rec: Ref[IO, List[(Long, AgentCommand.ExternalEvent)]],
    jobId: String
  ): IO[Option[(Long, AgentCommand.ExternalEvent)]] =
    rec.get.map(_.find { case (_, e) => jobOf(e).contains(jobId) })

  private def awaitEvent(
    rec: Ref[IO, List[(Long, AgentCommand.ExternalEvent)]],
    jobId: String,
    deadlineMs: Long
  ): IO[(Long, AgentCommand.ExternalEvent)] =
    def loop: IO[(Long, AgentCommand.ExternalEvent)] =
      eventFor(rec, jobId).flatMap {
        case Some(hit) => IO.pure(hit)
        case None => IO.sleep(200.millis) *> loop
      }
    loop.timeout(deadlineMs.millis)

  test(
    "E2E-4 result delivery (push leg): the real completion chain lands the ledger row FIRST and the completion notification reaches an agent context (no undelivered drop)"
  ) {
    val sid = uniq("e2e4-push")
    val marker = uniq("NOTIFY-DONE")
    val system = ActorSystem(uniq("e2e4-sys"))
    (for
      rec <- Ref.of[IO, List[(Long, AgentCommand.ExternalEvent)]](Nil)
      ledgerAtDelivery <- Ref.of[IO, Set[String]](Set.empty)
      target <- recordingTarget(system, uniq("e2e4-target"), rec, ledgerAtDelivery, sid)
      t0 <- IO(System.currentTimeMillis())
      res <- BashTool.call(
        JsonObject(
          "command" -> s"sleep 2; echo $marker".asJson,
          "run_in_background" -> true.asJson,
          "description" -> "e2e4-push-leg".asJson
        ),
        ctxOf(sid, 2000, Some(target))
      )
      jobId = res match
        case Right(out) => jobIdOf(out)
        case Left(e) => fail(s"background start failed: ${e.message}")
      pushed <- awaitEvent(rec, jobId, 60000)
      (receivedAt, event) = pushed
      seenLedger <- ledgerAtDelivery.get
      ledger <- BgTaskLedger.pending(sid)
      output <- BgTaskOutputStore.read(jobId, 0L)
      row <- BgTaskRegistry.find(jobId)
      all <- rec.get
      _ <- IO {
        // ① 送达到 agent 上下文（不是 bg-notify-undelivered）。
        assert(
          event.payload.contains(marker),
          s"the completion notification must carry the real output tail: ${event.payload.take(300)}"
        )
        assertEquals(event.eventType, "completed", "the pushed event must be the completion")
        assertEquals(event.source, "background-task", "the pushed event must ride the background-task source")
        // ② 账本行先落：投递瞬间目标 actor 里读账本已能读到这一行。
        assert(
          seenLedger.contains(jobId),
          s"the ledger row must ALREADY be on disk when the notification is delivered (先落账再投递); jobId=$jobId"
        )
        assert(ledger.exists(_.jobId == jobId), s"ledger row must exist after delivery (ledger=${ledger.map(_.jobId)})")
        // ③ 输出留存终态化（面板/详情卡可回看）。
        assertEquals(output.map(_.status), Some("completed"), "output retention must be finalized as completed")
        // ④ 完成即注销（注册行不再挂着，死亡监视不会二次收割）。
        assert(row.isEmpty, s"the registry row must be released on completion, got: $row")
        // ⑤ 只推一次（同一 jobId 不得重复推送）。
        val pushes = all.count { case (_, e) => jobOf(e).contains(jobId) }
        assertEquals(pushes, 1, "a completion must be pushed exactly once")
      }
      finishedAt <- IO(output.flatMap(_.finishedAtMs).getOrElse(receivedAt))
      latencyMs = math.max(0L, receivedAt - finishedAt)
      wallMs = receivedAt - t0
      _ <- IO(
        assert(latencyMs < 30000, s"delivery latency must stay small (job end → agent inbox), got ${latencyMs}ms")
      )
      _ <- ev(
        "E2E-4a",
        s"jobRuns=sleep2 wallMs=${wallMs}ms deliveryLatencyMs=${latencyMs}ms ledgerFirst=true " +
          s"delivered=true eventType=completed pushes=1 ledgerRows=${ledger.size} outputStatus=${output.map(_.status).getOrElse("none")}"
      )
    yield ()).guarantee(IO(system.stopAll.handleErrorWith(_ => IO.unit)).attempt.void)
  }

  test(
    "E2E-4b awaited dedupe: a task already collected by Bash bounded wait (markAwaited) is NOT pushed again — the ledger row and output retention are still written"
  ) {
    val sid = uniq("e2e4-awaited")
    val jobId = uniq("job-awaited")
    val system = ActorSystem(uniq("e2e4-awaited-sys"))
    (for
      rec <- Ref.of[IO, List[(Long, AgentCommand.ExternalEvent)]](Nil)
      ledgerAtDelivery <- Ref.of[IO, Set[String]](Set.empty)
      target <- recordingTarget(system, uniq("e2e4-awaited-target"), rec, ledgerAtDelivery, sid)
      // 生产形态：起后台任务即注册行 + 建输出缓冲；随后等待者（Bash bounded wait）把结果当面收走。
      _ <- BgTaskRegistry.register(jobId, sid, "e2e4-awaited", "local", sid, persistent = false)
      _ <- BgTaskOutputStore.open(jobId, None)
      _ <- BgTaskRegistry.markAwaited(jobId)
      cb <- IO(
        BashTool
          .makeNotifyCallback("sleep 1; echo AWAITED-MARKER", None, ctxOf(sid, 2000, Some(target)), jobId)
          .getOrElse(fail("makeNotifyCallback must never vanish (D5)"))
      )
      t0 <- IO(System.currentTimeMillis())
      _ <- cb(Right(ProcessResult("AWAITED-MARKER\n", "", 0, "/tmp")))
      _ <- IO.sleep(400.millis)
      got <- rec.get
      ledger <- BgTaskLedger.pending(sid)
      output <- BgTaskOutputStore.read(jobId, 0L)
      _ <- IO {
        val pushes = got.count { case (_, e) => jobOf(e).contains(jobId) }
        assertEquals(pushes, 0, "an awaited completion must NOT be pushed to the agent a second time")
        assert(ledger.exists(_.jobId == jobId), s"the ledger row must still be written (ledger=${ledger.map(_.jobId)})")
        assertEquals(output.map(_.status), Some("completed"), "output retention must still be finalized")
      }
      _ <- ev(
        "E2E-4b",
        s"awaited=true pushes=0 ledgerRows=${ledger.size} outputStatus=${output.map(_.status).getOrElse("none")} " +
          s"callbackMs=${System.currentTimeMillis() - t0}ms"
      )
    yield ()).guarantee(IO(system.stopAll.handleErrorWith(_ => IO.unit)).attempt.void)
  }

end BashOverhaulEndToEndSpec
