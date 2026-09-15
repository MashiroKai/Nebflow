package nebflow.core.project

import java.util.Locale

/**
 * 注入气泡顶栏（浅蓝注入气泡 header）的**单点格式化函数**——
 * 「气泡四段式统一」批（作者 2026-09-15 12:33 令；引擎面）。
 *
 * ## 形态（作者裁定，逐字）
 * 四段式 `KIND · PROJECT · SUBJECT · STATE`：
 *   - `KIND`    — 注入来源类别（后端自定名 source 词表，见 [[KindLabels]]），**全大写**；
 *   - `PROJECT` — **发送方所属项目名**（跨 root 直投件取 [[RootProject]]）；
 *   - `SUBJECT` — **链名 / 节点名 / 对端地址**（NODE 腿 = 节点名，CHAIN 腿 = 链 id，
 *                Mail 腿 = 发送方 agent 名，Team 消息 = `team/agent`）；
 *   - `STATE`   — 生命周期态或 Mail 消息类型（见 [[StateLabels]]），**全大写**。
 * 分隔符一律 [[Sep]]；段值统一 trim + 全大写（`Locale.ROOT`）；**空段跳过**且不产生
 * 双分隔符 / 首尾分隔符（旧历史行缺 sender/eventType 时优雅降级）。
 *
 * ## 为什么落在引擎侧（本批的目标形态）
 * 考古（`.nebflow/reports/20260915_evfmt-impl.md` §1.1）现取结论：**引擎侧原本零拼接**
 * （`grep 'NODE · |CHAIN · |MAIL · ' src/main/scala` 只命中两处文档注释），header 由
 * 前端 `web/js/chat.js#injectedSourceLabel` **自拼**（`:422` / `:438` 两处
 * `parts.join(' · ')`，且通用分支只有 3 段、KIND/STATE 为 TitleCase）。
 * ⇒ 目标形态 = **引擎单一来源、客户端只渲染**：本函数在**唯一发射点**
 * `AgentActor#emitInjectedUserEvent`（`grep '"injected" -> true` 单命中）被调用一次，
 * 产出的 header 同时进 WS 帧（前端逐字渲染）与落盘（历史恢复行同源）——
 * 覆盖 [[KindLabels]] 全部源，**无需逐源改造**，且完全绕开在飞的 `NodeEngine.scala`。
 *
 * ## 覆盖与回落（禁臆造 / 禁静默填空）
 *   - `header(...)` 的 source 不在 [[KindLabels]] 词表内 ⇒ 返回 `None` ⇒ **帧不带
 *     `header` 键** ⇒ 前端回落既有 `injectedSourceLabel`（逐字节不变）。这是刻意
 *     留的缝：在飞批新增的源（如 device-mail 批的 `deviceMail`，见交付报告
 *     「未纳入面」）由其自己的前端显式分支渲染，本函数不替它决定形态。
 *   - `PROJECT` 段的取值链（发送方项目 → 本项目 → [[RootProject]]）——**逐级落位以事实
 *     为准**（r3 更正：本行原称「链首级由构造点置位（`MailTool.mailAttribution`）」，与
 *     交付面不符）：
 *     ① **构造点显式置位** = `MailTool.mailAttribution` 取 `ToolContext.projectName`
 *        ——🔴 **当前未落位**（该文件属批 B 在飞写面）；NODE/CHAIN 腿**不经本字段**，
 *        其 `PROJECT` 由 `sender` 前缀 `"<项目名>/<节点名|链id>"` 切分（本函数内实现，
 *        不依赖置位）；
 *     ①′ **Mail 腿①（`Mail → project` 分发器收件面）** 由发射面
 *        `ProjectActor.leg1SenderProject` 在 ① 缺席时取**根域** [[RootProject]]
 *        （该腿发送方无项目上下文 = 「跨 root 直投件」；**不**取收件方项目）；
 *     ② 本项目 = 发射点 `AgentActor#emitInjectedUserEvent` 的 `sessionProject`（接收会话
 *        所属项目）；
 *     ③ [[RootProject]] = 前级皆空。
 *     逐处落位与原始读数登记在交付报告 §r3-2 / §r2-C（r3 更正节）。
 *
 * ## 与前端词表的关系
 * [[KindLabels]] 的**键集恒 = [[nebflow.agent.InjectionAttribution.BackendNamedSources]]**
 * （后端自定名源的唯一定义），由 `NotificationHeaderSpec` 硬门守住——后端加新源而本表
 * 落后即红（同 `InjectionSourceContractSpec` 对前端表的纪律）。
 */
object NotificationHeader:

  /** 段分隔符：一律 ` · `（U+00B7，两侧各一个半角空格）。 */
  val Sep: String = " · "

  /** 根域项目名：回落链末级「跨 root 直投件」的项目段取值（作者裁定逐字 `NEBULA`，
    * 经全大写归一后即此字面量）。 */
  val RootProject: String = "Nebula"

  /** KIND 词表（后端自定名 source → 四段式第 1 段）。**键集 = 后端自定名源全集**
    * （`InjectionAttribution.BackendNamedSources`，由 spec 硬门守住）。
    *
    * 取值口径 = 前端既有的 `INJECTED_SOURCE_LABELS` 显示标签**全大写**
    * （`mail→Mail→MAIL` 等；`subtask` 的既有标签 `SubTask` ⇒ `SUBTASK`）——
    * 不新造命名，只做大小写归一，使「KIND 含 CHAIN/NODE/MAIL 及其余源一律纳入」
    * 成为机械可核的覆盖关系。 */
  val KindLabels: Map[String, String] = Map(
    "mail" -> "MAIL",
    "task" -> "TASK",
    "dispatch" -> "DISPATCH",
    "system" -> "SYSTEM",
    "node" -> "NODE",
    "skill" -> "SKILL",
    "delegate" -> "DELEGATE",
    "subtask" -> "SUBTASK",
    "flow" -> "FLOW",
    "tool" -> "TOOL",
    "background" -> "BACKGROUND",
    "chain" -> "CHAIN"
  )

  /** STATE 词表（eventType → 第 4 段）。来源 = 前端 `NODE_STATUS_LABELS`
    * （`chat.js:381-387`，NODE 专用：`COMPLETED` / `FAILED` / `CANCELED`）∪
    * `EVENT_TYPE_LABELS`（`chat.js:372-377`，通用面）——两表**引擎侧同源副本**，
    * 语义逐字保持（注意 `cancelled → CANCELED` 这一个字母的历史口径不得漂移）。
    * 表外 eventType ⇒ 原样全大写（旧前端的兜底口径，逐字保持）。 */
  val StateLabels: Map[String, String] = Map(
    "completed" -> "COMPLETED",
    "failed" -> "FAILED",
    "cancelled" -> "CANCELED",
    "crashed" -> "CRASHED",
    "trigger" -> "TRIGGERED",
    "inject" -> "INJECTED",
    "info" -> "INFO",
    "result" -> "RESULT",
    "interrupt" -> "INTERRUPT",
    "follow_up" -> "FOLLOW-UP",
    "parallel" -> "PARALLEL"
  )

  private def up(s: String): String = s.trim.toUpperCase(Locale.ROOT)

  /** 四段组装（纯函数）：分隔符一律 [[Sep]]，段值 trim + 全大写，**空段跳过**
    * （不产生双分隔符 / 首尾分隔符）。 */
  def render(kind: String, project: Option[String], subject: Option[String], state: Option[String]): String =
    List(kind, project.getOrElse(""), subject.getOrElse(""), state.getOrElse(""))
      .map(up)
      .filter(_.nonEmpty)
      .mkString(Sep)

  /** 由注入件的来源字段解出整条 header。
    *
    * @param source     注入来源（`InjectionAttribution` / `UserInput.source`）
    * @param intake     **呈现判别**字段（mailbadge 批）：与前端同序——有值即优先取它
    *                   作 KIND 键（`source` 的会计语义不受影响）
    * @param project    **发送方所属项目**（链首级；`None` ⇒ 由发射点走回落链）
    * @param sender     发送方标识：NODE/CHAIN 腿 = `"<项目名>/<节点名|链id>"`，
    *                   Mail/Team 腿 = 发送方 agent 名
    * @param senderTeam Team 名（Team 消息的 SUBJECT = `team/agent`，旧呈现口径逐字保持）
    * @param eventType  结构化事件类型 / Mail 类型
    * @return `None` = 该 source 不属 [[KindLabels]] 词表 ⇒ 帧不带 `header`，
    *         前端回落既有渲染（在飞批新增源不被本函数接管）。 */
  def header(
      source: String,
      intake: Option[String] = None,
      project: Option[String] = None,
      sender: Option[String] = None,
      senderTeam: Option[String] = None,
      eventType: Option[String] = None
  ): Option[String] =
    val key = intake.map(_.trim).filter(_.nonEmpty).getOrElse(source)
    KindLabels
      .get(key)
      .orElse(KindLabels.get(source))
      .map { kind =>
        val (proj, subj) = split(kind, project, sender, senderTeam)
        render(kind, proj, subj, eventType.map(stateLabel0))
      }

  /** NODE/CHAIN 腿：`sender` 是路径约定 `"<项目名>/<节点名|链id>"`（`NodeEngine`
    * `deliverToNebula` / `deliverChainSummary`）⇒ 切分即得 PROJECT + SUBJECT；
    * 无 `/` 的旧形态（缺段）⇒ PROJECT 走回落链、**SUBJECT 省略**（旧前端
    * `chat.js:410-411` 记录的降级口径 `NODE · <状态>` 逐字保持）。
    * 其余腿：SUBJECT = Team 组合名（`team/agent`，旧呈现逐字）或 sender。 */
  private def split(
      kind: String,
      project: Option[String],
      sender: Option[String],
      senderTeam: Option[String]
  ): (Option[String], Option[String]) =
    def nonEmpty(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)
    if kind == "NODE" || kind == "CHAIN" then
      sender.flatMap(nonEmpty) match
        case Some(s) =>
          val i = s.indexOf('/')
          if i > 0 && i < s.length - 1 then (nonEmpty(s.substring(0, i)), nonEmpty(s.substring(i + 1)))
          else (project.flatMap(nonEmpty), None)
        case None => (project.flatMap(nonEmpty), None)
    else
      val subj = senderTeam.flatMap(nonEmpty) match
        case Some(t) => sender.flatMap(nonEmpty) match
            case Some(s) => Some(s"$t/$s")
            case None    => Some(t)
        case None => sender.flatMap(nonEmpty)
      (project.flatMap(nonEmpty), subj)

  private def stateLabel0(eventType: String): String =
    StateLabels.getOrElse(eventType, eventType)

end NotificationHeader
