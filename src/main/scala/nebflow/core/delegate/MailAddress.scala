package nebflow.core.delegate

import nebflow.actor.AgentKind

/**
 * Mail 地址形（**单一推导点**，2026-11-05「AgentControl list 返回批次 name 与续传地址」批）。
 *
 * 起因（作者令 2026-11-05 11:15 逐字）：「我觉得你的agentcontrol工具应该要能返回在跑的
 * delegate 的name 和地址，方便你讲任务投递到相关的delegate」——编排方要投递补充信息时
 * 过去得自己拼 `delegate:<id>`（或翻派发回执），拼错 = 投错人。
 *
 * 三条纪律（本文件成立的全部理由）：
 *  ① **形态必须与 Mail 解析面逐字同形**：读面（AgentControl list/status、后台代理面板行）
 *    给出的字符串必须能**原样粘进 `Mail(address=…)`**。故前缀常量在本文件出生，
 *    MailTool 的私有前缀常量改为引用此处（同一个字符串只有一份源）。
 *  ② **只按记录推导，绝不反猜**：入参 = 注册表记录的身份面（kind + sessionId + 节点名），
 *    不从任务摘要/标题里猜 id。推不出 = `None`（读面显示占位 `-`，不编造）。
 *  ③ **不可投递的 kind 一律 None**：Root / Team / Ephemeral / SubTask / 旧 dag-* Flow
 *    会话没有 Mail 面（Mail 侧会显式拒绝），给一个「看着像」的地址 = 教人投错。
 *
 * 三类形态（与 MailTool.layeredRoute 的腿一一对应）：
 *  - `delegate:<sessionId>`：Delegate 批次续聊（`deliverToDelegate`；要求 DelegateRegistry
 *    记录在场 + 会话 live，派发回执里的续传地址就是这一形）。
 *  - `kernel:<sessionId>`：kernel 实例（session id 前缀 `delegate-kernel-`，与
 *    `AgentActor.isKernelSession` 同判据）。Mail 侧 `kernel:` 与 `delegate:` 是**同一腿**
 *    （存量回执别名），此处按 kind 给更精确的那一形。
 *  - `node:<节点id或名>`：Project 节点会话（session id 前缀 `node-`）。节点会话 id 是
 *    `node-<uuid>`，**不含节点 id**——ref 只能取自注册表记录的 displayName（节点名，
 *    注册点写入，`Mail` 的 node 腿按 id 或名双寻址），故 nodeName 缺席 ⇒ None。
 *  - `ask:<sessionId>`：canvas ask 会话（session id 前缀 `ask-`，ask-mail 批
 *    2026-11-16）。会话身份归 ask 绑定表（`AskSessionRegistry`）——Mail 侧按
 *    id 直达（live 注入 / 不 live 落会话邮箱），授权面 root-only（与 delegate
 *    续聊同面）。
 */
object MailAddress:

  /** 续聊地址前缀（与派发回执逐字同形）。 */
  val DelegatePrefix = "delegate:"
  val KernelPrefix = "kernel:"
  val NodePrefix = "node:"

  /**
   * canvas ask 会话地址前缀（ask-mail 批 2026-11-16，作者令「给 ASK · INJECTED 加上
   * 地址，使编排方可通过 Mail 续聊」）：`ask:` + **完整会话 id**（即 `ask:ask-<8hex>`，
   * 前缀自叠与 `kernel:delegate-kernel-<uuid>` 同形——「前缀 + sessionId 零变换」
   * 单一规则，不造第二种 id 形）。
   */
  val AskPrefix = "ask:"

  /** kernel 实例会话 id 前缀（判据同 `AgentActor.isKernelSession`）。 */
  val KernelSessionPrefix = "delegate-kernel-"

  /** Project 节点会话 id 前缀（权威常量在 NodeEngine，此处只做别名）。 */
  val NodeSessionPrefix: String = nebflow.core.project.NodeEngine.SessionPrefix

  /** ask 会话 id 前缀（权威常量在 AskSessions，此处只做别名——mail 面单源纪律）。 */
  val AskSessionPrefix: String = nebflow.core.ask.AskSessions.Prefix

  /** 地址种类（= Mail 的续聊/投递腿）。 */
  enum Form(val prefix: String, val wire: String):
    case Delegate extends Form(DelegatePrefix, "delegate")
    case Kernel extends Form(KernelPrefix, "kernel")
    case Node extends Form(NodePrefix, "node")
    case Ask extends Form(AskPrefix, "ask")

  /** 解析结果：种类 + 目标 ref（前缀之后的部分，逐字保留）。 */
  final case class Parsed(form: Form, target: String):
    /** 规范形态（`parse(x).map(_.wire) == x` 对任何合法地址成立）。 */
    def wire: String = form.prefix + target

  private def nonEmpty(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)

  /**
   * 解析一个已知形态的 Mail 地址（**只认出本对象产出的三类**，不猜、不模糊匹配——
   * 与 Mail 的「无静默兜底」同纪律）。未知形态 → None。
   */
  def parse(address: String): Option[Parsed] =
    nonEmpty(address).flatMap { a =>
      if a.startsWith(DelegatePrefix) then nonEmpty(a.stripPrefix(DelegatePrefix)).map(Parsed(Form.Delegate, _))
      else if a.startsWith(KernelPrefix) then nonEmpty(a.stripPrefix(KernelPrefix)).map(Parsed(Form.Kernel, _))
      else if a.startsWith(NodePrefix) then nonEmpty(a.stripPrefix(NodePrefix)).map(Parsed(Form.Node, _))
      else if a.startsWith(AskPrefix) then nonEmpty(a.stripPrefix(AskPrefix)).map(Parsed(Form.Ask, _))
      else None
    }

  /**
   * 由注册表身份面推导 Mail 地址（None = 本 kind 无 Mail 面，或 ref 不足）。
   *
   * @param kind      AgentRecord.kind
   * @param sessionId AgentRecord.sessionId（kernel 实例也是 Delegate kind，靠前缀分辨）
   * @param nodeName  AgentRecord.displayName（仅 node- 会话用：Project 节点名）
   */
  def of(kind: AgentKind, sessionId: String, nodeName: Option[String] = None): Option[String] =
    nonEmpty(sessionId).flatMap { sid =>
      if sid.startsWith(KernelSessionPrefix) then Some(KernelPrefix + sid)
      else if sid.startsWith(AskSessionPrefix) then Some(AskPrefix + sid)
      else if kind == AgentKind.Delegate then Some(DelegatePrefix + sid)
      else if kind == AgentKind.Flow && sid.startsWith(NodeSessionPrefix) then
        nodeName.flatMap(nonEmpty).map(NodePrefix + _)
      else None
    }

end MailAddress
