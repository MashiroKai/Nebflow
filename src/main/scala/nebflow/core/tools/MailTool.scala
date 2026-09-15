package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowMailStore, MailQueueStore, TeamSessionRegistry}
import nebflow.core.project.{ProjectActor, ProjectRuntimeRegistry}
// device-mail 批（2026-09-15）：契约单点 + 本机 NebLink 身份面（设备腿）。
import nebflow.neblink.{DeviceMail, DeviceMailAck, NeblinkService}
import nebflow.shared.{ContentBlock, Message, MessageRole, ToolDefinition}


/**
 * Agent-to-agent communication tool.
 *
 * Delivery (via the `delivery` parameter) — **one mode only: immediate** (async send:
 * injected at the next turn boundary — an idle recipient starts a new turn, a busy
 * recipient gets it merged into the current turn).
 *
 * The former `queue` mode (serialized FIFO persisted to disk, one mail per turn —
 * for serial task chains: "do this, then that") was **retired** (delivery 退役批,
 * 2026-09-15 作者裁定 (b)「保留字段、退役 queue 模式语义」):
 * - the schema **keeps** the `delivery` key (compat: an old caller's key must not
 *   become an unknown property), but its `enum` is `["immediate"]` only;
 * - **every non-device leg** (team short name / `project:` / `node:` / Nebula) that
 *   still sends `delivery="queue"` is an **explicit error**
 *   (`MAIL_DELIVERY_QUEUE_RETIRED`) — **never** a silent downgrade to immediate
 *   (a declared serial-chain intent cannot be honoured by immediate delivery, so
 *   silently ignoring it would be a silent semantic loss — same direction as the
 *   landed `node:` / device-leg explicit refusals);
 * - the **device leg** keeps its own landed v2.1 refusal verbatim
 *   ([[deliverToDevice]]) — not rewritten by this batch;
 * - the legacy queue layer below (`MailQueueStore` / `deliverQueue` / `queueTo*`)
 *   is **retained**: it has consumers on other faces (session queue drain, REST
 *   queue endpoints) and specs call it directly — it now has **zero production
 *   caller from this tool face**.
 *
 * The former `ask` mode (synchronous context fork) was removed entirely
 * (2026-08-27 user ruling) — see git history if that mechanism is ever needed.
 */
object MailTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Routing rule: senders without a team context mail TEAM names only. */
  private val TeamOnlyRoutingError =
    "Agents outside a team mail TEAM names only (e.g. \"nebflow-project\") — the team Manager dispatches to members. \"team/agent\" explicit addresses and bare short names are not routable from outside a team."

  /** Nebula root agent 定义名（分层地址面与 root 解析的判据单点）。 */
  private val NebulaAgentName = "Nebula"

  // ============================================================
  // device-mail 批（2026-09-15）——`device` 目标的校验词表（**唯一来源**；
  // 报告、spec、描述三处同源引用，禁第二份字面量）。四类 + 设备面不可得：
  //   MAIL_TARGET_EXCLUSIVE  互斥：`address` 与 `device` 同填
  //   MAIL_TARGET_MISSING    双缺：两个目标都没填
  //   MAIL_DEVICE_NOT_FOUND  未知设备（含**歧义**多命中：沿用先例的候选清单文案，
  //                          判据在 `FriendMessageTool.resolveDevice`，本处只加码）
  //   MAIL_DEVICE_MALFORMED  非法形态（URL / 带 `device:` 前缀 / 空值）
  // ============================================================
  val ErrTargetExclusive: String = "MAIL_TARGET_EXCLUSIVE"
  val ErrTargetMissing: String = "MAIL_TARGET_MISSING"
  val ErrDeviceNotFound: String = "MAIL_DEVICE_NOT_FOUND"
  val ErrDeviceMalformed: String = "MAIL_DEVICE_MALFORMED"

  /** 互斥报错（逐字词表；`address` 与 `device` 各自可空、**禁双填**）。 */
  private def targetExclusiveError(address: String, device: String): ToolError =
    ToolError(
      s"[$ErrTargetExclusive] 'address' and 'device' are mutually exclusive — fill exactly one. " +
        s"Got address='$address', device='$device'. Use 'address' for agent/team/project targets " +
        "and 'device' for another machine's Nebula."
    )

  /** 双缺报错（逐字词表）。 */
  private[tools] val targetMissingMessage: String =
    s"[$ErrTargetMissing] Missing target: fill exactly one of 'address' (agent/team/project) or " +
      "'device' (another machine's Nebula). 'address' is required only when 'device' is absent."

  /** 非法形态报错（逐字词表；判据见 [[deviceMalformedReason]]）。 */
  private def deviceMalformedError(raw: String, reason: String): ToolError =
    ToolError(
      s"[$ErrDeviceMalformed] Malformed 'device' value '$raw' — $reason. Pass the device NAME or " +
        "device id alone (e.g. \"macbook-pro\" or the id); the \"device:\" prefix belongs to SendMessage's 'to'."
    )

  /** 非法形态判据（纯函数，供 spec 直测）。返回 `Some(可读原因)` = 形态非法。 */
  private[tools] def deviceMalformedReason(raw: String): Option[String] =
    val v = raw.trim
    if v.isEmpty then Some("it is empty")
    else if v.startsWith("device:") then Some("it carries the \"device:\" prefix")
    else if v.contains("://") then Some("it looks like a URL, not a device name/id")
    else None

  // ============================================================
  // delivery 退役批（2026-09-15 作者裁定 (b)「保留字段、退役 queue 模式语义」）——
  // **非设备腿** `delivery="queue"` 的统一显式拒绝文案（**唯一来源**；spec 与此处同源）。
  //   · 语义 = `delivery` 字段**保留**（schema 键在、`enum` 只剩 `"immediate"`），queue
  //     **模式**退役 ⇒ 一切非设备腿收到该值**立即显式拒绝**：零投递副作用、零队列落盘。
  //   · 选「显式拒绝」而非「立即化」的理由：调用方声明的**串行链语义**无法被立即投递
  //     满足 —— 静默改投 = 静默丢语义（禁用面），且与既有 `node:` / 设备腿的
  //     「显式拒绝，禁静默降级」先例同向；调用方拿到可读错误即可自纠。
  //   · 设备腿**不走本文案**：其 v2.1 拒 queue 契约自有字面量、逐字不动（见
  //     [[deliverToDevice]]）——两处字面量不同是**有意**的（禁为退役而翻已落契约）。
  // ============================================================
  val ErrDeliveryQueueRetired: String = "MAIL_DELIVERY_QUEUE_RETIRED"

  private[tools] def deliveryQueueRetiredMessage(address: String): String =
    s"""[$ErrDeliveryQueueRetired] delivery="queue" is retired (2026-09-15) — the serialized FIFO mode no longer exists: every Mail is delivered immediately (injected at the target's next turn boundary; an idle target starts a new turn, a busy target has it merged into the current turn). Drop delivery=queue (or omit the `delivery` parameter — only "immediate" is accepted). Target: '$address'."""

  val name: String = "Mail"

  val description: String =
    """Send a message to another agent — the platform's **only message primitive**
(2026-09-12 「一个 Mail 统一」；the former `Task` and `NodeMessage` tools are retired —
this note supersedes all earlier instructions naming them as entry points).

Required: message, plus **exactly one** of `address` / `device` (mutually exclusive — both
filled is an explicit error, both empty is an explicit error)

## Device target (`device` parameter — cross-device Nebula mail, 2026-09-15)
`device` = another machine of the same NebLink account, given as a device NAME or device
id (same resolution as `SendMessage`'s `device:` target: exact id → exact name → id prefix
→ name prefix → name contains; unknown **or ambiguous** ⇒ explicit error listing the
candidates, never a silent first hit). The mail is handed to the NebLink server
(`POST /api/relay/{target_device_id}/mail`, target in the path — no broadcast, no fan-out)
with the frozen payload `{"type":"agent_mail","from_device":…,"from_device_id":…,"to_nebula":true,"text":…}`
and is pushed to that device as event-stream `agent_mail`; it lands **directly in that device's
Nebula session** — injected at its next turn boundary with the header line
`[DEVICE-MAIL · from <from_device>]` (type INFO), shown in the peer's message stream as a blue
injected bubble. It does NOT go to the peer's user chat inbox, and **no confirmation card is
raised** (the Mail gate is unchanged — this is not a friend send).

## Address face (role-scoped — an address outside your face is an explicit error)
- **Nebula (root)**: `project:<name>` — triggers that project's dispatcher (a bare
  mounted project name is accepted as an equivalent form). You have no `node:`
  address and no self-address.
- **Project dispatcher**: `Nebula` — the root session; `node:<nodeId>` — a node in
  your current project (from NodeList). You do not mail your own project.
- **Team context (legacy)**: a team name (e.g. "nebflow-project") routed to its
  lead agent, a bare member short name (resolved within your team first), or an
  explicit "team/agent" route.

An address that is not recognizable in your face is an **explicit error** — there
is no silent fallback and no fuzzy matching.

## `node:<id>` routing semantics (by target node status; same engine as the retired
`NodeMessage` tool — `NodeEngine.sendNodeMessage`)
- **running**: injected into the node's live session at the NEXT turn boundary
  (does NOT interrupt the current turn), carrying a `[NODE-MESSAGE]` header.
- **wiring / pending** (non-terminal, no live session): persistently appended to
  the node's task as a「分发器补充」section — read when the node starts.
- **terminal (completed / failed / cancelled / blocked)**: REFUSED
  (`NODE_TERMINAL_NO_MESSAGE`) — a finished node is never retro-edited; create a
  new node instead (NodeEdit).
- Errors: `NODE_NOT_FOUND` / `NODE_MESSAGE_EMPTY` / `NODE_TERMINAL_NO_MESSAGE`.
- Every message (injected / appended / not-delivered) is appended to the project's
  flow-map-events.jsonl audit log (type=node-message).
- `node:` routing ignores `delivery` — the engine decides inject-at-turn-boundary
  vs append-to-task.

Images (optional `images` parameter): up to 5 absolute local image paths
(PNG/JPG/JPEG/GIF/WEBP/BMP) sent as attachments — the recipient sees the images
directly (vision models) plus their paths as text. For any other file, reference
its path in the message text and ask the recipient to Read it.

Delivery (the `delivery` parameter — kept for compatibility, one mode only):
  Every Mail is immediate: async send, injected at the target's next turn
  boundary (an idle target starts a new turn; a busy target has it merged into
  the current turn). You don't wait for a response.
  There is no delivery mode to choose: the former `queue` mode (serialized FIFO,
  one Mail per turn — for "do this, then that" serial chains) was RETIRED on
  2026-09-15. Passing `delivery="queue"` is an explicit error
  (MAIL_DELIVERY_QUEUE_RETIRED) on every non-device target — it is NEVER
  silently downgraded to immediate.

Message type (optional, default "INFO"):
  Every Mail has a TYPE tag. Check the TYPE before acting — it tells you how to handle the Mail:

  1. **[INFO]** — supplementary context for your current task. Keep working. Incorporate silently.
  2. **[FOLLOW_UP]** — additional task to start AFTER your current one finishes. Finish current work first. Then start the new task.
  3. **[PARALLEL]** — independent work that doesn't depend on your current task. Delegate it in parallel. Keep going.
  4. **[INTERRUPT]** — urgent, requires immediate attention. Pause current work and handle this now.
  5. **[RESULT]** — work results or status report from another agent. Acknowledge if needed. Continue your own work unless this changes your task.

  Default: Mail supplements your work, not replaces it. Switch tasks only on [INTERRUPT] or when your current task is complete."""

  // ============================================================
  // Q5（2026-09-13 作者裁定 = (b)）：地址面**只分化 `description`**，`inputSchema`
  // 逐字节不变（规格 §4.4 D-2 的差距 = 一个角色读到另两个角色的地址面：
  // `:49-60` 工具级 address 节 + `:106` 参数级 description）。
  //
  // 做法 = **段投影（纯删段）**：地址面按角色切成逐字段常量（全部取自基础
  // `description` 的既有字节），分化变体 = 基础里**只保留本角色的那一段**。
  // 零新造字（spec `ToolFaceVariantSpec` 断言每个段常量逐字节出现在基础里 +
  // 变体 = 基础删去他角色段的结果）；非地址面字节一律**逐字节原样**（子串手术，
  // 不重排、不改写）。
  //
  // 未分化面（登记在交付说明「待作者给措辞」）：`team context` 段 —— 定义层
  // 无 team 成员身份维度；`inputSchema.properties.address.description`（参数级
  // 三面并集）—— Q5 口径冻结 `inputSchema`，本批不动。
  // ============================================================

  /** **基础变体**（= 上面的 `description`）：**逐字节不变**——未登记/未知身份的
    * 会话看到的那一份（fail-closed 默认面）。 */
  val descriptionBase: String = description

  /** 地址面各段的**逐字常量**（逐字节取自基础 `description`；由 spec 逐段断言）。 */
  private[nebflow] val AddressFaceHeader: String =
    "## Address face (role-scoped — an address outside your face is an explicit error)\n"
  private[nebflow] val AddressFaceNebulaRoot: String =
    "- **Nebula (root)**: `project:<name>` — triggers that project's dispatcher (a bare\n  mounted project name is accepted as an equivalent form). You have no `node:`\n  address and no self-address.\n"
  private[nebflow] val AddressFaceDispatcher: String =
    "- **Project dispatcher**: `Nebula` — the root session; `node:<nodeId>` — a node in\n  your current project (from NodeList). You do not mail your own project.\n"
  private[nebflow] val AddressFaceTeam: String =
    "- **Team context (legacy)**: a team name (e.g. \"nebflow-project\") routed to its\n  lead agent, a bare member short name (resolved within your team first), or an\n  explicit \"team/agent\" route.\n"
  private[nebflow] val AddressFaceClosing: String =
    "\nAn address that is not recognizable in your face is an **explicit error** — there\nis no silent fallback and no fuzzy matching."

  /** 地址面投影：保留首尾公共段 + **本角色的段**，其余字节原样。任一段定位失败
    * （文案被改动）⇒ **fail-closed 回落基础 description**（绝不产出半截地址面）。 */
  private def addressFaceProjection(keep: String): String =
    val h = descriptionBase.indexOf(AddressFaceHeader)
    val c = descriptionBase.indexOf(AddressFaceClosing)
    if h < 0 || c < 0 then descriptionBase
    else
      descriptionBase.substring(0, h) + AddressFaceHeader + keep + AddressFaceClosing +
        descriptionBase.substring(c + AddressFaceClosing.length)

  /** **分化变体 · Nebula root**：地址面只留 root 自己的那一面（`project:<name>`）。 */
  val descriptionNebulaRoot: String = addressFaceProjection(AddressFaceNebulaRoot)

  /** **分化变体 · project dispatcher**：地址面只留分发器自己的面（`Nebula` / `node:<id>`）。 */
  val descriptionDispatcher: String = addressFaceProjection(AddressFaceDispatcher)

  /** 定义期变体（[[nebflow.agent.AgentCore.schemaVariantFor]] 消费）：**只替换
    * `description`**——`inputSchema` 逐字节不变（Q5 判据）。身份未知 ⇒ 基础面。 */
  def addressFaceVariant(base: ToolDefinition, nebulaRoot: Boolean, dispatcher: Boolean): ToolDefinition =
    if nebulaRoot then base.copy(description = descriptionNebulaRoot)
    else if dispatcher then base.copy(description = descriptionDispatcher)
    else base

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "address" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Role-scoped address. Nebula (root): \"project:<name>\" (a bare mounted project name is equivalent). Project dispatcher: \"Nebula\" or \"node:<nodeId>\". Team context: a team name, a member short name, or \"team/agent\". An address outside your face is an explicit error.".asJson
        ),
        "device" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Cross-device Nebula mail (device-mail, 2026-09-15): another machine of the same NebLink account, by device NAME or device id. MUTUALLY EXCLUSIVE with `address` — fill exactly one of the two (both ⇒ MAIL_TARGET_EXCLUSIVE, neither ⇒ MAIL_TARGET_MISSING). Unknown/ambiguous device ⇒ MAIL_DEVICE_NOT_FOUND with the candidate list; a malformed value (URL, or a \"device:\" prefix — the prefix belongs to SendMessage's `to`) ⇒ MAIL_DEVICE_MALFORMED. The message goes to the NebLink server (`POST /api/relay/{target_device_id}/mail` — the addressed device only, never a broadcast) and is pushed to it as an event-stream `agent_mail` event; it is injected into that device's Nebula session at its next turn boundary (header line `[DEVICE-MAIL · from <from_device>]`, type INFO); the peer sees it as a blue injected bubble. No confirmation card.".asJson
        ),
        "message" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or question to send".asJson
        ),
        "type" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("INFO".asJson, "FOLLOW_UP".asJson, "PARALLEL".asJson, "INTERRUPT".asJson, "RESULT".asJson),
          "description" -> """Message type tag. "INFO" = supplementary context (default); "FOLLOW_UP" = new task after current finishes; "PARALLEL" = delegate independently; "INTERRUPT" = urgent, handle now; "RESULT" = work results from another agent.""".asJson,
          "default" -> "INFO".asJson
        ),
        "delivery" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("immediate".asJson),
          "description" -> "Delivery mode — one mode only: 'immediate' = inject like user input (merged into the target's current turn at its next boundary; an idle target starts a new turn). The former 'queue' mode (serialized FIFO, one Mail per turn) was RETIRED on 2026-09-15: it is NOT available — passing \"queue\" is an explicit error (MAIL_DELIVERY_QUEUE_RETIRED) on every non-device target and is never silently downgraded. The key is kept for backward compatibility; the default is 'immediate'.".asJson,
          "default" -> "immediate".asJson
        ),
        "chainId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional chain id (e.g. \"chain-n-933b5a8c\") of the batch this Mail belongs to. Validated against the project's derived chain set — an unknown id is an explicit error (MAIL_CHAIN_NOT_FOUND). Not persisted anywhere; when provided it is embedded verbatim in the injected text so the recipient can quote it back. REQUIRED when reporting a batch close-out to Nebula.".asJson
        ),
        "images" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> 5.asJson,
          "description" -> "Optional absolute local image paths (PNG/JPG/JPEG/GIF/WEBP/BMP, max 5) to attach — the recipient sees the images directly plus their paths as text. For other files, reference the path in the message text.".asJson,
          "default" -> Json.arr()
        )
      ),
      "required" -> Json.arr("message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val addr = input("address").flatMap(_.asString).getOrElse("?")
    val device = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val target = device match
      case Some(d) => s"device:$d"
      case None    => addr
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")
    val typeStr = if mailType != "INFO" then s" [$mailType]" else ""
    // delivery 退役批（2026-09-15）：标签面不再有 `, queue` 形态 —— queue 模式退役后
    // 该值只可能是**已拒绝**的旧调用方，标签不得再宣称 queue 投递（描述面清理的连带面）。
    s"Mail(→$target)$typeStr"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("").trim
    val deviceRaw = input("device").flatMap(_.asString).getOrElse("").trim
    val message = input("message").flatMap(_.asString).getOrElse("")
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")

    val delivery = input("delivery").flatMap(_.asString).getOrElse("immediate")
    val chainIdRaw = input("chainId").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).filter(_ != "null")

    // device-mail 批（2026-09-15）：目标面 = `address` XOR `device`（各自可空、禁双填）。
    // 校验前置于一切投递副作用（与既有 fail-fast 纪律同序）。
    if address.nonEmpty && deviceRaw.nonEmpty then
      IO.pure(Left(targetExclusiveError(address, deviceRaw)))
    else if address.isEmpty && deviceRaw.isEmpty then IO.pure(Left(ToolError(targetMissingMessage)))
    else if message.isEmpty then IO.pure(Left(ToolError("Missing required parameter: message")))
    else if deviceRaw.nonEmpty then
      // 设备腿：形态先判（非法形态零副作用），再走先例的设备解析。
      deviceMalformedReason(deviceRaw) match
        case Some(reason) => IO.pure(Left(deviceMalformedError(deviceRaw, reason)))
        case None         => validateChainId(chainIdRaw, ctx).flatMap {
            case Left(err) => IO.pure(Left(err))
            // chainId 于设备腿**只校验不带出**（链集是本项目派生的，对端 Nebula 的
            // 项目链不同源 ⇒ 塞进对端注入体会误导）；登记在交付说明。
            case Right(_) => deliverToDevice(deviceRaw, message, mailType, delivery, ctx)
          }
    // delivery 退役批（2026-09-15 作者裁定 (b)）：**非设备腿** `delivery="queue"` ⇒
    // **显式拒绝**（零副作用，先于 chainId 校验与一切路由/投递）。
    // 位置**必须在设备腿分支之后**：设备腿的 v2.1「显式拒 queue」契约自有字面量，
    // 逐字保持、不得被本文案顶替（[[deliverToDevice]]）；`address`≠设备腿在这里兜住
    // 其余全部腿（`node:` / `project:` / Nebula / team 短名 / 裸项目名）——单点，
    // 结构性保证「本工具面零 queue 入口」。
    else if delivery == "queue" then IO.pure(Left(ToolError(deliveryQueueRetiredMessage(address))))
    else
      // B2-x：chainId 只校验不落库（零链级账本）——校验在一切投递副作用之前。
      validateChainId(chainIdRaw, ctx).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(chainId) =>
          ImageInject.parseImagesParam(input) match
            case Left(err) => IO.pure(Left(err))
            case Right(imagePaths) =>
              // G3: resolve attachments BEFORE any delivery side effect (fail fast
              // at the point of action — actor activation / queue persist must not
              // happen for an invalid attachment). Queue mode persists the paths
              // and re-reads at drain time (D6), so only the validation result is
              // used there.
              ImageInject.resolveImages(imagePaths).flatMap {
                case Left(err) => IO.pure(Left(err))
                case Right(attachments) =>
                  val blocks = ImageInject.messageBlocks(message, attachments)
                  ctx.actorSystem match
                    case None =>
                      IO.pure(Left(ToolError("No actor system available")))
                    case Some(system) =>
                      // R2 分层地址面（作者 2026-09-12 10:38 细则 + B1-a）：角色专属
                      // 地址形态先在这一层定判——命中即处理（含**显式报错**），未命中
                      // （= 该地址不属于本角色的分层面）才落回既有 team/短名瀑布。
                      // 硬禁静默兜底与模糊匹配：认不出的地址一律显式报错并指明合法面。
                      layeredRoute(address, message, blocks, mailType, chainId, ctx, system) match
                        case Some(action) => action
                        case None =>
                          // Observability (qa #8 note): unknown delivery values (e.g. an
                          // old caller still sending "ask") silently converge to the
                          // immediate path — warn so stale callers surface in logs.
                          // 注：`"queue"` 不再进入本匹配（上层单点已显式拒绝，退役批
                          // 2026-09-15）；故本层只剩「立即」与「陌生值收敛到立即」两支。
                          delivery match
                            case "immediate" =>
                              if address.contains("://") then deliverToAddress(address, message, blocks, mailType, ctx, system)
                              else deliverToShortName(address, message, blocks, mailType, ctx, system)
                            case other =>
                              IO(logger.warnSync(
                                s"[mail] unknown delivery mode '$other' from ${ctx.sessionId.getOrElse("?").take(8)} — falling back to immediate"
                              )) *>
                                (if address.contains("://") then deliverToAddress(address, message, blocks, mailType, ctx, system)
                                 else deliverToShortName(address, message, blocks, mailType, ctx, system))
              }
      }
  end call

  // ============================================================
  // R2 分层地址面（「一个 Mail 统一」批 2026-09-12）
  // ============================================================

  /** 发送者角色（授权面分层判据 = **引擎侧身份**，不用 senderName 字符串匹配）。 */
  private enum SenderRole:
    case NebulaRoot, Dispatcher, Teamish

  private def roleOf(ctx: ToolContext): SenderRole =
    if ctx.isDispatcher then SenderRole.Dispatcher
    else if ctx.agentDef.exists(_.name == MailTool.NebulaAgentName) then SenderRole.NebulaRoot
    else SenderRole.Teamish

  private val NodePrefix = "node:"
  private val ProjectPrefix = "project:"

  private def nebulaFace: String = "\"project:<项目名>\"（裸项目名等价接受）"
  private def dispatcherFace: String = "\"Nebula\"（root）或 \"node:<节点id>\""

  /** 分层地址面的显式越界报错（细则：错误消息必须指明**该角色的合法地址面**）。 */
  private def outOfFaceError(address: String, role: SenderRole): ToolError =
    val face = role match
      case SenderRole.NebulaRoot => nebulaFace
      case SenderRole.Dispatcher => dispatcherFace
      case SenderRole.Teamish    => "a team name, a member short name, or \"team/agent\""
    ToolError(
      s"Address '$address' is outside your address face. Your role may only mail: $face. " +
        "There is no silent fallback and no fuzzy matching — use one of the listed forms."
    )

  private def unresolvableError(address: String, role: SenderRole): ToolError =
    ToolError(
      s"Cannot resolve address '$address' — it is not a recognizable target in your address face (${
          role match
            case SenderRole.NebulaRoot => nebulaFace
            case SenderRole.Dispatcher => dispatcherFace
            case SenderRole.Teamish    => "a team name, a member short name, or \"team/agent\""
        }). No fallback was applied."
    )

  /** 分层地址分派。返回 Some(结果) = 本地址形态属分层面（已处理，含显式报错）；
    * None = 不属分层面（调用方继续既有 team/短名瀑布——D-6：legacy 面不随批收口）。
    *
    * delivery 退役批（2026-09-15）：本层**不再持有 `delivery` 参数**——「非设备腿禁
    * queue」由 `call` 的单点前置闸统一下判（该闸在设备腿分支之后、本层之前），
    * 故本层结构上**零 queue 分支**（`project:` 腿原先「两分支同体」也已折成单支）。 */
  private def layeredRoute(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      mailType: String,
      chainId: Option[String],
      ctx: ToolContext,
      system: ActorSystem
  ): Option[IO[Either[ToolError, String]]] =
    val role = roleOf(ctx)
    if address.startsWith(NodePrefix) then
      val nodeId = address.stripPrefix(NodePrefix).trim
      Some(
        if nodeId.isEmpty then IO.pure(Left(ToolError(s"Malformed address '$address' — expected \"node:<节点id>\".")))
        else if role != SenderRole.Dispatcher then IO.pure(Left(outOfFaceError(address, role)))
        else deliverToNode(nodeId, withChainAnnotation(message, chainId), mailType, ctx)
      )
    else if address.startsWith(ProjectPrefix) then
      val pname = address.stripPrefix(ProjectPrefix).trim
      Some(
        if pname.isEmpty then IO.pure(Left(ToolError(s"Malformed address '$address' — expected \"project:<项目名>\".")))
        else if role == SenderRole.Dispatcher then IO.pure(Left(outOfFaceError(address, role)))
        else deliverToProject(pname, message, mailType, ctx)
      )
    else if address == MailTool.NebulaAgentName then
      role match
        case SenderRole.NebulaRoot =>
          Some(IO.pure(Left(ToolError(
            s"Address \"Nebula\" is your own (self) address — it is not in your address face ($nebulaFace)."
          ))))
        case SenderRole.Dispatcher =>
          Some(deliverToNebulaRoot(address, withChainAnnotation(message, chainId), blocks, mailType, ctx, system))
        case SenderRole.Teamish => None // 既有 canMailNebula 闸不变
    else if role == SenderRole.NebulaRoot then
      // Nebula 的裸名形态 = 裸项目名（等价接受）；认不出的地址显式报错。
      Some(
        ProjectRuntimeRegistry.get(address).flatMap {
          case Some(_) => deliverToProject(address, message, mailType, ctx)
          case None    => IO.pure(Left(unresolvableError(address, role)))
        }
      )
    else if role == SenderRole.Dispatcher then
      // 分发器不给自己项目发（细则）——裸名一律越界报错。
      Some(IO.pure(Left(outOfFaceError(address, role))))
    else None
  end layeredRoute

  /** chainId 逐字进注入文本（R-18；腿②③一致）。只影响注入体，不落库。 */
  private def withChainAnnotation(message: String, chainId: Option[String]): String =
    chainId match
      case Some(id) => s"[mail chainId: $id]\n$message"
      case None     => message

  /** B2-x / R-17：`chainId` **只校验、不落库**（零链级账本）。本方法零副作用。
    * 无项目上下文时只做形态校验（无链集可对）；有项目上下文则对派生链全集。
    * 错误码 = `MAIL_CHAIN_NOT_FOUND`（非空但不在链集内）。 */
  private[tools] def validateChainId(chainId: Option[String], ctx: ToolContext): IO[Either[ToolError, Option[String]]] =
    chainId match
      case None => IO.pure(Right(None: Option[String]))
      case Some(id) =>
        ctx.projectName match
          case None => IO.pure(Right(Some(id): Option[String]))
          case Some(projectName) =>
            ProjectRuntimeRegistry.get(projectName).flatMap {
              case None => IO.pure(Right(Some(id): Option[String])) // 项目未挂载：无链集可对，交投递侧报错
              case Some(rt) =>
                rt.engine.chainIds.map { ids =>
                  if ids.contains(id) then Right(Some(id): Option[String])
                  else
                    Left(ToolError(
                      s"Unknown chainId '$id' in project '$projectName' (MAIL_CHAIN_NOT_FOUND). " +
                        s"Known chains: ${if ids.isEmpty then "(none derived)" else ids.toList.sorted.mkString(", ")}. " +
                        "chainId is validated only — it is never persisted; omit it if the Mail does not belong to a batch."
                    ))
                }
            }

  /** 本会话作为发信方的**来源标注**（bluebubble 批 2026-09-12，单点构造）：
    * 接管方 agent 自身名（与前端 `ownAgentName()` 同名空间，见
    * [[nebflow.agent.InjectionAttribution]] 的字段名/取值域契约）+ 所属 Team 名
    * + 邮件类型（eventType）。两条项目腿（腿① 项目、腿② 节点）与 leg③
    * `sendMail` 共用同一取值口径 —— 三种 Mail 形态的气泡顶栏因此同源。
    *
    * `intake`（mailbadge 批 2026-09-13，作者裁定「必须显示 MAIL」⇒ 选项 C）是
    * **调用方声明的收件通道判别**，**不由本方法统一置位**：本方法是三条腿的
    * 共用构造点，而三条腿的收件面**呈现口径不同** ——
    *   - 腿①（`routeToProject` → 分发器收件面）：传
    *     [[InjectionAttribution.IntakeMail]] ⇒ 标签显示 `Mail`（本批的目标）；
    *   - 腿②（`deliverToNode` → 节点收件面）：**不传**（默认 `None`）——
    *     `source` 保持 `"system"` ⇒ 标签恒 `System` + `[NODE-MESSAGE]` 文本头
    *     逐字不变（节点收件面不在本批，已单独立项）；
    *   - 腿③（`sendMail` → 非 project 面）：**不传**（默认 `None`）——
    *     `source` 已是 `"mail"` ⇒ 标签恒 `Mail`，呈现零漂移。
    * 若在此统一置位，腿② 的标签会被抬成 `Mail` ⇒ **越界扩面**（禁动面）。
    *
    * `project`（R-A 补，2026-09-15 ③ root 裁定）与 `intake` **相反**：**三条腿同源置位**
    * ——取本会话所属项目（`ToolContext.projectName`）= 四段式 `PROJECT` 段的**链首级**
    * 「发送方所属项目」。与 `intake`（收件通道的**呈现判别**，逐腿口径不同）无关：
    * 「发送方项目域」在任一收件面上都不改变该腿的标签语义，故单点置位零越界。
    * 腿②（→`node:`，发送方与本会话同项目）往返零漂移；腿①（`project:`，跨项目 /
    * 越面调用）由此从 **收件方**项目**纠正为发送方项目**。`None`（本会话无项目上下文，
    * 如 Nebula root / 团队会话）⇒ 发射面回落根域 `NEBULA`（`leg1SenderProject`）。 */
  private def mailAttribution(
      mailType: String,
      ctx: ToolContext,
      intake: Option[String] = None
  ): IO[InjectionAttribution] =
    val senderName = ctx.agentDef.map(_.name).getOrElse(MailTool.NebulaAgentName)
    TeamSessionRegistry.teamOfSession(ctx.sessionId.getOrElse("")).map { team =>
      InjectionAttribution(
        sender = Some(senderName),
        senderTeam = team,
        eventType = Some(mailType.toLowerCase),
        intake = intake,
        project = ctx.projectName
      )
    }

  /** 腿②（分发器 → 节点）：**复用引擎侧单点** `NodeEngine.sendNodeMessage`——三态判据
    * 与三个错误码**不复制**（复制必然漂移）。注入 source 保持 `"system"`（D-3：
    * 节点侧既有呈现零 UI 行为变化）；来源标注经 attribution 参数传（sender/
    * senderTeam/eventType），节点会话蓝气泡顶栏因此可辨「来自谁」。 */
  private def deliverToNode(nodeId: String, message: String, mailType: String, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.projectName match
      case None | Some("") =>
        IO.pure(Left(ToolError(
          s"Cannot route to node '$nodeId' — this session has no project context (the 'node:' leg resolves the project from the calling session)."
        )))
      case Some(projectName) =>
        ProjectRuntimeRegistry.get(projectName).flatMap {
          case Some(rt) =>
            mailAttribution(mailType, ctx).flatMap { attribution =>
              rt.engine.sendNodeMessage(nodeId, message, Some(attribution)).map(_.left.map(ToolError(_)))
            }
          case None =>
            IO.pure(Left(ToolError(
              s"Project '$projectName' is not mounted — cannot route to node '$nodeId'. Re-mount / restart (projects mount at startup)."
            )))
        }

  /** 腿④（device-mail 批，2026-09-15 作者令）：`Mail(device:X)` → 本机网关 →
    * NebLink 服务端（契约端点 `POST /api/relay/{target_device_id}/mail`，**契约 v2 ①**）
    * → 对端设备隧道推送 `agent_mail` 载荷 → 对端 Nebula 会话注入。
    *
    * 委托面（**零重复实现**）：
    *   - 设备解析 = [[FriendMessageTool.resolveDevice]]（Root 令「沿用 SendMessage 的
    *     device 解析先例」的**现取落点**：deviceId 精确 → deviceName 精确 → deviceId
    *     前缀 → deviceName 前缀 → deviceName 包含，唯一候选才成功；零命中/多命中一律
    *     列可用设备，**禁静默首命中**）；
    *   - 传输 = `NeblinkClient.relayAgentMail`（**目标走路径**；鉴权/自愈走既有
    *     `withSession` + `dispatchRequest` 缝；无 fan-out、无广播兜底——解析出的
    *     deviceId 是路径上的唯一目标，v2 ②）；
    *   - 载荷构造 = [[nebflow.neblink.DeviceMail.payload]]（唯一构造点，恰契约五键）；
    *   - 回执 = [[nebflow.neblink.DeviceMailAck]]（eventId 关联 + 超时腿，v2 ④）。
    *
    * `from_device`/`from_device_id` = **本机自报值，仅供初始展示**：服务端以鉴权
    * 身份覆盖 `from_device_id`（v2 ③），本腿**不**把它用于任何逻辑判定。
    *
    * **零新增闸/卡**：Mail 现状（Nebula 发 Mail 无需确认卡）逐字沿用——本腿只做设备
    * 解析 + 形态/语义闸 + 载荷上通道 + 诚实结果转写 + 一条审计行（④）；不触碰
    * A2A/团队权限面。
    *
    * 两条**显式拒绝**（禁静默丢语义）：设备腿恒 immediate（`delivery=queue` 拒绝，
    * 与 `node:` 腿同一先例）；契约载荷无邮件类型字段 ⇒ 仅 INFO（其它类型拒绝，
    * 不静默降级成 INFO）。 */
  private def deliverToDevice(
      device: String,
      message: String,
      mailType: String,
      delivery: String,
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    if delivery == "queue" then
      IO.pure(Left(ToolError(
        "Device targets are always immediate — the peer's Nebula session is injected at its next turn " +
          "boundary, so a serialized FIFO queue would only delay it. Drop delivery=queue."
      )))
    else if mailType != "INFO" then
      IO.pure(Left(ToolError(
        s"Device targets carry the frozen 'agent_mail' payload, which has no mail-type field — " +
          s"only type \"INFO\" is supported (got \"$mailType\"). Send an INFO Mail and put the urgency in the text."
      )))
    else
      (ctx.sharedResources.flatMap(_.neblinkService), ctx.sharedResources.flatMap(_.dropboxService)) match
        case (Some(ns), _) =>
          for
            id <- ns.identity
            peers <- ns.peers
            result <- FriendMessageTool.resolveDevice(device, peers) match
              case Left(err) =>
                IO.pure(Left(ToolError(
                  s"[$ErrDeviceNotFound] ${err.message}\n${FriendMessageTool.deviceCandidates(peers)}"
                )))
              case Right(peer) =>
                // 契约 v2 ①：目标走**路径**（`POST /api/relay/{target_device_id}/mail`），
                // body = 契约五键载荷本体。解析出的对端 **deviceId** 是唯一目标 ⇒ 本腿
                // 天然定向（无 fan-out、无「找不到就广播」兜底分支，v2 ②）。
                ns.relayClientOpt match
                  case None =>
                    IO.pure(Left(ToolError(
                      "Cannot send device mail: the NebLink relay client is not initialized (not logged in to a NebLink server?)."
                    )))
                  case Some(client) =>
                    val payload = DeviceMail.payload(message, id.deviceName, id.deviceId)
                    client.relayAgentMail(peer.deviceId, payload).flatMap {
                      case Right(serverId) =>
                        // ④ 回执：登记 pending ack（eventId = "message-<id>"），由隧道 ack
                        // 帧关联；超时腿在 DeviceMailAck 内（WARN + 审计行，禁静默）。
                        DeviceMailAck.await(peer.deviceId, serverId).flatMap { eventId =>
                          auditDeviceMailSend(ns, peer.deviceId, message, ctx).as(Right(
                            s"Message sent to device '${peer.deviceName}' — the frozen agent_mail payload " +
                              s"(type=${DeviceMail.TypeAgentMail}, to_nebula=true) is on the relay route " +
                              s"/api/relay/${peer.deviceId}/mail; the peer's Nebula session will be injected at " +
                              s"its next turn boundary. Awaiting ack $eventId."
                          ))
                        }
                      case Left(err) =>
                        IO.pure(Left(ToolError(
                          s"Device '${peer.deviceName}' could not be reached for agent mail: $err — nothing was sent."
                        )))
                    }
          yield result
        case _ =>
          IO.pure(Left(ToolError(
            "Device messaging is unavailable: NebLink/Dropbox services are not initialized (is NebLink enabled?)."
          )))

  /** ④ 发送腿审计（一条一行，`RelayExecAudit` 同族 = 设备通道审计的既有落面）。
    * `sourceDeviceId` = 本机（下发方），`targetDeviceId` = 对端设备。审计失败不影响
    * 发送（`RelayExecAudit.record` 既有语义：吞异常 + WARN）。 */
  private def auditDeviceMailSend(
      ns: NeblinkService,
      targetDeviceId: String,
      message: String,
      ctx: ToolContext
  ): IO[Unit] =
    ns.identity
      .flatMap(src =>
        RelayExecAudit.record(
          sourceDeviceId = src.deviceId,
          targetDeviceId = targetDeviceId,
          // 契约 v2.1：本腿已无「设备数据通道」形态（v1 面已弃）——出站走服务端
          // relay 端点（`POST /api/relay/{target}/mail`），故 `via` 与既有 relay 审计同值。
          via = "relay",
          action = "Mail.device.send",
          command = s"type=${DeviceMail.TypeAgentMail}; to_nebula=true; chars=${message.length}",
          projectRoot = ctx.projectRoot,
          cwd = Option(System.getProperty("user.dir")).getOrElse("")
        )
      )
      .handleErrorWith(_ => IO.unit)

  /** 腿①（Nebula → 项目分发器）：保留既有内核（`ProjectActor.TriggerDispatcher`）。 */
  private def deliverToProject(name: String, message: String, mailType: String, ctx: ToolContext): IO[Either[ToolError, String]] =
    routeToProject(name, message, mailType, ctx).flatMap {
      case Some(r) => IO.pure(r)
      case None =>
        IO.pure(Left(ToolError(
          s"Project '$name' is not mounted — Mail to a project triggers its dispatcher (ProjectActor.TriggerDispatcher). " +
            "Mounted projects mount at gateway startup; re-mount / restart, or check the exact name."
        )))
    }

  /** 腿③（分发器 → root）：**解析到真正的 Nebula root 会话**（追加条款②，2026-09-12）。
    * 硬禁三种静默行为：① 回落成发信者自身 ② 落到非 Nebula 的 Root 会话
    * ③ 解析失败仍报成功——解析不到即**显式报错**（并不指明合法地址面）。 */
  private def deliverToNebulaRoot(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      mailType: String,
      ctx: ToolContext,
      system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))
      case Some(res) =>
        resolveNebulaRootRef(res, senderSessionId, ctx.rootSessionId).flatMap {
          case Some((sid, ref)) =>
            sendMail(ref, NebulaAgentName, message, blocks, mailType, ctx, system).flatMap {
              case Right(_) => onMailDelivered(senderSessionId, sid, NebulaAgentName, message, ctx).as(Right(
                  s"Message sent to Nebula (root session ${sid.take(8)}). The root agent will process it."
                ))
              case Left(err) => IO.pure(Left(err))
            }
          case None => IO.pure(Left(nebulaUnresolvedError(senderSessionId)))
        }
  end deliverToNebulaRoot

  private def nebulaUnresolvedError(senderSessionId: String): ToolError =
    ToolError(
      "Cannot resolve the Nebula root session (NEBULA_ROOT_UNRESOLVED). No Mail was delivered — this is an explicit " +
        "failure, not a silent success. Expected: exactly one live Root session whose session meta names agent 'Nebula' " +
        s"and which is not the sender itself (${
            if senderSessionId.isEmpty then "sender session unknown" else senderSessionId.take(8)
          }). Check that the gateway's Nebula window session is running, then retry; your legal address face is " +
        s"$dispatcherFace."
    )


  // ============================================================
  // Legacy queue layer: persisted FIFO, drained one-per-turn
  // ============================================================
  // 退役登记（delivery 退役批，2026-09-15 作者裁定 (b)；**未摘除面**）：
  // `delivery` 字段保留、queue 模式退役 ⇒ 本层自本批起**在本工具面零生产调用方**
  // （`call` 的单点前置闸已显式拒绝一切非设备腿的 `delivery="queue"`，`layeredRoute`
  // 结构上不再持有 `delivery` 参数）。本层**保留不动**（禁摘除）：① spec 直调
  // （`MailToolRootSenderSpec` / `MailQueueNebulaSpec` / `ColdQueueActivationSpec` /
  // `MailIdleGateWiringSpec` —— 它们钉的是 idle-gate / 冷激活 / 根解析等**已落契约**，
  // 与本批退役面正交）；② 在库消费者仍在（`AgentActor` 的 legacy 队列排空、
  // `RestApiRoutes` 的队列检视/取消端点 —— 本批禁碰）。摘除属另批另议。

  /** #28 阶段 0 §3.2：Mail(→project) 触发分发器（试点期新旧并存）。
    * queue/immediate 两个入口共用——address 是已挂载 project → 返回
    * Some(结果)（已处理：触发 ProjectActor.TriggerDispatcher 或挂载错误）；
    * 非 project 名 → None（调用方继续旧路由）。Mail 仅触发、无回报——
    * 节点结果沿 out 边投递（§2.7），不经 Mail 回传。 */
  private def routeToProject(
      address: String,
      message: String,
      mailType: String,
      ctx: ToolContext
    ): IO[Option[Either[ToolError, String]]] =
    ProjectRuntimeRegistry.get(address).flatMap {
      case None => IO.pure(None)
      case Some(rt) =>
        rt.actorRef match
          case None =>
            IO.pure(Some(Left(ToolError(s"Project '$address' has no mounted ProjectActor — re-mount it"))))
          case Some(ref) =>
            val rootSid = ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")
            // 腿① 来源标注（bluebubble 批 2026-09-12）：发信方随触发消息落到分发器
            // 会话的注入气泡顶栏（source 仍 = task，D-5 裁定：值不改名）。
            // mailbadge 批（2026-09-13，选项 C）：**只有本腿**置 `intake` ——
            // 分发器收件面是作者口径「蓝色气泡标注 Mail」的落点；`source` 保持
            // `"task"` 不动（桥的消费计数单点 `ProjectActor:622` 与 `idleSince`
            // 30 min 空闲窗逐行不变）。
            mailAttribution(mailType, ctx, Some(InjectionAttribution.IntakeMail)).flatMap { attribution =>
              (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(message, rootSid, ProjectActor.SourceTask, Some(attribution))).void
                .as(Some(Right(s"Project '$address' dispatcher triggered")))
            }
    }

  private[tools] def deliverQueue(
      address: String,
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    TeamSessionRegistry.teamOfSession(senderSessionId).flatMap {
      case None =>
        if address == NebulaAgentName && senderName != NebulaAgentName then
          canMailNebula(ctx, senderName, senderSessionId).flatMap { canMail =>
            if canMail then resolveAndQueue(address, message, mailType, imagePaths, ctx, system, senderSessionId)
            else IO.pure(Left(nebulaDeniedError(senderName)))
          }
        else resolveAndQueue(address, message, mailType, imagePaths, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName, ctx).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None        => resolveAndQueue(address, message, mailType, imagePaths, ctx, system, senderSessionId)
        }
    }
  end deliverQueue

  /** Resolve target session (team name → lead, or short name) then queue the mail. */
  private def resolveAndQueue(
      address: String,
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem,
      senderSessionId: String
    ): IO[Either[ToolError, String]] =
    for
      teamOpt <- EntityLoader.loadTeam(address)
      result <- teamOpt match
        case Some(team) =>
          for
            leadSidOpt <- TeamSessionRegistry.findTeamAgent(address, team.lead)
            r <- leadSidOpt match
              case Some(targetSid) => queueToSession(targetSid, team.lead, message, mailType, imagePaths, ctx, system, senderSessionId)
              case None =>
                IO.pure(Left(ToolError(s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first.")))
          yield r
        case None =>
          for
            // Mail(→project) 路由（#28 阶段 0，§3.2 试点期新旧并存）：
            // project 名 → ProjectActor.TriggerDispatcher（触发分发器会话）。
            // 团队名路由优先（旧体系照常）；project 名兜底（新体系试点）。
            // Mail 仅做触发、无回报——节点结果沿 out 边投递（§2.7），不靠 Mail。
            pr <- routeToProject(address, message, mailType, ctx).flatMap {
              case Some(r) => IO.pure(r)
              case None =>
                for
                  senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
                  sr <- senderTeamOpt match
                    case None if address != NebulaAgentName =>
                      IO.pure(Left(ToolError(TeamOnlyRoutingError)))
                    case _ =>
                      for
                        targetRes <- ctx.sharedResources match
                          case Some(res) => TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
                          case None      => IO.pure(Right(None))
                        sr2 <- targetRes match
                          case Left(ambErr) => IO.pure(Left(ToolError(ambErr)))
                          case Right(Some(targetSid)) =>
                            queueToSession(targetSid, address, message, mailType, imagePaths, ctx, system, senderSessionId)
                          case Right(None) if address == NebulaAgentName =>
                            queueToNebula(message, mailType, imagePaths, ctx, system, senderSessionId, address, None)
                          case Right(None) => mailNotFound(address)
                      yield sr2
                yield sr
            }
          yield pr
    yield result

  /**
   * Queue-mode routing to the Nebula root agent (issue #312).
   *
   * resolveSessionId's contract for "Nebula" is Right(None) = "route to the
   * root agent directly" (see its "team/Nebula" branch) — but the caller must
   * locate the root session: the Nebula root is a Root-kind AgentRecord in the
   * agent registry, NOT a team session in sessionMap. Before this fix a queue
   * Mail to "Nebula" fell into mailNotFound with a misleading "only Team Lead
   * can communicate with Nebula" error even when the sender IS the Team Lead
   * (Manager→Nebula queue rejected; immediate mode was fine because it
   * resolves the actor by name via system.resolve). Permission is already
   * enforced upstream: deliverQueue runs checkTeamScope first, so only
   * canMailNebula senders reach here with address == "Nebula".
   */
  private[tools] def queueToNebula(
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem,
      senderSessionId: String,
      address: String,
      chainId: Option[String]
    ): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case Some(res) =>
        resolveNebulaRootSession(res, senderSessionId, ctx.rootSessionId).flatMap {
          case Some(nebulaSid) =>
            queueToSession(nebulaSid, address, withChainAnnotation(message, chainId), mailType, imagePaths, ctx, system, senderSessionId)
          case None => IO.pure(Left(nebulaUnresolvedError(senderSessionId)))
        }
      case None => IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))

  /** The Nebula root agent's sessionId — **the true Nebula root**, not "any Root record"
    * (追加条款② 2026-09-12). 解析序（两档，任一档解析不出 ⇒ None ⇒ 调用方显式报错）：
    *   ① **preferredRootSid**（调用方会话的 `ctx.rootSessionId`）——spawn 本分发器会话的
    *      那个 root 会话，确定性最高、零歧义；命中失败**不**静默改选别的 Root 会话；
    *   ② 无 preferred 时按 **session meta**（`agentName == "Nebula"`）判定，且必须唯一。
    * 两档都**排除发信者自身**（硬禁「回落成发信者自身」）。 */
  private[tools] def resolveNebulaRootSession(
      res: SharedResources,
      senderSessionId: String,
      preferredRootSid: Option[String] = None
  ): IO[Option[String]] =
    resolveNebulaRoots(res, senderSessionId, preferredRootSid).map(_.headOption.map(_._1))

  /** Root records eligible as "the Nebula root"（判据见 [[resolveNebulaRootSession]] 文档）。
    * 返回 0 或 ≥2 项都由调用方判为「解析不出」——**绝不**静默挑一条。
    *
    * 可见性（device-mail 批，2026-09-15）：`private` → `private[nebflow]` —— 设备邮件
    * 收件腿（`nebflow.neblink.DeviceMailInbox`）注入**本机 Nebula 会话**时必须走本
    * **唯一解析单点**（禁第二份同表达式：两份必然漂移）。零语义改动、零授权面改动。 */
  private[nebflow] def resolveNebulaRoots(
      res: SharedResources,
      senderSessionId: String,
      preferredRootSid: Option[String]
  ): IO[List[(String, ActorRef[AgentCommand])]] =
    res.agentRegistry.get.flatMap { reg =>
      val rootRecs = reg.values.toList
        .filter(rec => rec.kind == AgentKind.Root && rec.ref != null && rec.sessionId != senderSessionId)
        .sortBy(_.sessionId)
      preferredRootSid.map(_.trim).filter(_.nonEmpty) match
        case Some(pid) =>
          // 档①：确定性解析（本分发器会话的触发 root）。找不到 ⇒ 空（不回落、不改选）。
          IO.pure(rootRecs.find(_.sessionId == pid).map(rec => List((rec.sessionId, rec.ref))).getOrElse(Nil))
        case None =>
          // 档②：session meta 判定（agentName == "Nebula"）——要求唯一。
          val store = res.sessionStore
          if store == null then IO.pure(Nil)
          else
            rootRecs.flatTraverse { rec =>
              store.getSessionMeta(rec.sessionId).map { meta =>
                if meta.flatMap(_.agentName).contains(NebulaAgentName) then List((rec.sessionId, rec.ref)) else Nil
              }
            }
    }

  private def resolveNebulaRootRef(
      res: SharedResources,
      senderSessionId: String,
      preferredRootSid: Option[String]
  ): IO[Option[(String, ActorRef[AgentCommand])]] =
    resolveNebulaRoots(res, senderSessionId, preferredRootSid).map {
      case List(one) => Some(one)
      case _         => None // 0 命中或 ≥2 命中（歧义）都算解析不出 → 显式报错
    }

  /** Persist to MailQueueStore, activate target, send MailQueued command.
    * private[tools] for ColdQueueActivationSpec (issue #22). */
  private[tools] def queueToSession(
      sessionId: String,
      shortName: String,
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem,
      senderSessionId: String
    ): IO[Either[ToolError, String]] =
    val senderName = ctx.agentDef.map(_.name).getOrElse("Nebula")
    val item = MailQueueStore.MailQueueItem(
      id = s"mail-q-${java.util.UUID.randomUUID().toString.take(8)}",
      from = senderName,
      fromSession = senderSessionId,
      message = message,
      `type` = mailType,
      timestamp = System.currentTimeMillis(),
      // D6: persist paths (not base64) — re-read + re-compress at drain time
      imagePaths = imagePaths
    )
    (ctx.sharedResources, ctx.actorSystem) match
      case (Some(resources), Some(actorSystem)) =>
        for
          // 1. Persist to queue (survives restart)
          _ <- MailQueueStore.append(sessionId, item)
          // 2. Record in mailbox history
          _ <- onMailDelivered(senderSessionId, sessionId, shortName, message, ctx)
          // 3. Get or activate the target actor (#22: with liveness probe —
          // a stale dead ref would swallow MailQueued into an unconsumed queue)
          refOpt <- MailTool.this.liveActorOrActivate(sessionId, resources, actorSystem, ctx)
          // 4. Send MailQueued command (triggers drain on the live actor)
          _ <- refOpt.traverse_(_ ! AgentCommand.MailQueued(item, senderSessionId))
          _ <- IO {
            if refOpt.isDefined then
              logger.info(
                s"[mail] queue mail delivered to $shortName (session=${sessionId.take(8)}, from=$senderName) — agent active, drain triggered"
              )
          }
          // 4+5. Emit WS event regardless of activation outcome (the item IS
          // persisted either way — the frontend should see the queue grow)
          pendingCount <- MailQueueStore.size(sessionId)
          _ <- emitWsEvent(ctx, Json.obj(
            "type" -> "mailQueued".asJson,
            "sessionId" -> sessionId.asJson,
            "from" -> senderName.asJson,
            "to" -> shortName.asJson,
            "preview" -> message.take(200).asJson,
            "pendingCount" -> pendingCount.asJson,
            "timestamp" -> item.timestamp.asJson
          ))
        yield refOpt match
          case Some(_) =>
            Right(s"Message queued to $shortName. Will be processed after current work completes (position #$pendingCount in queue).")
          case None =>
            // #22 honesty: activation failed (session meta or agent def not
            // loadable). The item IS on disk and will drain on the agent's
            // next successful activation — but claiming "will be processed"
            // unconditionally was a silent-loss lie.
            Left(ToolError(
              s"Message persisted to $shortName's queue (position #$pendingCount), but cold activation FAILED — session metadata or agent definition not found for session ${sessionId.take(8)}. " +
                s"The item will drain once the agent is loadable (check the agent exists in its team). See logs: \"[mail] activation failed\"."
            ))
      case _ =>
        IO.pure(Left(ToolError(s"Cannot deliver queue mail to '$shortName': missing resources")))
  end queueToSession

  private def emitWsEvent(ctx: ToolContext, event: Json): IO[Unit] =
    ctx.wsSend match
      case Some(send) => send(event).handleErrorWith(_ => IO.unit)
      case None       => IO.unit

  // ============================================================
  // Normal mode: async delivery
  // ============================================================

  private def deliverToAddress(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      mailType: String,
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    system.resolve[AgentCommand](address).attempt.flatMap {
      case Right(ref) => sendMail(ref, address, message, blocks, mailType, ctx, system)
      case Left(err) => IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
    }

  private def deliverToShortName(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      mailType: String,
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    TeamSessionRegistry.teamOfSession(senderSessionId).flatMap {
      case None =>
        if address == NebulaAgentName && senderName != NebulaAgentName then
          canMailNebula(ctx, senderName, senderSessionId).flatMap { canMail =>
            if canMail then deliverShortNameUnscoped(address, message, blocks, mailType, ctx, system, senderSessionId)
            else IO.pure(Left(nebulaDeniedError(senderName)))
          }
        else deliverShortNameUnscoped(address, message, blocks, mailType, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName, ctx).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None =>
            deliverShortNameUnscoped(address, message, blocks, mailType, ctx, system, senderSessionId)
        }
    }
  end deliverToShortName

  /**
   * Who may mail Nebula directly: **a project dispatcher (engine-side role judge
   * `ctx.isDispatcher`)** — the 2026-09-12 细则 narrows "who can reach root" to that
   * single role — OR a registered team Manager (managerMap) OR any team lead by
   * definition (agent.json lead of a mounted/defined team). The name-based fallback
   * covers fork/temporary sessions — a forked Manager keeps the Manager agentDef
   * (and its lead role) but its sessionId is not registered in managerMap, so
   * sid-only checks would wrongly reject it.
   *
   * The dispatcher judge is deliberately an **engine-side identity**, not a
   * `senderName` string match (a name-based judge breaks the moment an agent is
   * renamed, and can be bypassed).
   */
  private[tools] def canMailNebula(ctx: ToolContext, senderName: String, senderSessionId: String): IO[Boolean] =
    if ctx.isDispatcher then IO.pure(true)
    else
      TeamSessionRegistry.isManager(senderSessionId).flatMap { isMgr =>
        if isMgr then IO.pure(true)
        else if senderName.isEmpty then IO.pure(false)
        else EntityLoader.listTeams().map(_.values.exists(_.lead == senderName))
      }

  /** 「不能给 root 发」的显式归因文案（R-15）：对**项目分发器**身份，旧文案
    * 「You are a team worker」是错误归因（它不是 team worker）——分发器走
    * `ctx.isDispatcher` 判据本就不会命中本分支；此处文案按身份分档。 */
  private def nebulaDeniedError(senderName: String): ToolError =
    val who = if senderName.isEmpty then "This sender" else s"'$senderName'"
    ToolError(
      s"Cannot mail Nebula directly: $who is not a project dispatcher and not a team lead. " +
        "Report to your Team Lead / Manager via Mail, and let it escalate to Nebula. " +
        "Your legal address face is not \"Nebula\"."
    )

  private[tools] def deliverShortNameUnscoped(
    address: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem,
    senderSessionId: String
  ): IO[Either[ToolError, String]] =
    for
      teamOpt <- EntityLoader.loadTeam(address)
      result <- teamOpt match
        case Some(team) =>
          for
            leadSidOpt <- TeamSessionRegistry.findTeamAgent(address, team.lead)
            r <- leadSidOpt match
              case Some(targetSid) =>
                for
                  res <- deliverToSession(targetSid, team.lead, message, blocks, mailType, ctx, system)
                  _ <- res match
                    case Right(_) => onMailDelivered(senderSessionId, targetSid, team.lead, message, ctx)
                    case Left(_) => IO.unit
                yield res
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first."
                    )
                  )
                )
          yield r

        case None =>
          // Mail(→project) 路由（§3.2，immediate 路径对称）——先于旧 short-name 解析。
          for
            pr <- routeToProject(address, message, mailType, ctx).flatMap {
              case Some(r) => IO.pure(r)
              case None =>
                // Not a team name — short agent name. Routable only for senders
                // with a team context (same-team priority); outside a team, only
                // team names and "Nebula" are valid (user ruling 08-24 — the
                // team/agent escape hatch is closed for root senders).
                for
                  senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
                  sr <- senderTeamOpt match
                    case None if address != NebulaAgentName =>
                      IO.pure(Left(ToolError(TeamOnlyRoutingError)))
                    case _ =>
                      for
                        targetRes <- ctx.sharedResources match
                          case Some(res) => TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
                          case None => IO.pure(Right(None))
                        sr2 <- targetRes match
                          case Left(ambErr) => IO.pure(Left(ToolError(ambErr)))
                          case Right(Some(targetSid)) =>
                            for
                              res <- deliverToSession(targetSid, address, message, blocks, mailType, ctx, system)
                              _ <- res match
                                case Right(_) => onMailDelivered(senderSessionId, targetSid, address, message, ctx)
                                case Left(_) => IO.unit
                            yield res
                          case Right(None) =>
                            val isNebulaTarget = address == NebulaAgentName || address.endsWith(s"/$NebulaAgentName")
                            if isNebulaTarget then
                              // 追加条款②（2026-09-12）：**必须**落到真正的 Nebula root 会话。
                              // 旧实现取 `getParentActor(senderSessionId)`（last-writer-wins 注册）
                              // 并在缺失时取「第一条 Root 记录」——两条都可能落到发信者自身或
                              // 其它 Root 会话，且解析失败仍返回成功文案（静默）。现改为单点解析
                              // `resolveNebulaRootRef`（session meta agentName == "Nebula" ∧ 排除
                              // 发信者自身 ∧ 唯一），解析不到即**显式报错**，不投递。
                              ctx.sharedResources match
                                case Some(res) =>
                                  resolveNebulaRootRef(res, senderSessionId, ctx.rootSessionId).flatMap {
                                    case Some((sid, ref)) =>
                                      for
                                        res <- sendMail(ref, NebulaAgentName, message, blocks, mailType, ctx, system)
                                        _ <- res match
                                          case Right(_) => onMailDelivered(senderSessionId, sid, NebulaAgentName, message, ctx)
                                          case Left(_)  => IO.unit
                                      yield res
                                    case None => IO.pure(Left(nebulaUnresolvedError(senderSessionId)))
                                  }
                                case None =>
                                  IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))
                            else mailNotFound(address)
                            end if
                      yield sr2
                yield sr
            }
          yield pr
    yield result

  /** Marker a team's rules.md can set to opt in to cross-team explicit Mail. */
  private val CrossTeamMailMarker = "allow-cross-team-mail: true"

  /**
   * Explicit "team/agent" addresses targeting another team are blocked by
   * default for non-lead senders (decision 20) — the escalation path is
   * Manager → Nebula. A team opts in by writing
   * `<!-- allow-cross-team-mail: true -->` in its rules.md. Leads (any team
   * Manager / lead — same judgment as canMailNebula) always pass.
   *
   * Unaffected: same-team explicit routes, short names, and the Nebula root
   * (no team context — it never reaches checkTeamScope).
   */
  private def checkCrossTeamExplicitRoute(
    address: String,
    senderTeam: String,
    isLead: Boolean
  ): IO[Option[String]] =
    if !address.contains("/") then IO.pure(None)
    else
      val targetTeam = address.substring(0, address.indexOf('/'))
      if targetTeam == senderTeam || isLead then IO.pure(None)
      else
        EntityLoader.loadTeamRules(senderTeam).map { rules =>
          if rules.contains(CrossTeamMailMarker) then None
          else
            Some(
              s"Cross-team Mail to '$address' is blocked by default. You are in team '$senderTeam'. " +
                "Ask your Manager to escalate to Nebula, or have the team opt in via its rules.md " +
                "(allow-cross-team-mail: true)."
            )
        }

  /** Visible for tests (package-private). */
  private[tools] def checkTeamScope(
      address: String,
      teamName: String,
      senderSessionId: String,
      senderName: String,
      ctx: ToolContext
    ): IO[Option[String]] =
    for
      teamOpt <- EntityLoader.loadTeam(address)
      canNebula <- canMailNebula(ctx, senderName, senderSessionId)
      crossTeam <- checkCrossTeamExplicitRoute(address, teamName, canNebula)
    yield teamOpt match
      case Some(_) if address == teamName =>
        None
      case Some(_) =>
        Some(s"Cannot mail outside your team. You are in team '$teamName'. Use your Manager to escalate to Nebula.")
      case None if address == NebulaAgentName && canNebula =>
        None
      case None if address == NebulaAgentName =>
        Some("Cannot mail Nebula directly. Use Mail(\"manager\", ...) to report to your Team Lead.")
      case None =>
        crossTeam

  /** Deliver to a session — ensure actor exists, then send Mail. */
  private def deliverToSession(
    sessionId: String,
    shortName: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    (ctx.sharedResources, ctx.actorSystem) match
      case (Some(resources), Some(actorSystem)) =>
        for
          // Check if actor already running (#22: probe liveness — dead cached
          // ref would swallow ImmediateInput into an unconsumed queue)
          refOpt <- MailTool.this.liveActorOrActivate(sessionId, resources, actorSystem, ctx)
          result <- refOpt match
            case Some(ref) => sendMail(ref, shortName, message, blocks, mailType, ctx, system)
            case None =>
              TeamSessionRegistry.getParentActor(sessionId).flatMap {
                case Some(ref) => sendMail(ref, shortName, message, blocks, mailType, ctx, system)
                case None => mailNotFound(shortName)
              }
        yield result
      case _ =>
        IO.pure(Left(ToolError(s"Cannot activate session for '$shortName': missing resources")))

  /** getRunningActor + liveness probe (#22): a cached ref whose actor loop
    * has exited (crash / TTL / stop whose deathwatch cleanup missed) still
    * accepts offers into an unconsumed queue — a queue-mode Mail delivered to
    * it persists, sends MailQueued into the void, and NEVER activates the
    * agent: no error, no retry, silent loss (issue #22's mechanism). Probe
    * the actor system registry; dead ref → reactivate. */
  private def liveActorOrActivate(
      sessionId: String,
      resources: SharedResources,
      system: ActorSystem,
      ctx: ToolContext
  ): IO[Option[ActorRef[AgentCommand]]] =
    TeamSessionRegistry.getRunningActor(sessionId).flatMap {
      case Some(ref) =>
        system.isAlive(ref.path).flatMap {
          case true => IO.pure(Some(ref))
          case false =>
            logger.warn(
              s"[mail] stale actor ref for session=${sessionId.take(8)} — actor dead but actorMap kept it; reactivating (issue #22)"
            ) *> activateAgent(sessionId, resources, system, ctx)
        }
      case None =>
        // #407 fix（E2E 实证）：用户消息触发的 team 成员 spawn（ensureRootAgent）
        // 只写统一 agentRegistry、不写 TeamSessionRegistry.actorMap——只查 actorMap
        // 会误判「无 live actor」→ activateAgent 重复 spawn（双活：旧 actor 在途
        // turn 与恢复 actor 并存，queue Mail 的 gate 投递错位、turn 完成不 drain）。
        // 回退统一注册表找 live record（ref 非 null 且 actor 存活），命中则复用。
        resources.agentRegistry.get.flatMap { reg =>
          reg.get(sessionId).filter(_.ref != null) match
            case Some(rec) =>
              system.isAlive(rec.ref.path).flatMap {
                case true  => IO.pure(Some(rec.ref))
                case false => activateAgent(sessionId, resources, system, ctx)
              }
            case None => activateAgent(sessionId, resources, system, ctx)
        }
    }

  /** Activate a team agent session by spawning an AgentActor (replaces FlowAgentActivator).
    * Package-visible for the lifecycle spec (respawn history + death watch). */
  private[tools] def activateAgent(
    sessionId: String,
    resources: SharedResources,
    actorSystem: ActorSystem,
    ctx: ToolContext
  ): IO[Option[ActorRef[AgentCommand]]] =
    for
      sessionOpt <- resources.sessionStore.getSessionMeta(sessionId)
      _ <- IO {
        if sessionOpt.isEmpty then
          logger.warn(s"[mail] activation failed: session ${sessionId.take(8)} not found in session store (issue #22 observability)")
      }
      refOpt <- sessionOpt.traverse_ { session =>
        val agentName = session.agentName.getOrElse("")
        for
          // P2: resolve the Nebula root session (permission-policy anchor) first
          // so the spawned agent inherits the root's policy bucket and its
          // InteractionRequests render in the Nebula window.
          parentSidOpt <- TeamSessionRegistry.parentSessionOf(session.flowName.getOrElse(""))
          rootSid = parentSidOpt.getOrElse(session.id)
          // Block 0 registration chain: member → its Manager; Manager → mounting
          // root; unknown → None (registration falls back to the caller sid).
          parentSid <- TeamSessionRegistry.parentForRecord(session.id)
          // permshield S1（2026-09-13）：有效档位 = 应用级全局持久值，走**唯一入口**
          // `SharedResources.effectiveSafetyMode`。既不读 `session.safetyMode`
          // （`_index.json` 的盘上遗留值：全局 confirm-edits 时被 Mail 激活的成员会
          // 继承盘上 auto-all），也不再有会话覆盖面。
          safetyMode <- resources.effectiveSafetyMode.map(nebflow.core.SafetyMode.toString)
          entryOpt <- session.flowName match
            case Some(teamName) => EntityLoader.loadTeamAgent(teamName, agentName)
            case None => EntityLoader.loadAgent(agentName)
          _ <- IO {
            if entryOpt.isEmpty then
              logger.warn(
                s"[mail] activation failed: agent def '$agentName' not loadable (team=${session.flowName.getOrElse("-")}) for session ${sessionId.take(8)} (issue #22 observability)"
              )
          }
          _ <- entryOpt.traverse_ { entry =>
            val agentDef = entry.toAgentDef
            // Route team agent events with a "team-" prefixed nodeSessionId so
            // the frontend can distinguish Mail-activated team agents from flow
            // agents (whose nodeSessionId is "dag-..."). Without this prefix,
            // the flow interceptor in ws.js would claim these events and render
            // team agent activity into a flow popup.
            //
            // protocol.scala stamps every subagent event with nodeSessionId =
            // sessionId, so the old "if nodeSessionId absent" guard was always
            // true and the "team-" prefix never applied. Overwrite explicitly,
            // except for ids already stamped by an inner wrapper:
            //  - "delegate-..." stamped by DelegateTool.routeWsSend (sub-agents
            //    the team agent spawned via Delegate keep their own prefix so
            //    their events route to the delegate popup, not here)
            //  - "subtask-..." stamped by SubTaskTool.routeWsSend (same for
            //    SubTask workers spawned by team members)
            //  - "team-..." stamped by an INNER MailTool wrapper — in nested
            //    Mail activation (Nebula→Manager→Frontend) the innermost
            //    wrapper stamps the true source; outer wrappers must preserve
            //    it, otherwise the frontend strips the outer team- prefix and
            //    marks the wrong agent (Manager) running while the real
            //    sub-agent (Frontend) stays idle
            val teamWsSend: Json => IO[Unit] = (json: Json) =>
              val underlying = ctx.wsSend.getOrElse((_: Json) => IO.unit)
              val nsidOpt = json.hcursor.downField("nodeSessionId").as[String].toOption
              val stamped = nsidOpt match
                case Some(nsid)
                    if nsid.startsWith("delegate-") || nsid.startsWith("subtask-") || nsid.startsWith("team-") =>
                  json
                case _ =>
                  json.asObject
                    .map(obj => Json.fromJsonObject(obj.add("nodeSessionId", s"team-${session.id}".asJson)))
                    .getOrElse(json)
              underlying(stamped)
            // Session history for the (re)spawn: team agents are LONG-LIVED
            // and their sessions persist across activations — a respawn
            // without the stored messages is an amnesiac agent (turn counts
            // reset, prior context lost). Same pattern as the root-agent
            // restore (WebSocketRoutes.ensureRootAgent).
            val historyIo: IO[List[Message]] = resources.sessionStore
              .loadMessagesForSession(session.id)
              .handleError { e =>
                logger.warn(s"activateAgent: history load failed for ${session.id}: ${e.getMessage}")
                List.empty[Message]
              }
            for
              history <- historyIo
              // Actor name = session.id pins the event contract
              // "agentId == sessionId" (documented at the getActiveAgents
              // reply: restored bg-agent entries key by sessionId so they
              // merge with subsequent realtime events). Live subagent events
              // key the frontend map by ctx.self.path.name, and the old
              // "mail-<sid8>" name broke the equality for Mail-activated
              // team agents: after a browser refresh the restore reply
              // (agentId=sid) plus the next live agentStart
              // (agentId=mail-<sid8>) filed TWO running rows for ONE
              // session — the Teams panel double-entry ghost. Delegate /
              // SubTask / DAG spawns already name actors by their
              // nodeSessionId; this aligns the Mail path with them.
              ref <- actorSystem.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = resources,
                  wsSend = teamWsSend,
                  depth = 1,
                  parentRef = ctx.agentActorRef,
                  sessionId = Some(session.id),
                  sessionName = Some(session.name),
                  initialMessages = history,
                  projectRoot = Some(ctx.projectRoot),
                  safetyMode = safetyMode,
                  rootSessionId = rootSid,
                  expectsMail = entry.name != "Manager"
                ),
                session.id
              )
              // Death watch: Mail-spawned team agents live OUTSIDE
              // FlowTreeActor's watch system, so their death was completely
              // silent — actorMap kept the dead ref (Mails to it vanished),
              // agentRegistry kept the record, busyMap kept the last turn's
              // busy flag, and getActiveAgents reported a running ghost for
              // hours. This watcher fires the cleanup FlowTreeActor runs for
              // its own spawns: unregister + clear busy + leave a log trail
              // (the silent death itself was the observability gap).
              _ <- actorSystem.spawn(
                MailTool.teamAgentDeathWatch(session.id, entry.name, ref, resources),
                s"deathwatch-${session.id.take(8)}"
              )
              _ <- TeamSessionRegistry.registerActor(session.id, ref)
              _ <- resources.agentRegistry.update(
                _ + (
                  session.id -> AgentRecord(
                    sessionId = session.id,
                    ref = ref,
                    kind = AgentKind.Team,
                    rootSessionId = rootSid,
                    parentRef = ctx.agentActorRef,
                    // Block 0 registration chain (supervision trio §B2): a
                    // member's parent is its team Manager; the Manager's parent
                    // is the mounting root. Fallback = the activating caller.
                    parentSessionId = parentSid.getOrElse(ctx.sessionId.getOrElse(""))
                  )
                )
              )
              // Restart recovery: if mail-queue.json has pending items from a
              // previous session, send MailQueued to trigger drain.
              _ <- MailQueueStore
                .load(session.id)
                .flatMap(items => items.headOption.traverse_(head => ref ! AgentCommand.MailQueued(head, "")))
                .start
                .void
            yield ref
            end for
          }
        yield ()
        end for
      }
      ref <- TeamSessionRegistry.getRunningActor(sessionId)
    yield ref

  /**
    * Death watch for Mail-activated team agents (deep follow-up #1,
    * 2026-08-17): MailTool spawns these actors outside FlowTreeActor's
    * watch system, so their death was completely silent — actorMap kept
    * the dead ref (subsequent Mails to it vanished into a dead queue),
    * agentRegistry kept the record, busyMap kept the last turn's busy
    * flag, and getActiveAgents reported a running ghost for hours
    * (the snapshot source of the Teams panel bare running rows).
    *
    * The watcher mirrors the cleanup FlowTreeActor runs for its own
    * spawns (unregisterActor + resume scheduling) plus the piece that
    * handler lacks — clearing the busy flag — and leaves a log trail:
    * silent death was itself the observability defect.
    *
    * Package-visible for the deathwatch spec.
    */
  private[tools] def teamAgentDeathWatch(
      sessionId: String,
      agentName: String,
      watched: ActorRef[AgentCommand],
      resources: SharedResources
  ): Behavior[SystemSignal] =
    Behaviors.setup { wctx =>
      wctx.watch(watched).map { _ =>
        new Behavior[SystemSignal]:
          def receive(ctx: ActorContext[SystemSignal], msg: SystemSignal): IO[Behavior[SystemSignal]] =
            IO.pure(this)

          override def onSignal(
              ctx: ActorContext[SystemSignal],
              signal: SystemSignal
          ): IO[Behavior[SystemSignal]] =
            signal match
              case SystemSignal.Terminated(_) =>
                TeamSessionRegistry.unregisterActor(sessionId, resources) *>
                  TeamSessionRegistry.markIdle(sessionId) *>
                  logger.info(
                    s"team agent actor stopped: agent=$agentName session=$sessionId — registry unregistered, busy cleared"
                  ).as(Behaviors.stopped[SystemSignal])
      }
    }
  end teamAgentDeathWatch

  private def sendMail(
    ref: ActorRef[AgentCommand],
    label: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderName = ctx.agentDef.map(_.name).getOrElse("Nebula")
    val senderSid = ctx.sessionId.getOrElse("")
    for
      // Resolve the sender's team so the injected bubble can show "team/agent" attribution.
      teamOpt <- TeamSessionRegistry.teamOfSession(senderSid)
      _ <- ref ! AgentCommand.ImmediateInput(
        message,
        // G3 attachments: blocks already contain the message text as the first
        // Text block (AgentActor drops `text` when blocks are present).
        blocks = blocks,
        source = Some("mail"),
        eventType = Some(mailType.toLowerCase),
        sender = Some(senderName),
        senderTeam = teamOpt,
        delivery = Some("immediate"),
        // 气泡四段式统一批（2026-09-15）· R-A 补：四段式 `PROJECT` 段链首级 =
        // **发送方所属项目**。本腿（分发器 → root）的收件会话是 root 会话（无项目
        // 上下文）⇒ 不置位时第二段恒落根域 `NEBULA`，与「发送方所属项目」判据相反。
        // 置本会话项目域即与 `mailAttribution` 同源（构造点 = 发送方侧，唯一取值处）。
        project = ctx.projectName,
        // ② 服务端注入（agent→agent 邮件），不是真人输入 —— 显式表态。
        fromUser = false
      )
      _ <- nebflow.core.UsageTracker.record("mail", senderSid)
    yield Right(s"Message sent to $label. The agent will process it.")

  end sendMail

  private def mailNotFound(address: String): IO[Either[ToolError, String]] =
    val msg =
      if address == NebulaAgentName then
        s"Cannot deliver to '$NebulaAgentName': no Nebula root session could be resolved (NEBULA_ROOT_UNRESOLVED). " +
          "Legacy gate: only a project dispatcher or a team lead may mail root. Use Mail(\"manager\", ...) to report to your Team Lead."
      else s"Cannot deliver to '$address'. Use a team name or agent short name."
    IO.pure(Left(ToolError(msg)))

  private def onMailDelivered(
    fromSid: String,
    toSid: String,
    toName: String,
    message: String,
    ctx: ToolContext
  ): IO[Unit] =
    val preview = message.take(200)
    for
      fromOpt <- TeamSessionRegistry.agentOfSession(fromSid)
      toInfo <- TeamSessionRegistry.instanceAndAgentOfSession(toSid)
      senderName = fromOpt.orElse(ctx.agentDef.map(_.name)).getOrElse("Nebula")
      (teamName, fromName) = toInfo match
        case Some((inst, _)) => (inst, senderName)
        case None => ("", fromOpt.getOrElse(fromSid.take(8)))
      parentSid <- TeamSessionRegistry.parentSessionOf(teamName)
      mailboxSid = parentSid.getOrElse(ctx.sessionId.getOrElse(""))
      _ <-
        if teamName.nonEmpty then
          FlowMailStore.append(mailboxSid, teamName, FlowMailStore.MailRecord(fromName, toName, message))
        else IO.unit
      _ <- ctx.wsSend match
        case Some(send) =>
          val event = Json.obj(
            "type" -> "flowMail".asJson,
            "from" -> fromName.asJson,
            "to" -> toName.asJson,
            "flowName" -> teamName.asJson,
            "preview" -> preview.asJson
          )
          send(event).handleErrorWith(_ => IO.unit)
        case None => IO.unit
    yield ()
    end for
  end onMailDelivered
end MailTool
