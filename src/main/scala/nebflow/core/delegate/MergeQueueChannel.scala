package nebflow.core.delegate

import io.circe.Json
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * MergeQueueChannel —— 合并队列的**write-ahead 通道**（R5 重启丢队修复 · 报告二
 * §4.3 / 设计稿 §1 P5 行；报告二建议「DeviceResultOutbox 同型可搬」照抄落地）。
 *
 * == 病灶（报告二 §4.3，B2 实证链）==
 *
 * [[MergeQueue]] 的账本落盘持久，但**投递通道是进程内运行态**（internal actor ref /
 * external 续聊闭包，`MergeQueue.Member.internal/external` 不持久）——每次引擎重启
 * 把未关账成员打成 owner-gone 人工处置；更早一层：ready-to-merge 之后、入账写盘
 * 之前被杀 ⇒ 连账本条目都没有（B2：13:00 判 ready 未入账，20:55 引擎重启，23:07
 * 人工 `--no-ff` 归队）。
 *
 * == 本对象是什么 ==
 *
 * 成员账面镜像的**先写面**：`MergeQueue` 在每次入账 / 状态迁移落内存（与持久账本）
 * **之前**先把成员镜像写进 `<dataRoot>/merge-channel/<delegateId>.json`（一条一文件，
 * 原地幂等覆写，AtomicJson 原子替换）。进程死在任意中间点，通道文件都已在盘上：
 * boot 时 [[MergeQueue]] 重放通道（`ensureLoaded` 尾）——账本缺的成员从通道收养、
 * 账本落后于通道的状态迁移从通道并回。语义 = **at-least-once（宁重勿丢）**：
 * 放行通知可能重投，幂等由合并指令自带的只读账实核对预检承担（已并 = 立即收尾）。
 *
 * 与既有面的分工：
 *   - **持久账本**（merge-ledger.json）：读面真源（面板/API/对账）——通道重放后
 *     账本仍是唯一读面，通道退回纯 write-ahead 镜像；
 *   - **DeviceResultOutbox**（跨设备件待发区）：同型机制、不同流量——本区只管
 *     本机合并队列成员资格的持久性，不投递任何件；
 *   - **ChainStore 审计行**：`channel-replayed` 等动作行照写（审计可对账）。
 *
 * == owner-gone 前提反转（R5 判定面）==
 *
 * 「通道丢失 ⇒ owner-gone」的判定**以本通道无未关账记录为前提**
 * （[[MergeQueue.notifyTurn]] 无通道腿 + MergeQueueBootSweep 对账腿）：通道里还有
 * 该成员的未关账镜像 = 成员资格在盘上，进程内存缺席（闭包/actor ref 随进程消亡）
 * **不再构成 owner-gone 依据**——成员保留（窗让回 → waiting），沿 BlockNotifyPort
 * 可行动通知（N 拍升级作者）。通道无记录（本批前的存量账面/直写账本）才照旧关账。
 *
 * == 设计红线 ==
 *
 *  - **零墙钟**：不设 TTL；条目只在关账后被压缩（事件/阈值驱动，见 [[compact]]）；
 *  - **落盘失败不阻断主路径**：写失败只 WARN（簿记面纪律，persistLocked 同款），
 *    队列内存路径照常推进——保证降级为 best-effort，与账本同水位；
 *  - **压缩留开放**：[[compact]] 只移除已关账（终态）镜像——账本已持终态审计行，
 *    通道副本冗余；未关账镜像永不被压缩腿删除（boot 对账的收养前提）。
 *
 * 存储：`<dataRoot>/merge-channel/<safeId(delegateId)>.json`，内容 = 成员镜像
 * （与账本行同形，`MergeQueue.memberJson` 输出——重放可直接构造 Member）。
 */
object MergeQueueChannel:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-channel")

  /** 一条通道镜像（`member` = 成员镜像全文，与账本行同形）。 */
  final case class Entry(delegateId: String, state: String, member: Json)

  /** 压缩阈值（事件驱动：终态镜像攒够此数才批量移除——careTick 拍尾检查，零定时器）。 */
  val CompactAfter: Int = 8

  private def dir: os.Path = PathUtil.dataRoot / "merge-channel"

  /** id 安全（作文件名）：非白名单字符一律替换，防路径穿越（DeviceResultOutbox 同款）。 */
  private def safeId(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '.' || c == '_' then c else '_')
    if cleaned.nonEmpty then cleaned.take(120) else "anon"

  private def fileOf(delegateId: String): os.Path = dir / s"${safeId(delegateId)}.json"

  /**
   * write-ahead 镜像写（**同一 delegateId 幂等原地覆写**——状态迁移覆盖前像）。
   * 调用纪律：必须在内存/账本写入**之前**（write-ahead 语义本体）。写失败只 WARN，
   * 绝不抛（调用方的内存路径照常继续）。
   */
  def sync(delegateId: String, state: String, member: Json): Unit =
    if delegateId.isEmpty then ()
    else
      try
        if !os.exists(dir) then os.makeDir.all(dir)
        AtomicJson.writeSync(fileOf(delegateId), member.noSpaces)
      catch
        case e: Throwable =>
          logger.warn(
            s"[merge-channel] sync failed for '$delegateId' (state=$state; memory path unaffected): ${Option(e.getMessage).getOrElse(e.toString).take(200)}"
          )

  /** 全量镜像（按 delegateId 字典序；坏文件跳过并 WARN——簿记坏件不阻断重放）。 */
  def readAll(): List[Entry] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.ext == "json")
          .toList
          .flatMap { p =>
            val parsed: Option[Entry] =
              try
                io.circe.parser.parse(os.read(p)).toOption.flatMap { j =>
                  val c = j.hcursor
                  for
                    id <- c.get[String]("delegateId").toOption
                    st <- c.get[String]("state").toOption
                  yield Entry(id, st, j)
                }
              catch case _: Throwable => None
            parsed match
              case Some(e) => Some(e)
              case None =>
                logger.warn(s"[merge-channel] unreadable entry ${p.last} skipped (missing delegateId/state)")
                None
          }
          .sortBy(_.delegateId)
    catch
      case e: Throwable =>
        logger.warn(s"[merge-channel] scan failed (${Option(e.getMessage).getOrElse(e.toString).take(200)}) — treated as empty")
        Nil

  /**
   * **owner-gone 前提探针**：该成员在通道里是否有未关账镜像。
   * `openStates` 由调用方传入（MergeQueue.State.Open）——本对象不做状态机语义。
   */
  def hasOpenRecord(delegateId: String, openStates: Set[String]): Boolean =
    if delegateId.isEmpty then false
    else readAll().exists(e => e.delegateId == delegateId && openStates.contains(e.state))

  /**
   * 压缩：移除已关账（终态）镜像，返回移除数。**只删终态**——未关账镜像是对账
   * 收养的前提，永不入压缩面。账本为终态审计真源，通道终态副本删除零信息损失。
   */
  def compact(terminalStates: Set[String]): Int =
    try
      if !os.exists(dir) then 0
      else
        readAll()
          .filter(e => terminalStates.contains(e.state))
          .map { e =>
            try
              os.remove(fileOf(e.delegateId))
              1
            catch case _: Throwable => 0
          }
          .sum
    catch
      case e: Throwable =>
        logger.warn(s"[merge-channel] compact failed: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
        0

  /** 阈值门压缩（careTick 拍尾调用；未达阈值 = 零动作，读数一次目录）。 */
  def compactIfDue(terminalStates: Set[String]): Int =
    try
      val due = readAll().count(e => terminalStates.contains(e.state))
      if due >= CompactAfter then compact(terminalStates) else 0
    catch case _: Throwable => 0

  /** 清空（运维/spec 用；正常路径绝不调用——零删除纪律）。 */
  def clear(): Unit =
    try if os.exists(dir) then os.remove.all(dir)
    catch case e: Throwable =>
      logger.warn(s"[merge-channel] clear failed: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")

end MergeQueueChannel
