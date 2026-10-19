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
 *   - [[Face.decideWorktree]] —— spawn 前（R6）：该 delegate 是否「会改文件」。
 *     判是/判否**一律**建分支 `delegate/<id>` ＋轻量 worktree（A5 步1，2026-10-08）：
 *     判是 = 工作座位（交付提交落自己分支，终态可进合并轮）；判否 = **只读座位**
 *     （brief 行走只读变体，零领先提交终态降级 completed 不入队）；两层全败 =
 *     按「判是」执行（fail-open 反转——宁可白建分支，不可丢分支纪律）。建权在
 *     判定机，delegate 不自建。
 *   - [[Face.judgeTerminal]] —— delegate 终态后：两态判词
 *     completed(+注记) / ready-to-merge（原文 Batch 4 终态 judge；输入 =
 *     任务书＋结果＋git status 摘要＋delegate_report 自申报；#566：blocked
 *     判词退役——判官的「没做成」折 completed＋注记，不是状态）。
 *
 * 失败语义（fail-open，JevAllocator 同款裁定：派发与终态绝不因判定机阻塞）：
 * 两个方法返回 `IO[Option[_]]`——`None` = 判定机不可用/失败，调用方走回落
 * （spawn 腿两层全败＝按判是（fail-open 反转）；终态腿 = delegate_report
 * 自申报 → 引擎既有完成语义）。
 */
object DelegateJudge:

  /**
   * 终态判词（#566 修复批 2026-10-19 · 作者令：delegate 面退役 blocked 状态——
   * 「delegate 是不应该有 blocked 型状态的，直接上报就行了，blocked 是给
   * agentflow 用的」）。
   *
   * 原文 Batch 4 三态（completed / blocked(+原因) / ready-to-merge）改为两态＋注记：
   * 判官的「没找到合并 / 任务没做成」之类**只是报告注记，不是状态**——delegate
   * 到点交报告就是 completed（注记随判词行 reason 通道与交付尾注走）；failed 保留
   * 给真失败（引擎 failed 终态腿，不经本枚举）。
   */
  enum Verdict:
    /** 任务完成、无可合并产物（或合并已完成后）——报 Nebula（既有链）。 */
    case Completed

    /**
     * completed 判词的**注记形**（原 blocked 判词的唯一残留通道）：wire 仍编
     * `completed`（无独立状态），note = 判官/预检/漂移闸/验收闸的注记文本，
     * 随判词行 reason 与交付尾注走。引擎控制流与裸 Completed 同面（Release）。
     */
    case CompletedNote(note: String)

    /** 工作分支上有可合并产物——入 MergeQueue（FIFO 按完成时间），合并轮终态再判。 */
    case ReadyToMerge

    /**
     * completed 形判词判定（裸 Completed 与注记形都算——机械验收闸等「completed
     *  形态全过闸」的判据单点）。
     */
    def isCompletedShaped: Boolean = this match
      case Completed | CompletedNote(_) => true
      case _ => false
  end Verdict

  /** 判定机对一次终态 judge 的全部输入。 */
  final case class TerminalInput(
    delegateId: String,
    /** 任务书原文（spawn brief，含 working-branch 行）。 */
    taskBrief: String,
    /** 终态结果文本（最后一条 assistant 消息）。 */
    resultText: String,
    /** git status 摘要（[[nebflow.core.GitWorktrees.statusDigest]]；非 git 工作区 = ""）。 */
    gitDigest: String,
    /**
     * delegate_report 自申报（status, detail）——值域 finish | merge；blocked 为
     *  退役前旧申报（#566 起映射 completed＋注记，不再产生独立状态）。
     */
    selfReport: Option[(String, String)],
    /** true = 该 delegate 已在合并轮（MergeQueue 已注入过合并 reminder）。 */
    mergeRound: Boolean,
    /**
     * N1 刀2 ⑸（候裁采纳项）：**在飞链计数**——该 delegate 自己作用域图内
     * 「存在非终态成员」的链数（fail-soft 缺席 = 0）。供判定机区分「工作全部
     * 收尾」与「还有自己登记的链在跑」——后者 terminal 不必然是整链终局。
     */
    inFlightChains: Int = 0,
    /**
     * 四修②（2026-10-07 修复令）：**全会话工具调用计数**（Completed 事件携
     * 带的全会话消息面现算，BackoffSupervisor 传入）。`None` = 调用方未计数
     * （外部执行器腿 / 直接调用——确定性预检跳过，既有语义）；`Some(0)` 且
     * resultText 空/空烧 stub ⇒ 终态确定性预检直接判 blocked（不依赖 LLM 判）。
     */
    sessionToolCalls: Option[Int] = None
  )

  /**
   * 一次「会否改文件」判定的裁决＋判定腿（引擎加固批 2026-10-09：spawn 行审计
   * 两键 `worktreeJudged` / `judgeLeg` 的数据源——additive，Face 旧方法不动）。
   */
  final case class WorktreeJudgement(changesFiles: Boolean, leg: String)

  /** 判定腿词表（spawn 行 `judgeLeg` 键的值域；与插件分配 `allocation` 三态同族）。 */
  object JudgeLeg:
    /** Jev 批量问句腿（①优先）。 */
    val Jev: String = "jev"

    /** SmallJudge 单次 LLM 兜底腿（②）。 */
    val SmallJudge: String = "small-judge"

    /** 两层全败的 fail-open 反转（按「判是」执行——非判定机的裁决）。 */
    val FailOpen: String = "fail-open"

    /** 调用方拿到裸 boolean（未升级的 Face 缺省实现——腿不可分）。 */
    val Unknown: String = "unknown"
  end JudgeLeg

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
       * 引擎加固批 2026-10-09（additive，带缺省实现——既有 Face 装配零改动）：
       * 裁决＋判定腿。缺省 = 复用 [[decideWorktree]]、腿记 `unknown`；生产执行体
       * （DelegateJudgeMachine）覆写以区分 jev / small-judge 两腿。
       */
      def decideWorktreeDetailed(task: String): IO[Option[WorktreeJudgement]] =
        decideWorktree(task).map(_.map(b => WorktreeJudgement(b, JudgeLeg.Unknown)))

      /**
       * 终态两态判定（#566：blocked 判词退役——判官的「没做成」折 completed＋注记）。
       * `None` = 判定机不可用/失败（调用方按自申报/既有语义回落）。
       */
      def judgeTerminal(input: TerminalInput): IO[Option[Verdict]]
    end Face

    private val face = new AtomicReference[Option[Face]](None)

    def install(f: Face): Unit = face.set(Some(f))

    def clear(): Unit = face.set(None)

    def decideWorktree(task: String): IO[Option[Boolean]] =
      face.get match
        case Some(f) => f.decideWorktree(task).handleErrorWith(_ => IO.pure(None))
        case None => IO.pure(None)

    /** [[Face.decideWorktreeDetailed]] 的注册器面（同 fail-open 兜底）。 */
    def decideWorktreeDetailed(task: String): IO[Option[WorktreeJudgement]] =
      face.get match
        case Some(f) => f.decideWorktreeDetailed(task).handleErrorWith(_ => IO.pure(None))
        case None => IO.pure(None)

    def judgeTerminal(input: TerminalInput): IO[Option[Verdict]] =
      face.get match
        case Some(f) => f.judgeTerminal(input).handleErrorWith(_ => IO.pure(None))
        case None => IO.pure(None)
  end DelegateJudgePort

  /** 判词的 JSON 承重键（判定机输出与 spec 断言共用，禁字面量散落）。 */
  object VerdictWire:
    val Completed: String = "completed"

    /**
     * 退役 wire 词（#566）：引擎**永不写出**（判官的「blocked」答案在映射点折成
     * completed＋注记）；常量保留仅供旧盘判词行/旧模型回文的解析侧容错。
     */
    val Blocked: String = "blocked"

    val ReadyToMerge: String = "ready-to-merge"

    /**
     * 引擎终态 wire 词（failed/cancelled——engine 判词行的 verdict 值域补全，
     *  #566：failed/cancelled 终态写真值，不再冒充 blocked）。
     */
    val Failed: String = "failed"
    val Cancelled: String = "cancelled"

    def fromWire(s: String): Option[String] =
      Set(Completed, Blocked, ReadyToMerge, Failed, Cancelled).find(_ == s.trim.toLowerCase)

    def encode(v: Verdict): String = v match
      case Verdict.Completed => Completed
      case Verdict.CompletedNote(_) => Completed // 注记形无独立 wire 词（注记走 reason）
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
        case Verdict.CompletedNote(n) => n
        case _ => ""),
      VerdictPayloadKeys.Source -> Json.fromString(source)
    )

  /**
   * 引擎终态判词行 payload（#566：failed/cancelled 腿写真值——不再写 blocked 冒充）。
   * verdict = 引擎任务终态词（VerdictWire.Failed / Cancelled），reason = 引擎注记。
   */
  def engineTerminalPayload(delegateId: String, taskId: Option[String], status: String, detail: String): Json =
    Json.obj(
      VerdictPayloadKeys.DelegateId -> Json.fromString(delegateId),
      VerdictPayloadKeys.TaskId -> Json.fromString(taskId.getOrElse("")),
      VerdictPayloadKeys.Verdict -> Json.fromString(status),
      VerdictPayloadKeys.Reason -> Json.fromString(detail),
      VerdictPayloadKeys.Source -> Json.fromString("engine")
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
