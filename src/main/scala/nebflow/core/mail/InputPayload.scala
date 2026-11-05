package nebflow.core.mail

import io.circe.*
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.shared.ContentBlock
import nebflow.shared.given

/**
 * InputPayload —— 用户输入/注入输入进磁盘邮箱时的**可序列化形态**
 * （用户输入落磁盘邮箱批 P3，作者裁定 2026-11-05 09:22「落盘，带幂等键」/ 09:26「37 处全量」）。
 *
 * == 为什么需要它（现状事实）==
 * `AgentCommand.UserInput` / `ImmediateInput` 是**命令面**类型，其中
 * `UserInput.replyTo: Option[ActorRef[AgentEvent]]` 是**本次运行句柄**
 * （架构规格 v1 §1 条款 R-5：身份 = 持久 id；`ActorRef` = 本次运行句柄；重启 = 新句柄同身份）。
 * 句柄不可序列化、跨进程无意义 ⇒ 落盘必须剥掉，改存 `replyToSessionId`（身份），
 * 恢复时按身份从 registry 重新解析（范式 = `MailboxDrain.injectIdleTurn` 的
 * `continuationReplyTo`）。
 *
 * == 落在哪一层 ==
 * 本件 ∈ `core.mail`（core 层，**零 agent 依赖**）。`AgentCommand` 属 `nebflow.actor`
 * （SessionMailbox 早已依赖它），故此处只用 `actor` 类型，不引用
 * `nebflow.agent.CompactionQueueStore` 的既有 codec（那是 agent 层，方向会倒置）。
 *
 * == 与幂等键的关系 ==
 * 本件只承载**载荷**；幂等键 = `SessionMailbox.Entry.identity`（入队幂等，防写重）。
 * 生成规则见设计 §2.2（K1 原生事件 id / K2 `clientMessageId` / K3 调用点铸造 ULID），
 * **identity 一律是入参，不由本件生成**——否则重试腿会每轮铸新键、幂等当场失效。
 */
final case class InputPayload(
  /** 载荷族：`"user"`（UserInput）| `"immediate"`（ImmediateInput）。重建时的判别位。 */
  kind: String,
  text: String,
  blocks: Option[List[ContentBlock]] = None,
  /** K2 幂等键（真人件）：客户端投递幂等面，落盘保存以便重放腿与呈现层对齐。 */
  clientMessageId: Option[String] = None,
  chatWidth: Int = 0,
  source: Option[String] = None,
  eventType: Option[String] = None,
  sender: Option[String] = None,
  senderTeam: Option[String] = None,
  delivery: Option[String] = None,
  intake: Option[String] = None,
  fromUser: Boolean = false,
  project: Option[String] = None,
  queuedAtMs: Option[Long] = None,
  windowItems: Option[List[AgentCommand.WindowItem]] = None,
  /**
   * `UserInput.replyTo` 的**身份形态**（会话 id）。`None` = 无结算目标 或 原 ref 不属于
   * 任何已登记会话。恢复重放时用它查 registry 取本次运行的新句柄。
   */
  replyToSessionId: Option[String] = None
)

object InputPayload:

  val KindUser: String = "user"
  val KindImmediate: String = "immediate"

  /** `UserInput` → 载荷（`replyTo` 由调用方按身份折算成 `replyToSessionId`）。 */
  def ofUser(ui: AgentCommand.UserInput, replyToSessionId: Option[String] = None): InputPayload =
    InputPayload(
      kind = KindUser,
      text = ui.text,
      blocks = ui.blocks,
      clientMessageId = ui.clientMessageId,
      chatWidth = ui.chatWidth,
      source = ui.source,
      eventType = ui.eventType,
      sender = ui.sender,
      senderTeam = ui.senderTeam,
      delivery = ui.delivery,
      intake = ui.intake,
      fromUser = ui.fromUser,
      project = ui.project,
      queuedAtMs = ui.queuedAtMs,
      windowItems = None,
      replyToSessionId = replyToSessionId
    )

  /** `ImmediateInput` → 载荷（该类本无 `replyTo`，构造上不含运行句柄）。 */
  def ofImmediate(imm: AgentCommand.ImmediateInput): InputPayload =
    InputPayload(
      kind = KindImmediate,
      text = imm.text,
      blocks = imm.blocks,
      clientMessageId = imm.clientMessageId,
      chatWidth = 0,
      source = imm.source,
      eventType = imm.eventType,
      sender = imm.sender,
      senderTeam = imm.senderTeam,
      delivery = imm.delivery,
      intake = None,
      fromUser = imm.fromUser,
      project = imm.project,
      queuedAtMs = None,
      windowItems = imm.windowItems,
      replyToSessionId = None
    )

  /**
   * 载荷 → 命令（重放面）。`replyTo` 恒 `None`：
   * 句柄必须由**恢复点**按 `replyToSessionId` 重新解析后另行挂接，
   * 绝不允许从磁盘"复原"一个上一进程的 ref。
   */
  def toCommand(p: InputPayload): Option[AgentCommand] =
    p.kind match
      case KindUser =>
        Some(
          AgentCommand.UserInput(
            text = p.text,
            replyTo = None,
            clientMessageId = p.clientMessageId,
            blocks = p.blocks,
            chatWidth = p.chatWidth,
            source = p.source,
            sender = p.sender,
            senderTeam = p.senderTeam,
            delivery = p.delivery,
            eventType = p.eventType,
            intake = p.intake,
            fromUser = p.fromUser,
            project = p.project,
            queuedAtMs = p.queuedAtMs
          )
        )
      case KindImmediate =>
        Some(
          AgentCommand.ImmediateInput(
            text = p.text,
            blocks = p.blocks,
            source = p.source,
            eventType = p.eventType,
            sender = p.sender,
            senderTeam = p.senderTeam,
            delivery = p.delivery,
            fromUser = p.fromUser,
            project = p.project,
            windowItems = p.windowItems,
            clientMessageId = p.clientMessageId
          )
        )
      case _ => None

  // ── Codec（手写，与仓内 Entry/PersistedQueues 同款：缺席即不落键）─────────

  private def windowItemEnc(w: AgentCommand.WindowItem): Json =
    Json.obj(
      "text" -> w.text.asJson,
      "nodeName" -> w.nodeName.asJson,
      "status" -> w.status.asJson,
      "sender" -> w.sender.asJson
    )

  // 注意：这里必须是 `val`（Decoder 实例），不能写成 `def` —— 后者在
  // `-Werror` 下触发 eta-expansion 警告即编译失败（本仓 -Xfatal-warnings）。
  private val windowItemDecoder: Decoder[AgentCommand.WindowItem] =
    Decoder.instance { c =>
      for
        text <- c.get[String]("text")
        nodeName <- c.get[String]("nodeName")
        status <- c.get[String]("status")
        sender <- c.get[String]("sender")
      yield AgentCommand.WindowItem(text, nodeName, status, sender)
    }

  given Encoder[InputPayload] = Encoder.instance { p =>
    val base = List(
      "kind" -> p.kind.asJson,
      "text" -> p.text.asJson,
      "fromUser" -> p.fromUser.asJson
    )
    val extra = List(
      p.blocks.filter(_.nonEmpty).map(b => "blocks" -> b.asJson),
      p.clientMessageId.map(v => "clientMessageId" -> v.asJson),
      Option.when(p.chatWidth != 0)("chatWidth" -> p.chatWidth.asJson),
      p.source.map(v => "source" -> v.asJson),
      p.eventType.map(v => "eventType" -> v.asJson),
      p.sender.map(v => "sender" -> v.asJson),
      p.senderTeam.map(v => "senderTeam" -> v.asJson),
      p.delivery.map(v => "delivery" -> v.asJson),
      p.intake.map(v => "intake" -> v.asJson),
      p.project.map(v => "project" -> v.asJson),
      p.queuedAtMs.map(v => "queuedAtMs" -> v.asJson),
      p.windowItems.filter(_.nonEmpty).map(ws => "windowItems" -> Json.arr(ws.map(windowItemEnc)*)),
      p.replyToSessionId.map(v => "replyToSessionId" -> v.asJson)
    ).flatten
    Json.obj((base ++ extra)*)
  }

  given Decoder[InputPayload] = Decoder.instance { c =>
    for
      kind <- c.get[String]("kind")
      text <- c.get[String]("text")
      fromUser <- c.get[Option[Boolean]]("fromUser")
      blocks <- c.get[Option[List[ContentBlock]]]("blocks")
      clientMessageId <- c.get[Option[String]]("clientMessageId")
      chatWidth <- c.get[Option[Int]]("chatWidth")
      source <- c.get[Option[String]]("source")
      eventType <- c.get[Option[String]]("eventType")
      sender <- c.get[Option[String]]("sender")
      senderTeam <- c.get[Option[String]]("senderTeam")
      delivery <- c.get[Option[String]]("delivery")
      intake <- c.get[Option[String]]("intake")
      project <- c.get[Option[String]]("project")
      queuedAtMs <- c.get[Option[Long]]("queuedAtMs")
      windowItems <- c.get[Option[List[AgentCommand.WindowItem]]]("windowItems")(
        using Decoder.decodeOption(Decoder.decodeList(windowItemDecoder))
      )
      replyToSessionId <- c.get[Option[String]]("replyToSessionId")
    yield InputPayload(
      kind = kind,
      text = text,
      blocks = blocks,
      clientMessageId = clientMessageId,
      chatWidth = chatWidth.getOrElse(0),
      source = source,
      eventType = eventType,
      sender = sender,
      senderTeam = senderTeam,
      delivery = delivery,
      intake = intake,
      fromUser = fromUser.getOrElse(false),
      project = project,
      queuedAtMs = queuedAtMs,
      windowItems = windowItems,
      replyToSessionId = replyToSessionId
    )
  }
end InputPayload
