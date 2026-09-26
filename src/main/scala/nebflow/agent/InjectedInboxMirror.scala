package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.{NebflowLogger, UiMessage}

/**
 * 子代理收件（卡08 裁点 2，作者 2026-09-20 07:33 批「建」）—— **投递侧增量**。
 *
 * == 缺口（诊断定谳）==
 * 注入族行按「谁收到就写谁的会话流」单一规则落盘（发射点
 * [[AgentActor#emitInjectedUserEvent]] 的 `appendUiMessages(sid, …)`，`sid` 恒 =
 * 发射者自身会话）。`mail` 族的收件方恒为主控（root）会话 ⇒ 现取读数
 * `mail`1179 行 **100% root 族、子代理族 0 行**（口径见诊断件
 * `.nebflow/evidence/20260920_064456_subagent-bubble-diag/`）⇒ 「子代理窗口无米下锅」。
 *
 * == 本件做什么 ==
 * 在同一发射点**追加一条镜像腿**：把该注入行的**同一份 UI 行**（逐字段同一实例，
 * 因而逐字节同形）**镜像写入相关子代理会话流**。渲染侧零改（四站点已能显示，
 * 见诊断 §5），本件只补投递侧。
 *
 * 🔴 **三条硬边界（设计决定，逐条可核）**
 *   ① **只落会话流，不投 agent**：镜像腿只调 `appendUiMessages`（UI 历史面），
 *      **不**向目标 actor 发任何 `AgentCommand` ⇒ 目标子代理的 LLM 上下文/回合
 *      语义/冻结判据**零变化**（禁改「其他 Actor 的会话流语义」的落点）；
 *      作者口径「镜像写其会话流」= `.ui.json` UI 流，非 `<id>.json` 消息流。
 *   ② **不产任何 WS 帧**：镜像腿零 `wsSend` ⇒ 主控窗口帧序列**逐字不变**
 *      （主控回归判据 by construction）。子代理窗口的显示走既有**历史拉取**路径
 *      （诊断位用例 C/A2：冷开/历史面四站点全绿），live 帧面归裁点 1（另批）。
 *   ③ **禁串流**：目标集由**注册表父子链**（`AgentRecord.rootSessionId`）判定，
 *      非黑名单排除；解析不到即**零写**（fail-closed，禁广播兜底）。
 *
 * == 路由表（事件族 → 目标子代理会话流；验收对象）==
 * 逐行见批报告「路由表」节；要点：
 *   · `mail` / `mail-queue` ⇒ **镜像**（本件 `MirrorSources`，唯一白名单来源）；
 *   · 其余族 ⇒ **豁免**（理由：子代理族**本就原生持行**，诊断读数逐族在案：
 *     tool 2597 / system 881 / delegate 426 / subtask 38 / task 1717 / dispatch 898 /
 *     flow 16 / background 380 —— 镜像只会把同一事件族在其中复制一份 = 纯噪声）；
 *   · `node`（节点完成通报投根）与 `ask`（AskUser 答复腿，root-only 语义）⇒ 豁免，
 *     理由见报告（前者「节点自身终态帧已在自身窗口原生可见」，后者 `AskMode.NonBlocking`
 *     结构性 root-only）。
 */
object InjectedInboxMirror:

  private val logger = NebflowLogger.forName("nebflow.agent.mirror")

  /** 镜像开关的 system property 名（变异/演练面；见 [[enabled]]）。 */
  val SwitchProp: String = "nebflow.subinbox.mirror"

  /** 镜像开关的环境变量名（fork 的 JVM 自动继承 ⇒ 演练首选本形）。 */
  val SwitchEnv: String = "NEBFLOW_SUBINBOX_MIRROR"

  /** 置为开关「关」的取值（大小写与首尾空白不敏感）。 */
  val OffValues: Set[String] = Set("off", "false", "0")

  /**
   * 镜像开关（**默认开** = 默认交付形态）。
   *
   * 读法 = 先 system property 后环境变量（两者**只读**，不写任何配置面）。取值 ∈
   * [[OffValues]] ⇒ 关（其余一切取值 = 开）。存在理由 = 验收判据 5「变异验红」需要
   * 一个**已知会命中的反向变异面**：关掉开关 ⇒ 子代理侧镜像行必须重新归零
   * （证明读数真在看镜像面，而不是在看别的历史残留）。
   */
  def enabled: Boolean =
    val raw = sys.props.get(SwitchProp).orElse(sys.env.get(SwitchEnv))
    !raw.exists(v => OffValues.contains(v.trim.toLowerCase))

  /**
   * 镜像族白名单（**唯一来源**，禁副本）。判据 = 「该族在子代理会话流中**原生零行**」
   * （缺口族）而非「所有注入族」——全镜像案已被否决，副作用分析见批报告 §污染面。
   *   mail        发射点 source = `"mail"`（MailTool#sendMail，腿③ / team 腿）
   *   mail-queue  legacy 队列排空腿的 source（`"mail-queue"` == mail 族同源）
   */
  val MirrorSources: Set[String] = Set("mail", "mail-queue")

  /**
   * 子代理会话 id 前缀（**与前端 `web/js/utils.js#isBgAgentId` 逐值同源**；本件是
   * 后端侧唯一副本，契约门 [[nebflow.agent.SubAgentInboxMirrorSpec]] 两侧逐字对账）。
   * 判据 = 「该会话在 UI 里由 bg-agent 弹窗承载」⇒ 与作者口径的「子代理窗口」
   * 同外延（`team-` 前缀**不在**内：`isBgAgentId` 不含它，其窗口不属于本批面）。
   */
  val SubAgentIdPrefixes: List[String] = List("delegate-", "subtask-", "node-", "dispatcher-")

  /** 是否子代理会话 id（前缀判据见 [[SubAgentIdPrefixes]]）。 */
  def isSubAgentSession(sessionId: String): Boolean =
    sessionId.nonEmpty && SubAgentIdPrefixes.exists(sessionId.startsWith)

  /** 是否镜像族（白名单判据见 [[MirrorSources]]）。 */
  def isMirrorSource(source: String): Boolean =
    MirrorSources.contains(source)

  /**
   * **纯路由**（无 IO、无注册表依赖 ⇒ 可直测）：给定发射者会话、事件族与注册表候选集
   * （`sessionId -> rootSessionId`），返回镜像目标会话 id 列表。
   *
   * 相关度判据（Ⓒ，逐条可核）：
   *   μ-1 **sessionId**（自身）：发射者自身会话由**既有**落盘腿承担，本函数**恒排除**
   *       （`sid != emitterSid`）⇒ 同一事件在**同一**窗口**永不重复**（判据 6）。
   *   μ-2 **parent 链**（`AgentRecord.rootSessionId`）：目标必须满足
   *       `rootSessionId == emitterSid` —— 即该子代理**归属**本次发射所在的主控/根会话。
   *       跨 root / 跨项目的子代理**结构上不可能命中**（禁串流到无关窗口）。
   *   μ-3 **sessionId 族前缀**：目标必须是 [[SubAgentIdPrefixes]] 之一（= UI 弹窗承载面）。
   *   歧义/不可判定 ⇒ **不镜像**（本函数只认这三点，缺一即弃）。
   *
   * 结果**去重且字典序稳定**（同一次调用对同一目标只写一行；顺序确定 ⇒ 可复算）。
   */
  def targets(
    emitterSid: String,
    source: String,
    candidates: Iterable[(String, String)]
  ): List[String] =
    if emitterSid.isEmpty || !enabled || !isMirrorSource(source) || isSubAgentSession(emitterSid) then Nil
    else
      candidates.iterator
        .collect {
          case (sid, root) if sid != emitterSid && root == emitterSid && isSubAgentSession(sid) => sid
        }
        .toList
        .distinct
        .sorted

  /** 追加写入面（唯一 IO 依赖）：目标会话 id + 待写行 ⇒ 落盘。 */
  type Append = (String, UiMessage.User) => IO[Unit]

  /**
   * 镜像落盘腿（发射点调用）：解析目标集后**逐目标**写入同一份 UI 行。
   *
   * 单目标失败**不**连坐其余目标、也**不**向上抛（返回已完成的目标清单供审计/测试）——
   * 与既有落盘腿同款 `handleErrorWith` 纪律（注入路径永不因落盘失败而中断 turn）。
   * `append` 以显式参数注入（**不**在内部直取 `SharedResources.sessionStore`）：
   * 使本件可在无 actor 系统/无 SharedResources 的测试里用真 `SessionStore`（临时目录）
   * 直测落盘形态，且把依赖面收窄到「一个函数」。
   */
  def mirror(
    candidates: Iterable[(String, String)],
    emitterSid: String,
    source: String,
    ui: UiMessage.User,
    append: Append
  ): IO[List[String]] =
    val tgts = targets(emitterSid, source, candidates)
    tgts.traverse { t =>
      append(t, ui)
        .handleErrorWith(e => logger.warn(s"injected inbox mirror failed: target=${t.take(32)} err=${e.getMessage}"))
        .as(t)
    }
end InjectedInboxMirror
