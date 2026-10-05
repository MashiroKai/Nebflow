package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json

import java.util.concurrent.atomic.AtomicReference

/**
 * DelegateJudge —— delegate 判定机的 core 侧（worktree-judge-fifo 批 #1224 增补
 * §3.1/§3.2；⑦ 判定机两层＝Jev 优先＋SmallJudge（LLM）兜底）.
 *
 * 分层（依赖倒置纪律：core 零 `nebflow.llm` import——`core/jev/JevAllocatorPort`
 * 同款裁定）：
 *   - 本件只承载**判词类型 + 判定输入 + 接线端口**（`DelegateJudgePort` 注册器，
 *     DelegateBudgetPort / SendConfirmPort / AskUserAnswerPort 同款 M2 形态）；
 *   - 判定机的**执行体**（Jev Choice 问句 / SmallJudge 单次 LLM 调用 / JSON 解析）
 *     落 `nebflow.llm.decision.DelegateJudgeMachine`，注册点 =
 *     `agent.SharedResources` 构造行（生产 boot 与测试装配共用）。
 *
 * 两个判定时机（R6 / 终态三态）：
 *   - [[Face.decideWorktree]] —— spawn 前（R6）：该 delegate 是否建 worktree。
 *     无论判否一律建分支 `delegate/<id>`（S13；建权在判定机，delegate 不自建）。
 *   - [[Face.judgeTerminal]] —— delegate 终态后：三态判词
 *     completed / blocked(+原因) / ready-to-merge（原文 Batch 4 终态 judge；输入 =
 *     任务书＋结果＋git status 摘要＋delegate_report 自申报）。
 *
 * 失败语义（fail-open，JevAllocator 同款裁定：派发与终态绝不因判定机阻塞）：
 * 两个方法返回 `IO[Option[_]]`——`None` = 判定机不可用/失败，调用方走回落
 * （spawn 腿 = 不建 worktree 仍建分支的「判否」路径；终态腿 = delegate_report
 * 自申报 → 引擎既有完成语义）。
 */
object DelegateJudge:

  /** 终态三态判词（原文 Batch 4：completed / blocked(+原因) / ready-to-merge）。 */
  enum Verdict:
    /** 任务完成、无可合并产物（或合并已完成后）——报 Nebula（既有链）。 */
    case Completed
    /** 做不成 / 合并冲突——报 Nebula＋原因（既有链，payload 携带 reason）。 */
    case Blocked(reason: String)
    /** 工作分支上有可合并产物——入 MergeQueue（FIFO 按完成时间），合并轮终态再判。 */
    case ReadyToMerge

  /** 判定机对一次终态 judge 的全部输入。 */
  final case class TerminalInput(
      delegateId: String,
      /** 任务书原文（spawn brief，含 working-branch 行）。 */
      taskBrief: String,
      /** 终态结果文本（最后一条 assistant 消息）。 */
      resultText: String,
      /** git status 摘要（[[nebflow.core.GitWorktrees.statusDigest]]；非 git 工作区 = ""）。 */
      gitDigest: String,
      /** delegate_report 自申报（status, detail）——值域 finish | blocked。 */
      selfReport: Option[(String, String)],
      /** true = 该 delegate 已在合并轮（MergeQueue 已注入过合并 reminder）。 */
      mergeRound: Boolean
  )

  /**
   * 判定机接线端口（M2 注册器；注册点 = `agent.SharedResources` 构造行）。
   * 未注册时两方法 = `IO.pure(None)`（判定机缺席 = 判否/既有完成语义，与
   * DelegateBudgetPort 的「无害 no-op」兜底同型——不产生新的失败面）。
   */
  object DelegateJudgePort:

    trait Face:
      /**
       * R6 spawn 前判定：是否建 worktree。`None` = 判定机不可用（调用方走判否
       * 腿的分支纪律，不重试——判定延迟有界，不拖 spawn）。
       */
      def decideWorktree(task: String): IO[Option[Boolean]]

      /**
       * 终态三态判定。`None` = 判定机不可用/失败（调用方按自申报/既有语义回落）。
       */
      def judgeTerminal(input: TerminalInput): IO[Option[Verdict]]
    end Face

    private val face = new AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))

    def clear(): Unit = face.set(None)

    def decideWorktree(task: String): IO[Option[Boolean]] =
      face.get match
        case Some(f) => f.decideWorktree(task).handleErrorWith(_ => IO.pure(None))
        case None    => IO.pure(None)

    def judgeTerminal(input: TerminalInput): IO[Option[Verdict]] =
      face.get match
        case Some(f) => f.judgeTerminal(input).handleErrorWith(_ => IO.pure(None))
        case None    => IO.pure(None)
  end DelegateJudgePort

  /** 判词的 JSON 承重键（判定机输出与 spec 断言共用，禁字面量散落）。 */
  object VerdictWire:
    val Completed: String = "completed"
    val Blocked: String = "blocked"
    val ReadyToMerge: String = "ready-to-merge"

    def fromWire(s: String): Option[String] =
      Set(Completed, Blocked, ReadyToMerge).find(_ == s.trim.toLowerCase)

    def encode(v: Verdict): String = v match
      case Verdict.Completed    => Completed
      case Verdict.Blocked(_)   => Blocked
      case Verdict.ReadyToMerge => ReadyToMerge
  end VerdictWire

  /** 判定行 payload 的承重键（ChainStore `terminal-verdict` 行 / spec 共用）。 */
  object VerdictPayloadKeys:
    val Verdict: String = "verdict"
    val Reason: String = "reason"
    val DelegateId: String = "delegateId"
    val Source: String = "source"
    val TaskId: String = "taskId"
  end VerdictPayloadKeys

  /** 判定 payload 快捷构造（ChainStore 行 + 测试共用）。 */
  def verdictPayload(delegateId: String, taskId: Option[String], v: Verdict, source: String): Json =
    Json.obj(
      VerdictPayloadKeys.DelegateId -> Json.fromString(delegateId),
      VerdictPayloadKeys.TaskId -> Json.fromString(taskId.getOrElse("")),
      VerdictPayloadKeys.Verdict -> Json.fromString(VerdictWire.encode(v)),
      VerdictPayloadKeys.Reason -> Json.fromString(v match
        case Verdict.Blocked(r) => r
        case _                  => ""),
      VerdictPayloadKeys.Source -> Json.fromString(source)
    )

end DelegateJudge

/**
 * delegate 会话身份判据单点（包级——DelegateReportTool / AgentSessionExecution
 * 的挂载面与工具内侧身份闸共用）：会话 id 前缀 `delegate-`（DelegateTool
 * .spawnBackground 的命名约定；AgentIdle.inferInjectionSource / AgentActor 同前缀
 * 先例）。
 */
object DelegateSessions:
  val Prefix: String = "delegate-"

  def isDelegateSession(sessionId: Option[String]): Boolean =
    sessionId.exists(_.startsWith(Prefix))
end DelegateSessions
