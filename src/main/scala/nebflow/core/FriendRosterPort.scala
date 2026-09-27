/* 严格DAG第⑥步第二批:FriendRoster 的解析/候选文案用面倒置为注册器(行为保持,2026-09-27)。 */
package nebflow.core

import nebflow.core.tools.ToolError
import nebflow.shared.{FriendSummary, GroupSummary}

import java.util.concurrent.atomic.AtomicReference

/**
 * `neblink.FriendRoster` 的窄投影(严格DAG第⑥步第二批 R10):FriendRoster 留驻
 * neblink(依赖 core.tools.ToolError,属好友域词汇单点),core 侧(ListFriendsTool 的
 * 名册行/空表提示、FriendMessageTool 的好友/群目标解析)经本注册器调用。四函数面
 * 签名与 `FriendRoster` 对应成员逐字一致(类型引用已下沉 shared 的
 * FriendSummary/GroupSummary);neblink boot 注册对应函数(注册点 = object
 * FriendRoster 对象初始化行,生产 boot 与测试直接引用该对象的 spec 共用;另有
 * NeblinkWiring 的统一 boot 段落幂等再注册,见 R12 接线说明)。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):注册器倒置,签名镜像现实现,行为保持
object FriendRosterPort:

  /** 注册器持有的四函数面(resolve/resolveGroup/availableHint/candidateLine)。 */
  trait Face:
    def resolve(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary]
    def resolveGroup(query: String, groups: List[GroupSummary]): Either[ToolError, GroupSummary]
    def availableHint(fs: List[FriendSummary]): String
    def candidateLine(f: FriendSummary, remark: Option[String] = None): String
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  // 严格DAG第⑥步第二批裁定(2026-09-27):未注册兜底 = fail-cloud 显式异常(对齐
  // FilePolicyPort.port 的未接线语义;名册文案/解析没有可伪造的同型值,禁静默空串)。
  // 生产 boot(NeblinkWiring 段落)与 neblink 对象初始化双注册 ⇒ 未注册只在「neblink
  // 整包未装配且统一 boot 段落未跑」的极端早启窗口可达。
  private def port: Face = face.get match
    case Some(f) => f
    case None =>
      throw new IllegalStateException(
        "FriendRosterPort 未注册(neblink boot 段落应注册 FriendRoster 四函数面)— friend roster face unavailable"
      )

  def resolve(query: String, friends: List[FriendSummary]): Either[ToolError, FriendSummary] =
    port.resolve(query, friends)

  def resolveGroup(query: String, groups: List[GroupSummary]): Either[ToolError, GroupSummary] =
    port.resolveGroup(query, groups)

  def availableHint(fs: List[FriendSummary]): String = port.availableHint(fs)

  def candidateLine(f: FriendSummary, remark: Option[String] = None): String = port.candidateLine(f, remark)

end FriendRosterPort
