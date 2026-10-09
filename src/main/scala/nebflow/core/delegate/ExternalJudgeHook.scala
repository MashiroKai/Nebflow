package nebflow.core.delegate

import cats.effect.IO

/**
 * ExternalJudgeHook —— 外部 delegate 终态判定的**接线缝**（external-executors
 * 批 #1225 留缝；判定机本体归 worktree-judge-fifo 批 #1224）。
 *
 * == 增补条款 3.3（R6/R7＋⑦ 判定同族）==
 * 外部 judge 同走 #1224 的 DelegateJudge 入口（Jev 优先＋SmallJudge 兜底），
 * 复用其三态枚举（completed / blocked / ready-to-merge）与 per-project
 * MergeQueue（FIFO 完成时间排序）。本批与 #1224 **并行在飞**——接口先行
 * 对齐：本缝就是那个接口的外部侧消费点。#1224 落地后由其接线（一个赋值点，
 * 见 [[classify]]）；本批**不实现判定机**、不建第二套。
 *
 * == 本批缺省行为（缝未接线）==
 * `classify` 缺省恒 `None` ⇒ runner 按进程结果直判：exit 0 且流面无失败帧
 * ⇒ completed；否则 blocked（＋原因）。「ready-to-merge」在本批缺省永不产生
 * ⇒ 无 barrier 悬挂（#1224 的合并轮经 MailTool 外部续聊腿注入**一条 resume
 * 消息**（`merge --no-ff` 回基分支 brief）驱动——S15 钉死，通道已由本批建好）。
 *
 * == 合同（#1224 接线时遵守）==
 *   - 输入 = 外部腿的判定素材（任务书摘要 + CLI final 文本 + 进程结果）；
 *   - 输出 = None（弃判，runner 走缺省直判）或三态判词；
 *   - ready-to-merge 判词下 runner **不发**父终态通知（MergeQueue 持有该
 *     delegate 的父回报权，合并轮终态再判——原书 Batch 4/5 语义）；
 *   - blocked 判词下父通知 eventType="failed"、failureType="blocked"（外部
 *     无 AgentFrozen 冻结面——⑬：失败经 judge 三态 blocked 上报，不建旁路上送）。
 */
object ExternalJudgeHook:

  /** 三态判词（#1224 DelegateJudge 枚举的外部侧镜像；接线后以 #1224 为准）。 */
  enum Verdict:
    case Completed
    case Blocked(reason: String)
    case ReadyToMerge

  /** 判定素材（结果输入 = CLI final 文本——原书不变）。 */
  final case class Input(
      id: String,
      executor: String,
      workspace: String,
      project: Option[String],
      taskId: Option[String],
      /** 任务摘要（DelegateRegistry.title）。 */
      title: String,
      /** CLI final 文本（终态结果）。 */
      finalText: String,
      exitCode: Option[Int],
      /** 进程/流面已显式失败（fail-closed 面）。 */
      processFailed: Boolean
  )

  /**
   * #1224 接线点：`ExternalJudgeHook.classify = input => IO(Some(verdict))`。
   * 缺省 = 恒 None（缝未接线——进程结果直判）。volatile 单点，进程级唯一。
   */
  @volatile var classify: Input => IO[Option[Verdict]] = _ => IO.pure(None)

  /** runner 的终态决策入口：缝优先，缝未接线走进程结果直判。 */
  def decide(input: Input): IO[Option[Verdict]] =
    classify(input).handleErrorWith { e =>
      // judge 自身抛错 = 弃判（不楔死终态路径）——blocked 语义由进程结果兜住。
      nebflow.shared.NebflowLogger
        .forName("nebflow.delegate.judge-hook")
        .warn(s"ExternalJudgeHook.classify failed (fallback to process outcome): ${e.getMessage}")
        .as(None)
    }

end ExternalJudgeHook
