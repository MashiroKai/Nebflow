package nebflow.core.delegate

import cats.effect.IO
import cats.effect.std.AtomicCell
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AgentRuntimePort
import nebflow.shared.{Defaults, NebflowLogger}

/**
 * DelegateFreeze —— **错误 = 冻结态（frozen），不是终止态**（错误冻结批
 * 2026-11-01 作者令逐字：「所有因错误状态的 delegate 应该上报之后 要能保留状态
 * 冻结态 方便被唤醒 接着做」）。
 *
 * == 与既有三态的分工（语义分工表见本批 docs/ 的冻结态状态机文档）==
 *   - `standby`（案A 批 2026-10-31）= **自愿待命**（回合正常收尾、申报
 *     显式待命（状态槽 standby，等作者给活）——actor 活着、状态
 *     `Waiting`、等待合法；
 *   - `frozen`（本件）= **异常冻结**（LLM 层故障 / 工具层不可恢复错误 / 引擎侧
 *     异常 / 配额取消）——同一会话（同 id、同历史、同 worktree）就地停住，
 *     **不判 failed、不 respawn、不重试**；唤醒才是续跑（作者：「冻结即停，
 *     唤醒才是重试」）；
 *
 * == 冻结保留什么（落盘、跨重启仍在）==
 *   ① 会话完整历史（SessionStore transcript——错误路径已 persist 后才冻结）；
 *   ② 工作区/worktree 引用与未提交改动（`DelegateRegistry.Record` 的
 *      cwd/worktree/branch/baseBranch；worktree 目录原样不动）；
 *   ③ 任务台账条目（`SubAgentTaskStore` 行 status=`frozen`——不进 boot 收殓视野）；
 *   ④ 工作分支与 workspace 锚原样（worktree 注册不动）；
 *   ⑤ 失败原因与最后一次错误上下文（registry `frozenReason/frozenDetail`，含
 *      provider/model 串——来自错误消息原文）。
 *
 * == 上报（冻结回执，不是失败回执）==
 * [[receiptText]] = 冻结回执正文（谁冻了 / 为什么 / 保留了什么 / 怎么唤醒），经
 * 父会话**持久邮箱**投递（`eventType="frozen"`——**不是** failed/completed/
 * cancelled，不抢终态幂等键、也不与后续真终态互吞，见
 * `DelegateDeliveryReceipts.TerminalEventTypes`）。
 *
 * == 唤醒（在原会话上下文上继续）==
 *   - live（进程内）：Mail `delegate:<id>` / 面板「唤醒」⇒ 向**原 actor** 注入
 *     UserInput（[[wakeBanner]] 前缀说明冻结点与原因），历史在场、已完成的不重做；
 *   - 不 live（进程重启后）：同 id 复活（`DelegateRevival` 的冻结腿，transcript
 *     checkpoint 续跑，**不新建会话**）；
 *   - 唤醒后 registry 冻结槽清空（[[DelegateRegistry.clearFrozen]]）、任务行翻
 *     running（[[onWoken]]）。
 *
 * == 兜底（不许无声堆积）==
 * [[staleDormant]] 纯函数 + [[dormantSweep]]：长期无人唤醒 ⇒ 面板提示
 * （`delegateFrozenDormant` 帧，缺省 24h 起）；超保留期（缺省 14 天）⇒ 归档
 * registry 记录（历史窗仍可查 `?archived=1`）+ 台账行留痕 + 响亮 WARN——绝不
 * 静默删除。
 *
 * == 分层 ==
 * 本件 ∈ core.delegate：只用 `AgentRuntimePort`（registry / taskStore）、
 * core 内单例（[[ChainStore]] / [[DelegateRegistry]]）与
 * `shared.Defaults`——不 import agent 层。agent 侧的冻结腿（监督者）与唤醒腿
 * （同 id 复活）在 agent 层调用本件的公开面。
 */
object DelegateFreeze:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.freeze")

  // ── 原因枚举（写进状态、面板可见、唤醒文案可读）──────────────────────────

  /**
   * 冻结原因（任务书点名的四类）：`llm-failure` / `tool-failure` /
   * `engine-fault` / `cancelled-by-quota`。词表是**状态面**的一部分——写进
   * `AgentRecord.frozenReason` 与 `DelegateRegistry.Record.frozenReason`。
   */
  enum Reason:
    /** LLM 层故障（provider 报错 / 全候选耗尽 / 鉴权/格式错误等）。 */
    case LlmFailure

    /**
     * **配置失效**：模型对该供应商/分组不存在（404 model_not_found /
     * `not available for this group`）——重试不会自己好（model-404-freeze 批
     * 2026-11-07）。与 [[LlmFailure]] 的分界就是本批的核心：provider 活着，错的是
     * 我们指的模型，所以要**人改配置**，不许无限自动重试。
     */
    case ModelUnavailable

    /** 工具层不可恢复错误（工具管道异常、工具侧硬失败）。 */
    case ToolFailure

    /** 引擎侧异常（监督耗尽 / respawn 链中途失败 / 空烧预算耗尽 / 进程级故障）。 */
    case EngineFault

    /** 配额/预算类取消（额度耗尽触发的 cancel）。 */
    case CancelledByQuota

  end Reason

  /** 全部原因（枚举序；重放面识别回执原因用——Scala 3 `enum` 无反射式 `values`）。 */
  val All: List[Reason] =
    List(Reason.LlmFailure, Reason.ModelUnavailable, Reason.ToolFailure, Reason.EngineFault, Reason.CancelledByQuota)

  /** 原因 → 线值（落盘/帧字段；短横线小写，与既有 frozenReason 词表同规格）。 */
  def wire(r: Reason): String = r match
    case Reason.LlmFailure => "llm-failure"
    case Reason.ModelUnavailable => "model-unavailable"
    case Reason.ToolFailure => "tool-failure"
    case Reason.EngineFault => "engine-fault"
    case Reason.CancelledByQuota => "cancelled-by-quota"

  /**
   * registry `frozenReason` 的**本批前缀**——与时间表冻结（`schedule`）/ LoopGuard
   * 冻结（`loop`）/ 错误族退避冻结（`llm-transient` 等）显式区分：面板与
   * `WsSessionChatHandlers.handleParentRestart` 据此判「这是 delegate 错误冻结」。
   */
  val WirePrefix: String = "delegate-frozen"

  /** 落 `AgentRecord.frozenReason` / `Record.frozenReason` 的完整线值。 */
  def wireValue(r: Reason): String = s"$WirePrefix:${wire(r)}"

  /** 线值 → 原因（非本批前缀 / 未知词 = None——旧盘面与其它冻结族零误判）。
    *
    * 线值允许携带**诊断后缀**（`detailedWireValue` 写出的
    * `engine-fault[trigger=…; errorType=…; …]`）——判定只读 `[` 之前的裸原因码，
    * 后缀是给人与审计面的触发源证据，绝不参与判等（对全部原因码机械生效，不特判
    * 单一码——旧盘面无后缀时行为逐字节不变）。
    */
  def parse(line: String): Option[Reason] =
    val s = Option(line).getOrElse("").trim
    if !s.startsWith(WirePrefix) then None
    else
      val code = s.stripPrefix(WirePrefix).stripPrefix(":").takeWhile(_ != '[')
      code match
        case "llm-failure" => Some(Reason.LlmFailure)
        case "model-unavailable" => Some(Reason.ModelUnavailable)
        case "tool-failure" => Some(Reason.ToolFailure)
        case "engine-fault" => Some(Reason.EngineFault)
        case "cancelled-by-quota" => Some(Reason.CancelledByQuota)
        case _ => None

  /** 是否本批的 delegate 错误冻结线值。 */
  def isFreezeWire(line: String): Boolean = parse(line).isDefined

  /**
   * 错误 → 原因的机器判读（纯函数，spec 判红锚）。输入 = 失败事件的
   * `errorType` 线值（`AgentErrorType.toString`）与消息正文。
   *
   * 判定顺序（provider 故障降噪批 2026-11-05：分类真源 = [[ProviderFault]] 判据表，
   * 本方法只是一个「表 → 四类原因码」的投影，**不再自己写散落 if**）：
   *
   *   1. `cancelled-by-quota`：消息命中配额/余额族（额度耗尽属 provider 侧可恢复，
   *      线值 `cancelled-by-quota` 本身就在 `FreezeDisplay.InfraReasonCodes` 里）；
   *   1'. `model-unavailable`（model-404-freeze 批 2026-11-07）：消息命中**配置失效族**
   *      （404 model_not_found / `not available for this group`）。这一条必须排在 ③ 之前
   *      ——旧判据里这类正文带着 `upstream` / `provider` 字样会命中上游 5xx 族（可恢复），
   *      于是「模型名指错」被当成「供应商临时挂了」，冻结后无限自动重试（作者
   *      2026-11-07 12:26 报障「一直卡住没跑完」的根因）；
   *   2. `tool-failure`：`ToolFailed` 或消息含工具管道标记 —— **确定性缺陷优先**，
   *      工具侧 bug 哪怕正文带着网络字样也照常通知；
   *   3. `llm-failure`：消息命中 [[ProviderFault.Table]] 的任一**可恢复族**
   *      （鉴权/订阅 403、配额余额、上游 5xx 含 550、连接失败/超时、格式层拒绝含
   *      context 超长 / `Input too long`、频率闸）——这是本批最关键的一条：这类
   *      失败以前落到 `engine-fault`（= 需人处理 ⇒ 逐条通知作者），现在归
   *      `llm-failure`（= provider 故障族 ⇒ 零通知 + 自动续跑）；
   *   4. `LlmFailed` ⇒ `llm-failure`（LLM 调用层失败，含 provider 全耗尽）；
   *   5. 其余（Timeout / Interrupted / DepthExceeded / Unknown）⇒ `engine-fault`。
   */
  def classify(errorType: String, message: String): Reason =
    val m = Option(message).getOrElse("")
    val ml = m.toLowerCase
    val t = Option(errorType).getOrElse("")
    if ml.contains("quota") || ml.contains("额度") || ml.contains("insufficient_quota") then Reason.CancelledByQuota
    else if t == "ToolFailed" || ml.contains("toolpipeline") || ml.contains("tool pipeline") then Reason.ToolFailure
    else if ProviderFault.isConfigInvalid(m) then Reason.ModelUnavailable
    else if ProviderFault.isRecoverable(m) && !ProviderFault.isRealFailure(m) then Reason.LlmFailure
    else if t == "LlmFailed" then Reason.LlmFailure
    else Reason.EngineFault

  /**
   * 该冻结原因是否属 provider 侧可恢复族（本批裁定：静默 + 自动续跑，零通知）。
   * 单一真源 = [[ProviderFault]] × `FreezeDisplay.InfraReasonCodes`。
   */
  def isProviderRecoverable(r: Reason): Boolean =
    nebflow.actor.FreezeDisplay.isInfraReasonCode(wire(r))

  /** 监督者内部触发器 → 原因（未知 = None ⇒ 走 [[classify]]）。 */
  def reasonForTrigger(trigger: String): Option[Reason] =
    Option(trigger).getOrElse("") match
      case "supervision-exhausted" | "respawn-failed" | "empty-burn" | "crashed" | "supervisor-fault" =>
        Some(Reason.EngineFault)
      case "cancelled-by-quota" | "quota" => Some(Reason.CancelledByQuota)
      case _ => None

  /** 原因的中文短名（面板/回执用；双语载荷里两语齐备）。 */
  def reasonLabelZh(r: Reason): String = r match
    case Reason.LlmFailure => "模型层故障"
    case Reason.ModelUnavailable => "模型不可用（配置失效）"
    case Reason.ToolFailure => "工具层故障"
    case Reason.EngineFault => "引擎侧故障"
    case Reason.CancelledByQuota => "配额耗尽取消"

  /** 原因的英文短名（回执/日志用）。 */
  def reasonLabelEn(r: Reason): String = r match
    case Reason.LlmFailure => "LLM-layer failure"
    case Reason.ModelUnavailable => "model unavailable (config error)"
    case Reason.ToolFailure => "tool-layer failure"
    case Reason.EngineFault => "engine fault"
    case Reason.CancelledByQuota => "quota-exhausted cancel"

  // ── 文案面（冻结回执 / 唤醒横幅）────────────────────────────────────────

  /** 详情压缩（单行、去控制字符、截断——落盘与回执共用单点）。 */
  def compactDetail(raw: String, max: Int = 400): String =
    // Keep the first diagnostic line only: exception stack traces commonly follow it and
    // must not crowd out the triggering error. Normalize controls without flattening stacks.
    val firstLine = Option(raw).getOrElse("").linesIterator.take(1).mkString.trim
    val cleaned = firstLine.replaceAll("[\\r\\t]+", " ").trim
    if cleaned.length <= max then cleaned else cleaned.take(max) + "…"

  /** Preserve older `engine-fault` records while allowing a diagnostic suffix in new ones. */
  def detailedWireValue(r: Reason, detail: String): String =
    val base = wireValue(r)
    if r == Reason.EngineFault && detail.trim.nonEmpty then s"$base[${compactDetail(detail, 200)}]" else base

  // ── 触发源诊断（engine-fault 冻结报错补详批 · 第二波）────────────────────────
  //
  // 每条冻结记录都必须答得出「什么触发了这次冻结」：trigger = 哪条腿/哪个事件
  // （`failed-event` / `cancelled-by-quota` / `managed-workspace-stopped` /
  // `supervision-exhausted` / `respawn-failed`），errorType = 原始错误类别**逐字**
  // （分类兜底（[[classify]] 落 `engine-fault` 档）永不吞掉它——原始值保住在记录
  // （frozenDetail / frozenTrigger 槽）与文案（回执 / 告警 / 面板）可见面）。

  /** 结构化诊断前缀的**格式单点**：`trigger=<t>; errorType=<e>`（任一缺席就只写在场的那半）。 */
  def triggerDiagnosis(trigger: String, errorType: String): String =
    val t = Option(trigger).getOrElse("").trim
    val e = Option(errorType).getOrElse("").trim
    (t, e) match
      case ("", "") => ""
      case ("", _) => s"errorType=$e"
      case (_, "") => s"trigger=$t"
      case _ => s"trigger=$t; errorType=$e"

  /** 从详情行提取触发源（`trigger=<v>`；旧记录/旧腿没写 ⇒ None——零误判）。 */
  def triggerOf(detail: String): Option[String] =
    diagnosisFieldsOf(detail)._1

  /** 从详情行提取原始错误类别（`errorType=<v>`；没写 ⇒ None——零误判）。 */
  def errorTypeOf(detail: String): Option[String] =
    diagnosisFieldsOf(detail)._2

  /**
   * 诊断前缀字段提取（只认**行首诊断前缀区**内的 `key=<value>` 形态；值到下一个 `;`
   * 或行尾为止）。前缀区 = 行首起连续的 `trigger=…` / `errorType=…` 段（构建单点
   * [[triggerDiagnosis]] 的 canonical 顺序 trigger 在前，errorType-only 形态也认）；
   * 前缀区一旦结束（第一段非 `key=` 形态）即停——**绝不扫正文**，正文里出现
   * "trigger=" / "errorType=" 字样不构成误判。
   */
  private def diagnosisFieldsOf(detail: String): (Option[String], Option[String]) =
    val s = Option(detail).getOrElse("").trim
    var rest = s
    var trigger: Option[String] = None
    var errorType: Option[String] = None
    diagnosisFieldOf(rest, "trigger").foreach { v =>
      trigger = Some(v)
      rest = rest.stripPrefix(s"trigger=$v").stripPrefix(";").trim
    }
    if trigger.isDefined || rest.startsWith("errorType=") then
      diagnosisFieldOf(rest, "errorType").foreach { v =>
        errorType = Some(v)
        rest = rest.stripPrefix(s"errorType=$v").stripPrefix(";").trim
      }
    (trigger, errorType)

  /** 单字段行首形态（供 [[diagnosisFieldsOf]] 逐段消费）。 */
  private def diagnosisFieldOf(detail: String, key: String): Option[String] =
    val s = Option(detail).getOrElse("").trim
    if !s.startsWith(s"$key=") then None
    else
      val v = s.stripPrefix(s"$key=").takeWhile(_ != ';').trim
      Option.when(v.nonEmpty)(v)

  /** 剥掉行首的结构化诊断前缀（`trigger=…; errorType=…; `），让判据表读到**原始错误文本**
    * （`ProviderFault` 判据表是子串匹配——`errorType=Timeout` 自带的 "timeout" 字样
    * 不得参与正文族判定）。无前缀 = 原样返回（旧记录零影响）。
    */
  def stripTriggerDiagnosis(detail: String): String =
    val s = Option(detail).getOrElse("").trim
    var rest = s
    List("trigger", "errorType").foreach { key =>
      diagnosisFieldOf(rest, key).foreach { v =>
        rest = rest.stripPrefix(s"$key=$v").stripPrefix(";").trim
      }
    }
    rest

  /**
   * 冻结回执正文（**四段**：谁冻了 / 为什么 / 保留了什么 / 怎么唤醒）。双语在册
   * （en 主文 + zh 摘要行）——模型任读其一，审计面两语齐备。
   *
   * 判词诚实铁律（作者令「不许把引擎/上游故障写成 agent 的失败」）：正文逐字
   * 声明「这不是失败判词，是冻结」，并给出唤醒地址。
   */
  def receiptText(
    delegateId: String,
    description: String,
    reason: Reason,
    detail: String,
    workspace: String
  ): String =
    val why = compactDetail(detail)
    val ws = if workspace.trim.isEmpty then "(unknown)" else workspace
    s"""[delegate-frozen] "$description" (delegate #$delegateId) hit **${reasonLabelEn(
        reason
      )}** and was FROZEN — this is NOT a failure verdict.
       |
       |WHO: the engine froze delegate #$delegateId (reason code `${wire(
        reason
      )}`). Nothing was judged about the work itself.
       |WHY: $why
       |WHAT IS KEPT (persisted; survives an engine restart): the full session transcript, the workspace/worktree $ws including uncommitted changes, the task-ledger row (status=frozen), the merge-queue membership (never closed as owner-gone), and this reason/context.
       |HOW TO WAKE IT (continues in the SAME session — history in place, finished work is not redone): mail the continuation address `delegate:$delegateId` with one instruction line, or click 唤醒 / Wake on the background-agents panel. The engine does NOT retry on its own — freezing is where it stops; the wake is the retry.
       |
       |【冻结回执】delegate #$delegateId（$description）因${reasonLabelZh(reason)}被**冻结**——这不是失败判词。原因码 `${wire(
        reason
      )}`，详情：$why。保留：完整会话历史、工作区/worktree $ws 与未提交改动、台账行（status=frozen）、合并队列成员资格、失败原因与最后错误上下文。唤醒 = 给 `delegate:$delegateId` 发一条 Mail（或面板点「唤醒」）——**在原会话上下文上继续**，已完成的不重做；引擎不自动重试。""".stripMargin

  end receiptText

  /**
   * 唤醒横幅（注入被唤醒会话的首条 UserInput 前缀）：说明「你在冻结点被唤醒、
   * 原因 X、已完成的不重做、从断点接着做」，随后才是唤醒者的指令正文。
   */
  def wakeBanner(delegateId: String, reasonLine: String, detail: String): String =
    val reason = parse(reasonLine)
    val whyZh = reason.map(reasonLabelZh).getOrElse(if reasonLine.trim.isEmpty then "未知" else reasonLine)
    val whyEn = reason.map(reasonLabelEn).getOrElse(if reasonLine.trim.isEmpty then "unknown" else reasonLine)
    val d = compactDetail(detail, 200)
    s"""[frozen-wake] You were FROZEN at this point (delegate #$delegateId, reason: $whyEn${
        if d.nonEmpty then s" — $d" else ""
      }) and have just been woken.
       |Your full history is in place: continue from the freeze point — do NOT redo what is already done, and do NOT re-plan from scratch.
       |【冻结唤醒】你在冻结点被唤醒（delegate #$delegateId，原因：$whyZh${
        if d.nonEmpty then s"——$d" else ""
      }）。完整历史在场：从冻结点继续，已完成的不重做。""".stripMargin

  // ── 冻结落盘（单写点）────────────────────────────────────────────────

  /** 台账行状态值（`SubAgentTask.status` 词表扩值；boot 收殓视野**不含**它）。 */
  val TaskStatus: String = "frozen"

  /**
   * 冻结单写：registry 冻结槽（`frozenSince/frozenReason/frozenDetail/frozenTrigger`）
   * ＋ 台账行 `status=frozen`（lastError = 原因详情，面板可见）。返回冻结时刻（registry 无
   * 记录时 None——只对真实 spawn 生效，与 `parkStandby` 同纪律）。
   *
   * @param trigger 触发源线值（第二波补详）：哪条腿/哪个事件/哪个看门狗触发本次冻结
   *                （`failed-event` / `cancelled-by-quota` / `managed-workspace-stopped` /
   *                `supervision-exhausted` / `respawn-failed`）——落 `Record.frozenTrigger`
   *                结构化槽；空 = 不写该槽（旧调用形态零变化）。
   *
   * fail-soft：任一侧写失败只 WARN，绝不阻断监督者冻结腿的其余动作。
   */
  def park(
    resources: AgentRuntimePort,
    delegateId: String,
    parentSessionId: String,
    reason: Reason,
    detail: String,
    trigger: String = ""
  ): IO[Option[Long]] =
    val d = compactDetail(detail)
    val registryWrite = IO
      .blocking(DelegateRegistry.parkFrozen(delegateId, detailedWireValue(reason, d), d, trigger))
      .attempt
      .flatMap {
        case Right(Right(ts)) => IO.pure(Option(ts))
        case Right(Left(err)) => logger.warn(s"[delegate-freeze] registry park skipped for $delegateId: $err").as(None)
        case Left(e) => logger.warn(s"[delegate-freeze] registry park failed for $delegateId: ${e.getMessage}").as(None)
      }
    val rowWrite =
      if parentSessionId.trim.isEmpty then IO.unit
      else
        resources.subAgentTaskStore
          .updateStatus(
            parentSessionId,
            delegateId,
            TaskStatus,
            lastError = Some(s"[frozen:${wire(reason)}] $d")
          )
          .handleErrorWith(e =>
            logger.warn(s"[delegate-freeze] task row write failed for $delegateId: ${e.getMessage}")
          )
    registryWrite <* rowWrite

  end park

  /**
   * 唤醒后收口（Mail 唤醒 / 面板唤醒 / 同 id 复活三腿共用）：清冻结槽 + 审计行 +
   * 合并队列重排（道净后本成员自动重排，无留队僵尸）+ **状态变更帧**。fail-soft。
   *
   * 冻结状态两套真相批（2026-11-05 10:42 报障「面板说要唤醒、点了说不需要」）：
   * 本动作是**唯一**把两侧冻结槽清空的地方，因此也是唯一必须发帧的地方——旧版没有
   * `wsSend` 参数，Mail 唤醒腿清完槽一声不响，面板那行的冻结标记（客户端单调累积）
   * 就永久停在旧值。现在无论唤醒来自面板、Mail 还是自动恢复，唤醒瞬间都推一帧
   * `delegateWoken`（含 sessionId / 新状态 / via / 时刻 / 冻结块），面板据此即时收起
   * 「需唤醒」，**不需要手动刷新**。
   */
  def onWoken(
    resources: AgentRuntimePort,
    delegateId: String,
    via: String,
    wsSend: Option[Json => IO[Unit]] = None
  ): IO[Unit] =
    for
      _ <- IO
        .blocking(DelegateRegistry.clearFrozen(delegateId))
        .attempt
        .flatMap {
          case Right(Right(())) => IO.unit
          case Right(Left(err)) => logger.warn(s"[delegate-freeze] clearFrozen skipped for $delegateId: $err")
          case Left(e) => logger.warn(s"[delegate-freeze] clearFrozen failed for $delegateId: ${e.getMessage}")
        }
      // registry 侧状态归位（冻结腿把它写成 Frozen）：只清**本批线值**的冻结
      // （时间表冻结 `schedule` 等其它冻结族的 reason 不动 ⇒ 解冻闸仍归 FreezeScheduler），
      // 状态回 Idle（面板徽标收起；下一回合 Processing 由既有 touch 面刷新）。
      _ <- resources.agentRegistry
        .update { m =>
          m.get(delegateId) match
            case Some(rec) if rec.status == nebflow.actor.AgentStatus.Frozen && rec.frozenReason.exists(isFreezeWire) =>
              m.updated(delegateId, rec.copy(status = nebflow.actor.AgentStatus.Idle, frozenReason = None))
            case _ => m
        }
        .handleErrorWith(e =>
          logger.warn(s"[delegate-freeze] registry status reset failed for $delegateId: ${e.getMessage}")
        )
      // 台账行归位（frozen → running）：唤醒即新执行窗口，行必须诚实地在飞——
      // 否则 boot 收殓视野与面板投影都会把跑着的会话当冻结遗留。
      _ <- IO
        .blocking(DelegateRegistry.find(delegateId))
        .map(_.map(_.parentSessionId).getOrElse(""))
        .flatMap { parentSid =>
          if parentSid.trim.isEmpty then IO.unit
          else
            resources.subAgentTaskStore
              .updateStatus(parentSid, delegateId, "running")
              .handleErrorWith(e =>
                logger.warn(s"[delegate-freeze] task row wake write failed for $delegateId: ${e.getMessage}")
              )
        }
      _ <- IO
        .blocking(
          ChainStore.appendSync(
            ChainStore.keyFor(None, delegateId),
            "delegate-frozen-wake",
            Json.obj(
              "action" -> "woken".asJson,
              "delegateId" -> delegateId.asJson,
              "via" -> via.asJson
            )
          )
        )
        .handleErrorWith(e => logger.warn(s"[delegate-freeze] wake audit row failed for $delegateId: ${e.getMessage}"))
      // 状态变更帧（冻结状态两套真相批 2026-11-05）：三腿共用、**无一例外**。
      // 面板据此把「需唤醒」即时收起（帧里带 sessionId / 新状态 / via / 时刻 /
      // 非冻结的 freeze 块）；缺席 = 面板永远停在旧值（本轮报障的直接成因）。
      _ <- wsSend.fold(IO.unit) { send =>
        IO.blocking(DelegateRegistry.find(delegateId))
          .map(_.map(_.parentSessionId).getOrElse(""))
          .handleErrorWith(_ => IO.pure(""))
          .flatMap { rootSid =>
            val at = System.currentTimeMillis()
            send(
              Json.obj(
                "type" -> "delegateWoken".asJson,
                "sessionId" -> delegateId.asJson,
                "rootSessionId" -> rootSid.asJson,
                "via" -> via.asJson,
                "status" -> "running".asJson,
                "at" -> at.asJson,
                "freeze" -> nebflow.actor.FreezeTruth.running("running", at).json
              )
            ).handleErrorWith(e =>
              logger.warn(s"[delegate-freeze] delegateWoken frame failed for $delegateId: ${e.getMessage}")
            )
          }
      }
      _ <- logger.info(s"[delegate-freeze] $delegateId woken via $via (frozen slot cleared)")
    yield ()

  /**
   * 冻结态的 registry 侧写法（监督者冻结腿调用）：status=Frozen +
   * frozenReason=本批线值。fail-soft。
   */
  def markRegistryState(resources: AgentRuntimePort, delegateId: String, reason: Reason): IO[Unit] =
    resources.agentRegistry
      .update { m =>
        m.get(delegateId) match
          case Some(rec) =>
            m.updated(
              delegateId,
              rec.copy(status = nebflow.actor.AgentStatus.Frozen, frozenReason = Some(wireValue(reason)))
            )
          case None => m
      }
      .handleErrorWith(e =>
        logger.warn(s"[delegate-freeze] registry state write failed for $delegateId: ${e.getMessage}")
      )

  /** record 是否处于冻结态（面板/看护判据特意**只读 registry 记录**，零进程态依赖）。 */
  def registryFrozenReason(resources: AgentRuntimePort, delegateId: String): IO[Option[String]] =
    resources.agentRegistry.get
      .map(_.get(delegateId).flatMap(_.frozenReason).filter(isFreezeWire))
      .handleErrorWith(_ => IO.pure(None))

  // ── 兜底：长期无人唤醒（面板提示 + 保留期归档）──────────────────────────

  /** 冻结龄阶段（纯函数；[[dormantSweep]] 的判据单点）。 */
  enum Stage:
    /** 冻结中，未到提示窗。 */
    case Active

    /** 长期无人唤醒——面板提示（保留期未到）。 */
    case Dormant

    /** 超保留期——归档 registry 记录（历史窗仍可查），台账行留痕。 */
    case Expired

  /**
   * 冻结龄判读（纯函数，spec 判红锚）：`age < remindMs` ⇒ Active；
   * `remindMs ≤ age < retentionMs` ⇒ Dormant；`age ≥ retentionMs` ⇒ Expired。
   * `retentionMs ≤ 0` = 保留期关闭（永不归档，只有提示）。
   */
  def staleDormant(frozenSince: Long, now: Long, remindMs: Long, retentionMs: Long): Stage =
    val age = math.max(0L, now - frozenSince)
    if retentionMs > 0 && age >= retentionMs then Stage.Expired
    else if age >= remindMs then Stage.Dormant
    else Stage.Active

  /** 缺省提示窗（ms）：`Defaults.DelegateFreezeDormantRemindMs`（缺省 24h）。 */
  def dormantRemindMs: Long = Defaults.DelegateFreezeDormantRemindMs

  /** 缺省保留期（ms）：`Defaults.DelegateFreezeRetentionDays`（缺省 14 天；0 = 关闭归档）。 */
  def retentionMs: Long = Defaults.DelegateFreezeRetentionDays * 24L * 3600L * 1000L

  /** 进程内提示/归档去重（重启后重发一次提醒 = 无害；归档本身幂等）。 */
  private val reminded = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  /**
   * 长期冻结兜底扫描（挂 `FreezeScheduler` 的既有 30s 拍，零新增定时器）：
   * 逐条冻结记录 → [[staleDormant]] 判读 → Dormant 一次面板提示
   * （`delegateFrozenDormant` 帧 + WARN）、Expired 归档（`DelegateRegistry.archive`
   * 语义：只加 `archivedAt` 旗，记录/子代理记账/转录/worktree 原样留存——历史窗
   * 仍可查）。全链 fail-soft：扫描器必须活着。
   */
  def dormantSweep(resources: AgentRuntimePort, wsSend: Option[Json => IO[Unit]]): IO[Unit] =
    if !Defaults.DelegateFreezeEnabled then IO.unit
    else
      IO.blocking(DelegateRegistry.list()).attempt.flatMap {
        case Left(e) =>
          logger.warn(s"[delegate-freeze] dormant sweep registry read failed: ${e.getMessage}")
        case Right(records) =>
          val now = System.currentTimeMillis()
          val remind = dormantRemindMs
          val retain = retentionMs
          records.filter(_.frozenSince.isDefined).traverse_ { rec =>
            val since = rec.frozenSince.getOrElse(now)
            staleDormant(since, now, remind, retain) match
              case Stage.Active => IO.unit
              case Stage.Dormant =>
                if reminded.putIfAbsent(s"dormant:${rec.id}", java.lang.Boolean.TRUE) == null then
                  val ageH = (now - since) / 3600000L
                  logger.warn(
                    s"[delegate-freeze] delegate '${rec.id}' has been frozen for ${ageH}h awaiting a wake " +
                      s"(reason=${rec.frozenReason.getOrElse("?")}, workspace=${rec.cwd}) — mail `delegate:${rec.id}` or use the panel wake action"
                  ) *> wsSend.fold(IO.unit) { send =>
                    send(
                      Json.obj(
                        "type" -> "delegateFrozenDormant".asJson,
                        "sessionId" -> rec.id.asJson,
                        "rootSessionId" -> rec.parentSessionId.asJson,
                        "frozenSince" -> since.asJson,
                        "frozenReason" -> rec.frozenReason.getOrElse("").asJson,
                        "ageHours" -> ageH.asJson,
                        "retentionHours" -> (retain / 3600000L).asJson
                      )
                    ).handleErrorWith(_ => IO.unit)
                  }
                else IO.unit
              case Stage.Expired =>
                if reminded.putIfAbsent(s"expired:${rec.id}", java.lang.Boolean.TRUE) == null then
                  val ageD = (now - since) / 86400000L
                  logger.warn(
                    s"[delegate-freeze] frozen delegate '${rec.id}' exceeded the ${Defaults.DelegateFreezeRetentionDays}-day retention " +
                      s"(frozen ${ageD}d, reason=${rec.frozenReason.getOrElse("?")}) — archiving the registry record " +
                      "(transcript/worktree/task row are untouched and stay queryable via archived=1)"
                  ) *> IO
                    .blocking(DelegateRegistry.archive(rec.id))
                    .attempt
                    .flatMap {
                      case Right(_) => IO.unit
                      case Left(e) => logger.warn(s"[delegate-freeze] archive failed for ${rec.id}: ${e.getMessage}")
                    } *> resources.subAgentTaskStore
                    .updateStatus(
                      rec.parentSessionId,
                      rec.id,
                      TaskStatus,
                      lastError = Some(
                        s"[frozen:expired] frozen for ${ageD}d, no wake — registry record archived after retention; transcript/worktree kept"
                      )
                    )
                    .handleErrorWith(_ => IO.unit)
                else IO.unit
            end match
          }
      }

end DelegateFreeze
