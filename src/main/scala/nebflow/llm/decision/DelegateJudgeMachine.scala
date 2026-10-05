package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.delegate.DelegateJudge
import nebflow.core.delegate.DelegateJudge.{DelegateJudgePort, TerminalInput, Verdict}
import nebflow.llm.{ProviderRegistry, SendMessageParams}
import nebflow.shared.{Config, Message, MessageRole, NebflowLogger}

import scala.concurrent.duration.*

/**
 * DelegateJudgeMachine —— 判定机执行体（worktree-judge-fifo 批 #1224 增补 §3.1：
 * **⑦ 判定机两层＝Jev 优先＋SmallJudge（LLM）兜底**，与分配族机制同一）.
 *
 * 分层：core 只见 `DelegateJudgePort`（core 零 llm import 纪律——JevAllocatorPort
 * 同款裁定）；本件落 llm.decision，注册点 = `agent.SharedResources` 构造行
 * （`DelegateJudgePort.install(face(resources.providerRegistry))` 一行）。
 *
 * 两层语义：
 *   ① Jev 优先 —— `jev` 节已配置（`JevSettings.fromConfig` 非 None）⇒ 走
 *      [[DecisionProvider]] 批量问句机制（与 JevAllocator 同族：Choice 问句 +
 *      P(yes)≥0.5 判据 / 三选一判词）；
 *   ② SmallJudge 兜底 —— Jev 未配置**或 Jev 调用失败** ⇒ 单次 LLM 调用（原文
 *      Batch 4：HealthMonitor.SendMessageParams 模式；模型 = `judge.utilityModel`
 *      可配，缺省 = 全局候选链首选；JSON 输出）。
 *
 * 失败语义：两层全败 ⇒ `None`（fail-open——spawn 腿落「判否＋建分支」纪律，终态
 * 腿落自申报/既有完成语义；判定机绝不阻塞派发与终态，JevAllocator 的 adjudicated
 * #3 posture 同款）。
 *
 * 🔴 判定机的问句/状态文本是**判定模型可见文案**——本批最小成稿（§16 待过目，
 * 终稿归 N3 提示词批）。
 */
object DelegateJudgeMachine:

  private val logger = NebflowLogger.forName("nebflow.llm.delegate-judge")

  /** 判定调用时限缺省（JudgeConfig.timeoutMs 缺省口径；30s——判定有界）。 */
  val DefaultTimeoutMs: Long = 30_000L

  /** 判定会话的身份戳（SendMessageParams 元数据；router 日志归因）。 */
  private val JudgeSessionId = "delegate-judge"

  // ── Face 装配（注册点唯一消费面） ────────────────────────────────────

  def face(registry: ProviderRegistry): DelegateJudgePort.Face =
    new DelegateJudgePort.Face:
      def decideWorktree(task: String): IO[Option[Boolean]] =
        jevLeg.flatMap {
          case Some((p, ms)) => jevWorktree(p, ms, task)
          case None          => smallJudgeWorktree(registry, task)
        }.flatTap {
          case None => IO(logger.warnSync("delegate-judge: worktree decision unavailable on both legs — falling back to no-worktree (branch discipline still applies)"))
          case _    => IO.unit
        }

      def judgeTerminal(input: TerminalInput): IO[Option[Verdict]] =
        jevLeg.flatMap {
          case Some((p, ms)) => jevVerdict(p, ms, input)
          case None          => smallJudgeVerdict(registry, input)
        }.flatTap {
          case None => IO(logger.warnSync(s"delegate-judge: terminal verdict unavailable on both legs for ${input.delegateId} — falling back to self-report/legacy"))
          case _    => IO.unit
        }
  end face

  // ── ① Jev 优先腿 ─────────────────────────────────────────────────────

  /** 热读装配：未配置 = None（整腿跳过 → SmallJudge 兜底）；携带分配调用时限。 */
  private def jevLeg: IO[Option[(DecisionProvider[IO], Long)]] =
    IO.blocking(JevSettings.fromConfig(health = None))
      .map(_.map(s => (DecisionProviders.build(s), s.timeoutMs)))
      .map(_.collect { case (Some(p), ms) => (p, ms) })

  /** 判 worktree 的 Jev 问句（Choice；need_ 同族判据 P(yes)≥0.5）。 */
  private def jevWorktree(p: DecisionProvider[IO], timeoutMs: Long, task: String): IO[Option[Boolean]] =
    val req = DecisionRequest(
      state = worktreeState(task),
      questions = List(
        DecisionQuestion.Choice(
          id = "worktree",
          instructions =
            "Should this delegated task be executed in a dedicated git worktree (an isolated checkout of the project repository)? " +
              "Answer yes when the task will modify tracked files in a git repository (code edits, builds producing tracked artifacts, " +
              "branch-based deliverables). Answer no for read-only tasks, conversation-only tasks, or work outside any git repository.",
          criteria = Map(
            "yes" -> Some("the task modifies tracked files in a git repository and benefits from an isolated checkout"),
            "no" -> Some("read-only, conversational, or outside any git repository — a plain branch in the project is enough")
          )
        )
      )
    )
    p.predict(req).timeout(timeoutMs.millis).attempt.flatMap {
      case Right(Right(resp)) =>
        IO.pure(resp.answers.get("worktree").flatMap(DecisionAnswer.pYes).map(_ >= DecisionAnswer.YesThreshold))
      case Right(Left(err)) =>
        IO(logger.warnSync(s"delegate-judge: jev worktree leg failed (${err.message}) — SmallJudge fallback")) *> IO.pure(None)
      case Left(e) =>
        IO(logger.warnSync(s"delegate-judge: jev worktree leg errored (${e.getClass.getSimpleName}) — SmallJudge fallback")) *> IO.pure(None)
    }
  end jevWorktree

  /** 终态三态的 Jev 问句（Choice 三选一；blocked 无自由文本——实质随终态消息直传）。 */
  private def jevVerdict(p: DecisionProvider[IO], timeoutMs: Long, input: TerminalInput): IO[Option[Verdict]] =
    val req = DecisionRequest(
      state = terminalState(input),
      questions = List(
        DecisionQuestion.Choice(
          id = "verdict",
          instructions = terminalInstructions(input),
          criteria = Map(
            DelegateJudge.VerdictWire.Completed -> Some(
              "the task is fully done and there is no unmerged work left on the branch (or the merge already landed cleanly)"
            ),
            DelegateJudge.VerdictWire.Blocked -> Some(
              "the task could not be completed, or the merge round hit a refusal/conflict — the terminal text states the blocker"
            ),
            DelegateJudge.VerdictWire.ReadyToMerge -> Some(
              "the task's deliverable commits sit on the delegate branch, unmerged, and the terminal text reports success"
            )
          )
        )
      )
    )
    p.predict(req).timeout(timeoutMs.millis).attempt.flatMap {
      case Right(Right(resp)) => IO.pure(verdictFromChoice(resp, input))
      case Right(Left(err)) =>
        IO(logger.warnSync(s"delegate-judge: jev verdict leg failed (${err.message}) — SmallJudge fallback")) *> IO.pure(None)
      case Left(e) =>
        IO(logger.warnSync(s"delegate-judge: jev verdict leg errored (${e.getClass.getSimpleName}) — SmallJudge fallback")) *> IO.pure(None)
    }
  end jevVerdict

  /** Choice 判词读取（纯函数——spec 判红锚）：`verdict` 问句的选中之词 → 判词。 */
  private[decision] def verdictFromChoice(resp: DecisionResponse, input: TerminalInput): Option[Verdict] =
    resp.answers.get("verdict").collect { case c: DecisionAnswer.Choice => c.choice.trim.toLowerCase } flatMap {
      case DelegateJudge.VerdictWire.Completed    => Some(Verdict.Completed)
      case DelegateJudge.VerdictWire.ReadyToMerge => Some(Verdict.ReadyToMerge)
      case DelegateJudge.VerdictWire.Blocked =>
        Some(Verdict.Blocked(s"jev judge: blocked${if input.mergeRound then " (merge round)" else ""} — substance in the terminal text"))
      case _ => None
    }

  // ── ② SmallJudge 兜底腿（单次 LLM 调用） ─────────────────────────────

  /** utility model 解析：`judge.utilityModel` ref → 候选；缺省 = 全局候选链首选（「根链首选」）。 */
  private def utilityCandidate(registry: ProviderRegistry): IO[Option[nebflow.llm.ModelCandidate]] =
    IO.blocking(Config.loadServiceConfig().judge.flatMap(_.utilityModel)).flatMap {
      case Some(ref) =>
        registry.getCandidateForRef(ref).flatMap {
          case Some(c) => IO.pure(Some(c))
          case None =>
            IO(logger.warnSync(s"delegate-judge: judge.utilityModel '$ref' unresolved — falling back to root-chain head")) *>
              registry.getCandidates().map(_.headOption)
        }
      case None => registry.getCandidates().map(_.headOption)
    }

  private def judgeTimeoutMs: Long =
    scala.util.Try(Config.loadServiceConfig().judge.flatMap(_.timeoutMs)).toOption.flatten
      .filter(_ > 0)
      .getOrElse(DefaultTimeoutMs)

  private def smallJudgeWorktree(registry: ProviderRegistry, task: String): IO[Option[Boolean]] =
    utilityCandidate(registry).flatMap {
      case None => IO.pure(None)
      case Some(c) =>
        val params = SendMessageParams(
          messages = List(
            Message(
              MessageRole.User,
              Left(
                s"""${worktreeState(task)}
               |
               |Answer with ONLY a JSON object: {"worktree": true|false}.""".stripMargin
              )
            )
          ),
          model = c.model,
          sessionId = Some(JudgeSessionId),
          agentId = Some(JudgeSessionId)
        )
        singleCall(registry, c.providerId, params).map(_.flatMap(parseWorktree))
    }
  end smallJudgeWorktree

  private def smallJudgeVerdict(registry: ProviderRegistry, input: TerminalInput): IO[Option[Verdict]] =
    utilityCandidate(registry).flatMap {
      case None => IO.pure(None)
      case Some(c) =>
        val params = SendMessageParams(
          messages = List(
            Message(
              MessageRole.User,
              Left(
                s"""${terminalState(input)}
               |
               |${terminalInstructions(input)}
               |
               |Answer with ONLY a JSON object: {"verdict": "completed"|"blocked"|"ready-to-merge", "reason": "<one line>"}.""".stripMargin
              )
            )
          ),
          model = c.model,
          sessionId = Some(JudgeSessionId),
          agentId = Some(JudgeSessionId)
        )
        singleCall(registry, c.providerId, params).map(_.flatMap(parseVerdict))
    }
  end smallJudgeVerdict

  /** HealthMonitor.SendMessageParams 模式的单次调用（probe 同形：adapter.sendMessage + timeout + attempt）。回文全文（解析在纯函数单点）。 */
  private def singleCall(
    registry: ProviderRegistry,
    providerId: String,
    params: SendMessageParams
  ): IO[Option[String]] =
    registry
      .getAdapter(providerId)
      .flatMap(_.sendMessage(params))
      .timeout(judgeTimeoutMs.millis)
      .attempt
      .flatMap {
        case Right(resp) => IO.pure(Some(resp.reply))
        case Left(e) =>
          IO(logger.warnSync(s"delegate-judge: small-judge call failed (${e.getClass.getSimpleName})")) *> IO.pure(None)
      }

  // ── 状态拼装与解析（纯函数——spec 判红锚） ───────────────────────────

  private def worktreeState(task: String): String =
    val capped = if task.length > 12000 then task.take(12000) else task
    s"""=== TASK BRIEF ===
$capped"""

  private def terminalState(input: TerminalInput): String =
    val selfReportLine = input.selfReport match
      case Some((status, detail)) => s"self-report: $status — $detail"
      case None                   => "self-report: (none)"
    s"""=== TASK BRIEF ===
${cap(input.taskBrief)}
=== RESULT (final message) ===
${cap(input.resultText)}
=== GIT STATUS DIGEST ===
${if input.gitDigest.isEmpty then "(not a git workspace)" else input.gitDigest}
=== CONTEXT ===
$selfReportLine
mergeRound: ${input.mergeRound}"""

  private def terminalInstructions(input: TerminalInput): String =
    if input.mergeRound then
      "This is the MERGE ROUND: the delegate was instructed to run `git merge --no-ff` in the base workspace. Judge from the git digest whether the merge landed cleanly (completed) or was refused/conflicted (blocked). ready-to-merge is NOT a valid outcome for a merge round."
    else
      "Judge the delegate's terminal state: completed (done, nothing left to merge), blocked (could not complete — the reason is in the result/git digest), or ready-to-merge (deliverable commits sit unmerged on its branch)."

  private def cap(s: String): String = if s.length > 12000 then s.take(12000) else s

  /** 从模型回文中取第一个完整 JSON 对象（裸 JSON / ```json 围栏 / 混排皆容）。 */
  private[decision] def extractJsonObject(text: String): Option[Json] =
    val t = text.trim
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if start < 0 || end <= start then None
    else io.circe.parser.parse(t.substring(start, end + 1)).toOption

  /** SmallJudge worktree JSON：`{"worktree": true|false}`。 */
  private[decision] def parseWorktree(text: String): Option[Boolean] =
    extractJsonObject(text).flatMap(j => j.hcursor.get[Boolean]("worktree").toOption)

  /** SmallJudge 三态 JSON：`{"verdict": "...", "reason": "..."}`。 */
  private[decision] def parseVerdict(text: String): Option[Verdict] =
    extractJsonObject(text).flatMap { j =>
      val verdict = j.hcursor.get[String]("verdict").toOption.map(_.trim.toLowerCase)
      val reason = j.hcursor.get[String]("reason").toOption.getOrElse("")
      verdict.flatMap {
        case DelegateJudge.VerdictWire.Completed    => Some(Verdict.Completed)
        case DelegateJudge.VerdictWire.ReadyToMerge => Some(Verdict.ReadyToMerge)
        case DelegateJudge.VerdictWire.Blocked      => Some(Verdict.Blocked(if reason.nonEmpty then reason else "small-judge: blocked"))
        case _                                      => None
      }
    }

end DelegateJudgeMachine
