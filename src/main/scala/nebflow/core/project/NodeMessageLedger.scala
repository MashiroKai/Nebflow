package nebflow.core.project

import cats.effect.IO
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.{Message, NebflowLogger}

/**
 * `node:` 消息 **append-only 台账**（C-2 批 2026-11-04，作者原话「node 的消息…中断了都应该能
 * 恢复自己的上下文，继续干活」）。
 *
 * ## 为什么需要它（确切丢件路径）
 *
 * `node:` 三态投递（[[NodeDelivery.sendNodeMessage]]）的 running 腿是
 * `record.ref ! AgentCommand.ImmediateInput(...)`（NodeDelivery.scala:317）——**fire-and-forget
 * tell**：tell 返回时消息只存在于目标 actor 的邮箱 / 内存消息数组里，落盘发生在**接收方**
 * （idle 直投腿直接进 turn 的 messages，`AgentFinishTurn.scala:649`
 * `saveMessagesForSession` 才写转录；busy 腿 `AgentProcessing.scala:1651-1655`
 * `persistQueues` 才写 `injection-queues.json`）。进程在这两个落盘点**之前**死（kill -9 /
 * 断电 / 引擎重启）⇒ 消息既不在转录也不在注入队，唯余 FlowMapEventLog 一行
 * `injected -> session …`（NodeDelivery.scala:332-338，**没有任何回放腿读它**）⇒ 永久丢件。
 *
 * 本对象把**发送侧**变成单一写者：投递**前**先 append 一行 `queued`（durable），投递后 append
 * 状态行；重启时 [[NodeRecovery.bootRecoveryClaim]] 按台账把「未被消费」的消息重放进
 * resume prompt（节点续跑的第一条输入），并用 `消息号 <id>` 标记做**幂等**判据
 * （转录里已有该标记 = 已消费 ⇒ 不再注入）。
 *
 * ## 落盘位置
 *
 * `<workspace>/.nebflow/node-messages.jsonl`（与 `flow-map-events.jsonl`（FlowMapEventLog.scala:101
 * / :980）同目录同形态：append-only JSONL，一行一状态迁移，`createFolders = true`）。
 * 为什么挂 workspace 而不是 `~/.stand`：`node:` 消息属于**该 flow map**，跨实例重启后由
 * 同一引擎（同一 workspace）回放；delegate 作用域天然各写各的座位，互不串账。
 *
 * ## 幂等键（三层）
 *
 *  1. **消息身份** `id` = `nm-<base36 毫秒>-<6 hex>`（发送时铸一次，全生命周期不变）——
 *     埋进投递正文的 `消息号 <id>` 标记里（header / task 分节头），任何持久面都能凭它认出
 *     「这条消息是否已经到过」。
 *  2. **状态机**（一行一迁移，fold 取最高档）：`queued` < `injected`/`appended` < `replayed`
 *     < `consumed`/`dead`。只有 `queued`/`injected`/`replayed` 是三态里**可能未消费**的
 *     ⇒ 只有它们进回放候选；`consumed`/`dead` 终态，永不再注入。
 *  3. **转录判据**：回放前扫节点会话转录（`loadMessagesForSession`），命中标记 ⇒ append
 *     `consumed` + 跳过注入（同一消息绝不二次注入/二次执行）。转录是**权威**：即使台账
 *     停滞在 `injected`（崩溃在状态行写入前），转录命中即视为已消费。
 *
 * 纪律：`queued` 行写失败 => **投递失败**（宁可拒绝也不制造「已投但无盘面」的窗口，
 * 这正是本批要关的缝）；状态迁移行写失败只 WARN（投递事实已成立，留痕不阻断）。
 */
object NodeMessageLedger:

  val FileName = "node-messages.jsonl"

  private val logger = NebflowLogger.forName("nebflow.node.message-ledger")

  // ── 状态档（fold 用；数值 = 单调优先级）────────────────────────────────
  val Queued = "queued"
  val Injected = "injected"
  val Appended = "appended"
  val Replayed = "replayed"
  val Consumed = "consumed"
  val Dead = "dead"

  /** 路由（与 NodeDelivery 三态一一对应，只作审计判读）。 */
  val RouteInject = "inject"
  val RouteTask = "task"
  val RouteUndelivered = "undelivered"

  private def rank(event: String): Int = event match
    case Queued   => 0
    case Injected => 1
    case Appended => 1
    case Replayed => 2
    case Consumed => 3
    case Dead     => 3
    case _        => 0

  /** 台账行（append-only，一行一次状态迁移）。 */
  final case class Row(
    id: String,
    event: String,
    nodeId: String,
    nodeName: String,
    text: String,
    route: String,
    ts: Long,
    detail: Option[String] = None
  )

  /** fold 后的单条消息状态（同一 id 的最高档行）。 */
  final case class Entry(
    id: String,
    nodeId: String,
    nodeName: String,
    text: String,
    route: String,
    event: String,
    firstTs: Long,
    detail: Option[String],
    rows: Int
  ):
    /** 可能「已投递但未被消费」⇒ 回放候选。 */
    def pending: Boolean = event == Queued || event == Injected || event == Replayed
    def isTerminal: Boolean = event == Consumed || event == Dead

  // ── 幂等键 / 标记 ───────────────────────────────────────────────────────

  /** 铸一条消息的身份牌（发送侧单点；全生命周期复用，绝不重铸）。 */
  def newId(): String =
    s"nm-${java.lang.Long.toString(System.currentTimeMillis(), 36)}-${java.util.UUID.randomUUID.toString.take(6)}"

  /**
   * 正文里的**可机械求值标记**（中文词 + 身份牌）：投递文本（header / 分节头）恒带它 ⇒
   * 转录、task 正文、台账三面都能凭同一个字符串判定「这条消息到了没有」。
   */
  def markerOf(id: String): String = s"消息号 $id"

  /** 转录（或任意持久正文）里是否已有该消息的标记 ⇒ 已消费。 */
  def inMessages(msgs: List[Message], id: String): Boolean =
    val m = markerOf(id)
    msgs.exists(_.textContent.contains(m))

  def inText(text: String, id: String): Boolean = text.contains(markerOf(id))

  // ── 落盘 ────────────────────────────────────────────────────────────────

  def path(workspace: String): os.Path =
    os.Path(workspace) / ".nebflow" / FileName

  private def encode(r: Row): Json =
    Json.obj(
      (List(
        "ts" -> r.ts.asJson,
        "event" -> r.event.asJson,
        "id" -> r.id.asJson,
        "nodeId" -> r.nodeId.asJson,
        "nodeName" -> r.nodeName.asJson,
        "route" -> r.route.asJson,
        "text" -> r.text.asJson
      ) ++ r.detail.toList.map(d => "detail" -> d.asJson))*
    )

  private def decodeRow(line: String): Option[Row] =
    decode[Json](line).toOption.flatMap { j =>
      val c = j.hcursor
      c.get[String]("id").toOption.map { id =>
        Row(
          id = id,
          event = c.get[String]("event").getOrElse(Queued),
          nodeId = c.get[String]("nodeId").getOrElse(""),
          nodeName = c.get[String]("nodeName").getOrElse(""),
          text = c.get[String]("text").getOrElse(""),
          route = c.get[String]("route").getOrElse(RouteInject),
          ts = c.get[Long]("ts").getOrElse(0L),
          detail = c.get[String]("detail").toOption
        )
      }
    }

  /** 追加一行（append-only；`createFolders` = 未挂载/测试新目录也不丢账）。 */
  def append(workspace: String, row: Row): IO[Unit] =
    IO.blocking {
      os.write.append(path(workspace), encode(row).noSpaces + "\n", createFolders = true)
    }

  /**
   * 状态迁移行（`queued` 之后）：失败只 WARN——投递事实已经成立，台账留痕不得反过来
   * 阻断投递链（与 FlowMapEventLog 的审计纪律同源）。
   */
  def appendSoft(workspace: String, row: Row): IO[Unit] =
    append(workspace, row).handleErrorWith(e =>
      logger.warn(s"node-message ledger append failed (id=${row.id} event=${row.event}): ${e.getMessage}")
    )

  /** 读全量行（缺失/损坏 => Nil；坏行逐行跳过）。 */
  def load(workspace: String): IO[List[Row]] =
    IO.blocking {
      val f = path(workspace)
      if !os.exists(f) then Nil
      else os.read.lines(f).toList.flatMap(decodeRow)
    }.handleErrorWith(e => logger.warn(s"node-message ledger read failed ($workspace): ${e.getMessage}").as(Nil))

  /** fold：按 id 取最高档状态行（同档取最后一次出现），保持首次出现顺序。 */
  def fold(rows: List[Row]): List[Entry] =
    val order = rows.map(_.id).distinct
    val byId = rows.groupBy(_.id)
    order.flatMap { id =>
      byId.get(id).flatMap { rs =>
        rs.reduceOption((a, b) => if rank(b.event) >= rank(a.event) then b else a).map { best =>
          Entry(
            id = id,
            nodeId = best.nodeId,
            nodeName = best.nodeName,
            text = best.text,
            route = best.route,
            event = best.event,
            firstTs = rs.map(_.ts).minOption.getOrElse(0L),
            detail = best.detail,
            rows = rs.size
          )
        }
      }
    }

  /** 全账（已 fold）。 */
  def entries(workspace: String): IO[List[Entry]] = load(workspace).map(fold)

  /** 某节点的**回放候选**（未消费、未判死）。 */
  def pendingFor(workspace: String, nodeId: String): IO[List[Entry]] =
    entries(workspace).map(_.filter(e => e.nodeId == nodeId && e.pending))

  /** 便捷状态迁移（nodeId/nodeName/text/route 从既有行继承）。 */
  def transition(workspace: String, base: Entry, event: String, detail: Option[String]): IO[Unit] =
    appendSoft(
      workspace,
      Row(
        id = base.id,
        event = event,
        nodeId = base.nodeId,
        nodeName = base.nodeName,
        text = base.text,
        route = base.route,
        ts = System.currentTimeMillis(),
        detail = detail
      )
    )

end NodeMessageLedger
