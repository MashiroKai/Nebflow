package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.delegate.{DualModeConfig, ModeDecision, DelegateModeAllocator}
import nebflow.core.jev.{JevAllocation, JevConfigReader, JevFallback, JevFallbackKind, JevGateOutcome}
import nebflow.llm.{ModelCandidate, ProviderRegistry, SendMessageParams}
import nebflow.shared.{Defaults, Message, MessageRole, NebflowLogger}

/**
 * DelegateModeMachine —— **delegate 模式选择器执行体**（双模式批 2026-10-28；设计
 * `20261028_213056_delegate-dual-mode-redesign` §3.2）。
 *
 * == 决策链（复用插件分配三态底座，`PluginAllocatorMachine` 同构）==
 *  ① **JeV 门**（JevConfigReader 热读）：Applied → JeV 问对腿；Off/未装配 → LLM
 *     fallback 腿（JevFallback 审计留痕——fallback 从不静默）。
 *  ② **JeV 问对腿**：沿用 `JevAllocator` 方法论三件——状态模板（固定前导＋任务文
 *     截断）、问对（`need_dispatcher` CHOICE{yes,no} ＋ `fit_dispatcher`
 *     SCORE{poor,partial,good}，一次批调）、成员规则（P(yes)≥0.5 → dispatcher）。
 *     与插件分配的差异只在候选集：两个模式而非 N 个插件——问对退化为单目标判定，
 *     模板更短。
 *  ③ **LLM fallback 腿**（`LlmAllocator` 同件形态）：单次 JSON 调用内置模型候选，
 *     输出 `{"mode":"kernel"|"dispatcher","reason":"…"}`；解析失败/无候选 → fail-open。
 *  ④ **fail-open 缺省 = kernel**（方向性安全：kernel 误判复杂任务 = 单会话硬扛零
 *     机制风险；dispatcher 误判简单任务 = 白付链开销——缺省取便宜的一侧；可由
 *     `delegate.dualMode.failopenMode` 旋钮改裁，DualModeConfig.failopenMode）。
 *
 * 🔴 本件不产出模型可见文案之外的行为；提示词为模型可见文案（§3.2 选择依据骨架，
 * 判据文字单点可调——改文案 = 改行为，mode-decision 台账行是回归锚）。
 * 🔴 零超时（2026-10-19 作者拍板先例）：本腿不带 wall-clock 预算，完成与否由
 * 探活/审计表达。
 */
final class DelegateModeMachine(
  /** JeV 腿热读装配：None = 配置面解不出 provider（gate 之外的兜底进路）。 */
  jevLeg: IO[Option[DecisionProvider[IO]]],
  /** LLM 兜底腿装配：None = 无可用模型候选（腿不可用）；Some = (候选, 单次调用缝)。 */
  llmLeg: IO[Option[(ModelCandidate, SendMessageParams => IO[Option[String]])]],
  /** fail-open 缺省方向（DualModeConfig.failopenMode 热读注入）。 */
  failopen: IO[String]
) extends DelegateModeAllocator.Face:

  private val logger = NebflowLogger.forName("nebflow.llm.mode-alloc")

  private val AllocSessionId = "delegate-mode-alloc"

  override def decideMode(subject: String, taskText: String): IO[ModeDecision] =
    JevConfigReader.outcome.flatMap {
      case JevGateOutcome.Applied(_) =>
        jevLeg.flatMap {
          case Some(provider) => jevDecide(subject, taskText, provider)
          case None =>
            IO(logger.warnSync(s"mode allocation '$subject': jev provider unresolved — llm fallback leg takes over")) *>
              fallbackLeg(subject, taskText)
        }
      case JevGateOutcome.Off(reason) =>
        IO(logger.infoSync(s"mode allocation '$subject': jev gate off ($reason) — llm fallback leg takes over")) *>
          fallbackLeg(subject, taskText)
    }

  // ── JeV 问对腿（JevAllocator 方法论三件，单目标判定形态）──────────────────

  private[decision] def stateFor(taskText: String): String =
    val brief = if taskText.length > JevAllocator.DefaultStateCapChars then taskText.take(JevAllocator.DefaultStateCapChars) else taskText
    s"${DelegateModeMachine.StatePreamble}\n\n${JevAllocator.BriefHeader}\n$brief"

  private[decision] def questionsFor: List[DecisionQuestion] = List(
    DecisionQuestion.Choice(
      id = "need_dispatcher",
      instructions =
        "Does executing this task's primary purpose REQUIRE dispatcher-mode orchestration - i.e. the task " +
          "involves multi-stage dependencies, parallel multi-leg work, multi-plugin collaboration, or explicit " +
          "chain orchestration that a single-session executor cannot properly carry? Judge by the task text alone; " +
          "ignore platform boilerplate.",
      criteria = Map(
        "yes" -> Some("requires dispatcher-mode chain orchestration"),
        "no" -> Some("a single-session kernel executor can carry it properly")
      )
    ),
    DecisionQuestion.Score(
      id = "fit_dispatcher",
      instructions = "How well does dispatcher-mode chain orchestration fit this task's primary purpose, by the task text alone?",
      criteria = List("poor", "partial", "good")
    )
  )

  private def jevDecide(subject: String, taskText: String, provider: DecisionProvider[IO]): IO[ModeDecision] =
    provider
      .predict(DecisionRequest(state = stateFor(taskText), questions = questionsFor))
      .map {
        case Left(err) => Left(err)
        case Right(resp) => Right(select(resp))
      }
      .flatMap {
        case Left(err) =>
          val kind = if err.isTimeout then JevFallbackKind.Timeout else JevFallbackKind.Failure
          jevFallbackThenFallbackLeg(kind, subject, taskText, err.message)
        case Right(mode) =>
          JevFallback
            .recordSuccess(subject, List(mode), Some("jev"))
            .as(ModeDecision(mode, "jev", "jev need_dispatcher/fit_dispatcher pair"))
      }
      .handleErrorWith { e =>
        jevFallbackThenFallbackLeg(JevFallbackKind.Failure, subject, taskText, e.getClass.getSimpleName)
      }

  /** R2 成员规则（单目标形）：P(need_dispatcher=yes) ≥ 0.5 → dispatcher，否则 kernel。 */
  private[decision] def select(resp: DecisionResponse): String =
    resp.answers.get("need_dispatcher") match
      case Some(a) =>
        DecisionAnswer.pYes(a) match
          case Some(p) => if p >= DecisionAnswer.YesThreshold then DualModeConfig.ModeDispatcher else DualModeConfig.ModeKernel
          case None =>
            logger.warnSync("need_dispatcher answered without a probabilities[yes] read — treated as kernel")
            DualModeConfig.ModeKernel
      case None =>
        logger.warnSync("need_dispatcher has no answer in the response — treated as kernel")
        DualModeConfig.ModeKernel

  private def jevFallbackThenFallbackLeg(kind: JevFallbackKind, subject: String, taskText: String, reason: String): IO[ModeDecision] =
    IO(logger.warnSync(s"mode allocation '$subject': jev leg unavailable ($reason) — falling back to the llm leg")) *>
      JevFallback.record(kind, subject, reason).flatMap(_ =>
        fallbackLeg(subject, taskText).map(d => d.copy(reason = s"jev leg fell back: $reason; ${d.reason}"))
      )

  // ── LLM fallback 腿（LlmAllocator 同件形态：单次 JSON）────────────────────

  private def fallbackLeg(subject: String, taskText: String): IO[ModeDecision] =
    failopen.flatMap { failopenMode =>
      llmLeg.flatMap {
        case None =>
          JevFallback
            .record(JevFallbackKind.Off, subject, "llm mode allocator: no model candidate available")
            .as(ModeDecision(failopenMode, "fail-open", "no model candidate available"))
        case Some((cand, call)) =>
          val params = SendMessageParams(
            messages = List(Message(MessageRole.User, Left(prompt(taskText)))),
            model = cand.model,
            sessionId = Some(AllocSessionId),
            agentId = Some(AllocSessionId),
            maxTokensOverride = Some(Defaults.ProbeMaxTokens)
          )
          call(params).attempt.flatMap {
            case Right(Some(reply)) =>
              parseMode(reply) match
                case Right((mode, reason)) =>
                  JevFallback
                    .recordSuccess(subject, List(mode), Some(PluginAllocatorMachine.LlmFallbackWire))
                    .as(ModeDecision(mode, "llm-fallback", reason))
                case Left(reason) =>
                  JevFallback
                    .record(JevFallbackKind.Failure, subject, s"llm mode allocator: $reason")
                    .as(ModeDecision(failopenMode, "fail-open", s"llm reply unparsable: $reason"))
            case Right(None) =>
              JevFallback
                .record(JevFallbackKind.Failure, subject, "llm mode allocator: call failed (no reply)")
                .as(ModeDecision(failopenMode, "fail-open", "llm call produced no reply"))
            case Left(e) =>
              JevFallback
                .record(JevFallbackKind.Failure, subject, s"llm mode allocator: ${e.getClass.getSimpleName}")
                .as(ModeDecision(failopenMode, "fail-open", s"llm call errored: ${e.getClass.getSimpleName}"))
          }
      }
    }

  private[decision] def prompt(taskText: String): String =
    val brief =
      val t = if taskText == null then "" else taskText
      if t.length > JevAllocator.DefaultStateCapChars then t.take(JevAllocator.DefaultStateCapChars) else t
    s"""${DelegateModeMachine.StatePreamble}

${JevAllocator.BriefHeader}
$brief

Choose the execution mode for this dispatched task, applying exactly this baseline: single-session single-artifact work with no phase dependencies and no parallel branches -> "kernel"; tasks with multi-stage dependencies, parallel multi-leg work, multi-plugin collaboration, or explicit orchestration demands -> "dispatcher". When equally easy to act on, choose the cheaper side ("kernel").

Answer with ONLY a JSON object: {"mode": "kernel" | "dispatcher", "reason": "<one short sentence>"}"""

  /**
   * 回文 → (mode, reason)。`Left` = 缺键 / 非 JSON / mode 词不在值域
   * （fail-open 走 Failure——绝不 fabricate 成功）。`reason` 缺键容忍（空串）。
   */
  private[decision] def parseMode(text: String): Either[String, (String, String)] =
    DelegateJudgeMachine.extractJsonObject(text) match
      case None => Left("reply carries no JSON object")
      case Some(json) =>
        json.hcursor.get[String]("mode").toOption match
          case None => Left("""reply JSON has no "mode" string""")
          case Some(raw) =>
            val mode = raw.trim.toLowerCase
            if !DualModeConfig.isModeWord(mode) then Left(s""""mode" value '$raw' is not kernel|dispatcher""")
            else Right(mode -> json.hcursor.get[String]("reason").getOrElse(""))

end DelegateModeMachine

object DelegateModeMachine:

  /**
   * 固定前导（§3.2 选择依据骨架——判据文字单点，mode-decision 台账行是回归锚）。
   */
  val StatePreamble: String =
    "You are auditing task dispatch in an agent-orchestration platform. Below is one dispatched task brief, " +
      "verbatim. It may contain platform boilerplate (default-clause templates, citation paths, workspace " +
      "footers) - ignore boilerplate; judge the task's substantive purpose. Judge from the task text alone."

  /**
   * 生产装配（注册点 = `agent.SharedResources` 构造行，PluginAllocatorMachine.face
   * 同缝同序）：JeV 腿 = JevSettings 热读构建；LLM 兜底腿 = 候选解析（判定机
   * utilityCandidate 同口径）；门 = JevConfigReader 热读；fail-open 方向 =
   * DualModeConfig 旋钮热读。
   */
  def face(registry: ProviderRegistry): DelegateModeAllocator.Face =
    new DelegateModeMachine(
      jevLeg = jevLeg,
      llmLeg = LlmAllocator.candidateFrom(registry).map {
        case None => None
        case Some(cand) =>
          val call = (params: SendMessageParams) =>
            registry.getAdapter(cand.providerId).flatMap(_.sendMessage(params)).attempt.map {
              case Right(resp) => Some(resp.reply)
              case Left(_)     => None
            }
          Some((cand, call))
      },
      failopen = IO.blocking(DualModeConfig.failopenMode)
    )

  private def jevLeg: IO[Option[DecisionProvider[IO]]] =
    IO.blocking(JevSettings.fromConfig(health = None)).flatMap {
      case None           => IO.pure(None)
      case Some(settings) => IO.blocking(DecisionProviders.build(settings))
    }
end DelegateModeMachine
