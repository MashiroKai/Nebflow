package nebflow.core.tools

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import scala.concurrent.duration.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowMailStore, MailQueueStore, TeamSessionRegistry}
import nebflow.core.project.{ProjectActor, ProjectRuntimeRegistry}
// mailattach 批（2026-09-17 作者四答 = 路线 A）：`attachments` 的件数/大小上限**只**引用
// `AttachContract`（单一数值权威面）——本文件零硬编码副本（plan 面 3 判据：grep 应只见单点）。
import nebflow.dropbox.AttachContract
// mailmodel batch (2026-09-25, ruling e-1): the device-mail CLIENT contract is retired —
// the Mail `device` leg (outbound) and the tunnel `agent_mail` intake (inbound) are both
// gone from this tool face; `nebflow.neblink.DeviceMail*` objects remain in-tree only as
// tombstones (zero production callers from this file).
import nebflow.shared.{ContentBlock, Message, MessageRole, ToolDefinition}


/**
 * Agent-to-agent communication tool.
 *
 * Delivery — **every Mail is immediate**: one mode, and no parameter to choose it
 * (async send, injected at the next turn boundary — an idle recipient starts a new
 * turn, a busy recipient gets it merged into the current turn).
 *
 * The former `queue` mode (serialized FIFO persisted to disk, one mail per turn —
 * for serial task chains: "do this, then that") was **retired** (delivery 退役批,
 * 2026-09-15 作者裁定 (b)):
 * - **every leg** (team short name / `project:` / `node:` / Nebula / `kernel`) that
 *   still sends `delivery="queue"` is an **explicit error**
 *   (`MAIL_DELIVERY_QUEUE_RETIRED`) — **never** a silent downgrade to immediate
 *   (a declared serial-chain intent cannot be honoured by immediate delivery, so
 *   silently ignoring it would be a silent semantic loss — same direction as the
 *   landed `node:` explicit refusals);
 * - the legacy queue layer below (`MailQueueStore` / `deliverQueue` / `queueTo*`)
 *   is **retained**: it has consumers on other faces (session queue drain, REST
 *   queue endpoints) and specs call it directly — it now has **zero production
 *   caller from this tool face**.
 *
 * mailparams 批 (2026-09-17 作者裁定 = 案 C「清死面」): the `delivery` **key has been
 * removed from `inputSchema`** — parameter face **8 → 7**. Zero capability is lost
 * (`immediate` was the only remaining `enum` value and it is also the default, so
 * the key only ever asked the model to pick a one-option option).
 * The removal is deliberately **not** a silent downgrade: `call()` keeps a
 * **tombstone read** of the key whose only job is to still refuse `"queue"` with the
 * same `MAIL_DELIVERY_QUEUE_RETIRED` error (see the tombstone comment in `call()`).
 * This works because the engine performs **no JSON-Schema validation** — a stale
 * caller's key still reaches the tool and is judged there, which is exactly why the
 * tombstone is the fail-closed side of this change.
 *
 * mailmodel batch (2026-09-25, rulings (b)/(d)/(e)):
 * - the `device` **key is removed from `inputSchema`** (parameter face **8 → 7**):
 *   the cross-device agent-mail leg is retired both ways (outbound leg deleted; the
 *   relay-tunnel `agent_mail` intake no longer injects). A stale caller that still
 *   passes `device=` gets the explicit `MAIL_DEVICE_RETIRED` error (tombstone read —
 *   same fail-closed shape as the `delivery` tombstone above), never a silent ignore.
 * - the `type` **key is removed from `inputSchema`** (parameter face **7 → 6**):
 *   the five mail-type tags were prompt-level semantics with only two engine branches,
 *   both re-homed (the device-leg INFO-only branch died with the leg; the
 *   dispatcher-to-root P0 window exemption is now judged on the **body's first-line
 *   `[INTERRUPT]` literal**, keeping the A3/R7 bypass pair intact — mechanism, not a
 *   message type).
 * - the **`kernel` address leg** is added (Nebula-exclusive, ruling (b)):
 *   `address="kernel"` starts a Kernel instance (the Delegate inner-core sub-agent)
 *   and the receipt carries the continuation address `kernel:<id>`; a later Mail to
 *   `kernel:<id>` continues that live instance. Non-Nebula callers are refused
 *   (`MAIL_KERNEL_EXCLUSIVE`) — the kernel trigger face belongs to the Nebula root
 *   only. The result of a kernel instance is delivered back to the Nebula session by
 *   the existing `source="delegate"` uplink (zero tool-face change on the return leg).
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
  // mailmodel batch (2026-09-25, rulings (d)/(e)) — target face and retirement word
  // table (**single source**; the report, specs and descriptions cite this same source;
  // a second literal copy is forbidden).
  //   MAIL_TARGET_MISSING    both missing: `address` not filled (the device leg is
  //                          retired; the target face is address-only)
  //   MAIL_DEVICE_RETIRED    a stale caller still sends `device=` => explicit refusal
  //                          (fail-closed tombstone, same shape as the `delivery`
  //                          tombstone — the schema key is deleted and the engine
  //                          does zero JSON-Schema validation => the stale key still
  //                          reaches call(), where one read line refuses it)
  //   MAIL_KERNEL_EXCLUSIVE  the kernel leg is Nebula-exclusive (ruling (b)): every
  //                          non-Nebula caller is refused
  //   MAIL_KERNEL_NOT_LIVE   the `kernel:<id>` instance is not live (terminal state /
  //                          unknown id) => explicit refusal
  // ============================================================
  val ErrTargetMissing: String = "MAIL_TARGET_MISSING"
  val ErrDeviceLegRetired: String = "MAIL_DEVICE_RETIRED"
  val ErrKernelExclusive: String = "MAIL_KERNEL_EXCLUSIVE"
  val ErrKernelNotLive: String = "MAIL_KERNEL_NOT_LIVE"

  /** Both-missing error (verbatim word table; after the device leg retired, the target face is `address`-only).
    *
    * friendseal batch (2026-09-25): the tail pointer is SEAL-AWARE. With the
    * friends feature sealed (the default posture), SendMessage's friend/group/
    * local targets — including the this-machine local copy — answer
    * `FRIENDS_SEALED`, so the pointer names the surviving `device:` target
    * explicitly; unsealed, the original sentence is kept verbatim (zero
    * behavior change while the flag is on). `def` (not `val`): the latch is a
    * boot-time constant, but this word table must follow it without a second
    * source. */
  private[tools] def targetMissingMessage: String =
    s"[$ErrTargetMissing] Missing target: fill 'address' (an agent/team/project target — e.g. " +
      "\"project:<name>\", a team name, or a member short name). The former 'device' parameter is " +
      "retired (MAIL_DEVICE_RETIRED); " +
      (if nebflow.core.FriendsSeal.isSealed then
         "files for another machine go via SendMessage's `device:` target — its friend/group/local " +
           "targets (including the local this-machine copy) are sealed (FRIENDS_SEALED) until the " +
           "feature is unsealed."
       else
         "files for another machine's user go via SendMessage.")

  /** Retirement refusal for a stale caller's `device=` (pure constructor, for direct spec testing). */
  private[tools] def deviceLegRetiredError(device: String): ToolError =
    ToolError(
      s"[$ErrDeviceLegRetired] The 'device' parameter was retired on 2026-09-25 — the cross-device " +
        "agent-mail leg no longer exists (the receiving end stopped injecting agent_mail envelopes in the " +
        "same batch), so nothing was sent and nothing is queued. Got device='" + device + "'. To move files or " +
        "text to another MACHINE use SendMessage (pure transport; that machine's agent is not informed). To " +
        "reach an AGENT use 'address': \"project:<name>\" (or a bare mounted project name), a team name or " +
        "member short name, or — Nebula root only — \"kernel\" / \"kernel:<id>\"."
    )

  /** Kernel-leg unauthorized-caller refusal (ruling (b): Nebula-exclusive; the refusal text ships as a §16 candidate deliverable). */
  private[tools] def kernelExclusiveError(address: String, role: String): ToolError =
    ToolError(
      s"[$ErrKernelExclusive] The kernel address leg is EXCLUSIVE to the Nebula root session (ruling " +
        "2026-09-25 (b)): dispatchers, team agents and nodes cannot start or continue a kernel instance. " +
        s"Nothing was sent (address='$address', your role: $role). Route the work through Nebula instead — " +
        "mail your report to Nebula and let it dispatch."
    )

  /** Explicit refusal when the `kernel:<id>` instance is not live (fail-closed: a finished kernel instance's address is unroutable). */
  private[tools] def kernelNotLiveError(id: String): ToolError =
    ToolError(
      s"[$ErrKernelNotLive] No live kernel instance 'kernel:$id' — kernel instances are one-shot Delegate " +
        "sessions: when an instance finishes, its result is delivered back to your session and its address " +
        "stops being routable. Nothing was sent. Start a new instance with address=\"kernel\"."
    )

  // ============================================================
  // delivery 退役批（2026-09-15 作者裁定 (b)「保留字段、退役 queue 模式语义」）
  //   ＋ mailparams 批（2026-09-17 作者裁定 = 案 C「清死面」）——
  // The unified explicit-refusal text for `delivery="queue"` on **every leg** (**single source**; specs cite this same source).
  //   · 语义 = `delivery` 字段已**退役出 schema**（mailparams 批：参数面 8 → 7，该键
  //     不再对模型可见），但 `call()` 内**保留一行墓碑读取**（`deliveryTombstone`）
  //     —— every leg receiving `"queue"` is still **immediately and explicitly refused**: zero delivery side effects, zero queue persistence.
  //   · 选「显式拒绝」而非「立即化」的理由：调用方声明的**串行链语义**无法被立即投递
  //     honoured by immediate delivery —— a silent switch = a silent semantic loss (forbidden face), same direction as the existing `node:` leg's
  //     「显式拒绝，禁静默降级」先例同向；调用方拿到可读错误即可自纠。
  //   · 墓碑读取成立的判据（不是权宜）：引擎**零 JSON-Schema 校验**（`protocol.scala`
  //     自陈「面外参数会被静默忽略」）⇒ 删 schema 键**不会**让旧键到不了 `call()`；
  //     故「删键 + 墓碑判」= fail-closed，而「删键 + 删判」才是静默降级。
  //   · (mailmodel 2026-09-25: the device leg's own v2.1 queue-refusal literal died with
  //     the leg itself — the single gate below now structurally covers every leg.)
  // ============================================================
  val ErrDeliveryQueueRetired: String = "MAIL_DELIVERY_QUEUE_RETIRED"

  // ============================================================
  // mailattach 批（2026-09-17 作者四答 = 路线 A）——「静默丢」修 B6 的**唯一来源**词表。
  //   MAIL_VISION_UNSUPPORTED_LEG  `project:` / `node:` / `kernel` (the spawn leg)
  //                                receiving `images` => explicit refusal.
  // Rationale: these legs are **structurally string-only** (`ProjectActor.TriggerDispatcher(message)` /
  // `NodeEngine.sendNodeMessage(nodeId, message, _)` and the kernel spawn brief all lack a blocks
  // parameter) => the old behavior was "load + base64, then never use it" = silent drop (plan risk 1).
  // Now an explicit refusal, with the message giving **category + reason + alternative path** and
  // naming the legs that can carry images.
  // (mailmodel batch 2026-09-25: the device leg retired whole with (e) => its B7 4000-char body
  // gate (`MaxDeviceMailTextChars` / `MAIL_DEVICE_TEXT_TOO_LONG`) is deleted with it.)
  // ============================================================
  val ErrVisionUnsupportedLeg: String = "MAIL_VISION_UNSUPPORTED_LEG"

  /** B6 显式拒绝文案（纯函数，供 spec 直测；词表与文案同源）。 */
  private[tools] def sameMachineVisionUnsupportedError(target: String, count: Int): ToolError =
    ToolError(
      s"[$ErrVisionUnsupportedLeg] $count image(s) cannot be delivered to '$target': this leg is TEXT ONLY — " +
        "it hands a plain string to an engine-side session (ProjectActor.TriggerDispatcher / " +
        "NodeEngine.sendNodeMessage), so it has no attachment channel and nothing was sent. " +
        "Use `attachments` instead (same-machine targets get each file's absolute path, byte size and " +
        "sha256 in the message text, and the recipient reads the original), or send the images over a leg " +
        "that can carry them: address=\"Nebula\" or a team/agent short name."
    )

  private[tools] def deliveryQueueRetiredMessage(address: String): String =
    s"""[$ErrDeliveryQueueRetired] delivery="queue" is retired (2026-09-15) — the serialized FIFO mode no longer exists: every Mail is delivered immediately (injected at the target's next turn boundary; an idle target starts a new turn, a busy target has it merged into the current turn). Drop delivery=queue (or omit the `delivery` parameter — only "immediate" is accepted). Target: '$address'."""

  val name: String = "Mail"

  val description: String =
    """Send a message to another agent — the platform's **only message primitive**
(2026-09-12 「一个 Mail 统一」；the former `Task` and `NodeMessage` tools are retired —
this note supersedes all earlier instructions naming them as entry points).

Required: message, plus `address`.

## `Mail` vs `SendMessage` — who receives it?
- **`Mail` — an AGENT receives it**: the mail is injected into the target agent's
  session at its next turn boundary; that agent reads it and handles it.
- **`SendMessage` — pure transport; the peer's agent is NOT aware of it**: files land
  on the peer MACHINE and the text appears for the machine and its user; nothing
  enters the peer's agent session or its LLM context. Moving files or text to another
  machine or its user = `SendMessage`; making another agent aware = `Mail`.
  (The former cross-device `device` parameter of THIS tool was retired on 2026-09-25 —
  a stale `device=` call is an explicit MAIL_DEVICE_RETIRED error, never a silent send.)

## Address face (role-scoped — an address outside your face is an explicit error)
- **Nebula (root)**: `project:<name>` — triggers that project's dispatcher (a bare
  mounted project name is accepted as an equivalent form). `kernel` — start a Kernel
  instance (the Delegate inner-core sub-agent); `kernel:<id>` — continue THAT live
  instance. You have no `node:`
  address and no self-address.

## Kernel leg (Nebula-exclusive, 2026-09-25)
Only the Nebula root session may mail a kernel — a dispatcher, team agent or node that
mails `kernel` / `kernel:<id>` gets MAIL_KERNEL_EXCLUSIVE and nothing is sent.
`address="kernel"` starts a Kernel instance: seven fixed tools (Read/Write/Edit/Glob/
Grep/Bash + AskUserQuestion), no project context, no memory — the message must be a
fully self-contained brief. The instance runs in the background; its result is delivered
back to your session when it finishes, and the tool result header carries its
continuation address (`kernel:<id>`). `address="kernel:<id>"` continues THAT live
instance (injected at its next turn boundary). A `kernel:<id>` whose instance has
already finished is an explicit error (MAIL_KERNEL_NOT_LIVE) — its result was already
delivered; start a new one with `kernel`.

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
- `node:` routing takes no delivery-mode choice — the engine decides inject-at-turn-boundary
  vs append-to-task.

Images (optional `images` parameter — up to 5 absolute local image paths,
PNG/JPG/JPEG/GIF/WEBP/BMP), injected as **vision blocks**. **Agent targets (`address`)
that reach a chat turn** (Nebula / a team or agent short name / a live `kernel:<id>`
instance): the recipient sees the images directly (vision models) plus their paths as
text. **`project:` / `node:` targets (and the kernel START leg) are text-only** — those
legs hand a plain string to an engine-side session, so `images` there is an **explicit
error** and nothing is sent; use `attachments` (path mode) instead, or target `Nebula` /
a team agent.

Attachments (optional `attachments` parameter — up to 9 absolute local paths of ANY file
type, each up to 1024 MB = 1 GiB) are how a non-image file — or any file you do not need
the model to *see* — reaches the recipient. **Same-machine targets** (`address` =
`project:…` / `node:…` / `kernel…` / `Nebula` / a team or agent short name): nothing is
copied — the mail text carries each
file's **absolute path, byte size and sha256**, because the recipient shares your disk
and reads the original. So the file must still exist when the recipient reads it: do not
point `attachments` at temporary or worktree paths that may be cleaned up before then.
`attachments` does NOT put bytes into the recipient's LLM context: for an image the
model should see, use `images`.

Delivery — every Mail is immediate (there is no delivery parameter to set):
  Async send, injected at the target's next turn boundary (an idle target starts
  a new turn; a busy target has it merged into the current turn). You don't wait
  for a response.
  There is no delivery mode to choose: the former `queue` mode (serialized FIFO,
  one Mail per turn — for "do this, then that" serial chains) was RETIRED on
  2026-09-15, and the `delivery` parameter itself was removed from this tool's
  schema on 2026-09-17. A stale caller that still passes `delivery="queue"` is an
  explicit error (MAIL_DELIVERY_QUEUE_RETIRED) on every target — it is
  NEVER silently downgraded to immediate.
  Dispatcher-to-root replies are coalesced into ONE injection per 5-second window;
  a reply that must bypass the window leads its body with the literal token
  [INTERRUPT] on the first line."""

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
    "- **Nebula (root)**: `project:<name>` — triggers that project's dispatcher (a bare\n  mounted project name is accepted as an equivalent form). `kernel` — start a Kernel\n  instance (the Delegate inner-core sub-agent); `kernel:<id>` — continue THAT live\n  instance. You have no `node:`\n  address and no self-address.\n\n## Kernel leg (Nebula-exclusive, 2026-09-25)\nOnly the Nebula root session may mail a kernel — a dispatcher, team agent or node that\nmails `kernel` / `kernel:<id>` gets MAIL_KERNEL_EXCLUSIVE and nothing is sent.\n`address=\"kernel\"` starts a Kernel instance: seven fixed tools (Read/Write/Edit/Glob/\nGrep/Bash + AskUserQuestion), no project context, no memory — the message must be a\nfully self-contained brief. The instance runs in the background; its result is delivered\nback to your session when it finishes, and the tool result header carries its\ncontinuation address (`kernel:<id>`). `address=\"kernel:<id>\"` continues THAT live\ninstance (injected at its next turn boundary). A `kernel:<id>` whose instance has\nalready finished is an explicit error (MAIL_KERNEL_NOT_LIVE) — its result was already\ndelivered; start a new one with `kernel`.\n\n"
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
          "description" -> "Role-scoped address. Nebula (root): \"project:<name>\" (a bare mounted project name is equivalent), \"kernel\" (start a Kernel instance — the Delegate inner-core sub-agent), or \"kernel:<id>\" (continue THAT live instance; the start receipt carries its id) — the kernel leg is Nebula-exclusive. Project dispatcher: \"Nebula\" or \"node:<nodeId>\". Team context: a team name, a member short name, or \"team/agent\". An address outside your face is an explicit error.".asJson
        ),
        "message" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or question to send".asJson
        ),
        "chainId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional chain id (e.g. \"chain-n-933b5a8c\") of the batch this Mail belongs to. Validated against the project's chain registry: the derived chain set (declared chains ∪ fallback-derived components) ∪ chain-level dependency targets ∪ every chain id registered in the chain ledger (after a chain re-id the superseded old id stays reachable via its alias). An id that is registered nowhere is an explicit error (MAIL_CHAIN_NOT_FOUND). Not persisted anywhere; when provided it is embedded verbatim in the injected text so the recipient can quote it back. REQUIRED when reporting a batch close-out to Nebula.".asJson
        ),
        "task" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> ("Optional task id — continue THAT task's dispatcher instead of starting a new one. " +
            "OMIT it to have the engine CREATE a task for this Mail and return its number in the result header " +
            "(`[task #N] …`): pass that number on subsequent Mails about the same work so they continue the same " +
            "task instead of spawning a parallel one. A `task` that does not exist, or is already terminal " +
            "(`closed` / `completed`), is an explicit error and nothing is sent. " +
            "Only meaningful on the `project:<name>` leg. The Mail body is automatically appended " +
            "to that task's note timeline by the engine — there is no note parameter anywhere.").asJson
        ),
        "images" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> 5.asJson,
          "description" -> ("Optional absolute local image paths (PNG/JPG/JPEG/GIF/WEBP/BMP, max 5), injected as " +
            "vision blocks. For `address` targets that reach a chat turn (Nebula / team / agent short name / a " +
            "live `kernel:<id>` instance) the recipient sees the images directly plus their paths as text. NOT " +
            "supported on `project:` / `node:` (or the kernel START leg) — those legs are text-only, so passing " +
            "`images` there is an explicit error and nothing is sent; pass those paths as `attachments` " +
            "instead.").asJson,
          "default" -> Json.arr()
        ),
        "attachments" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> AttachContract.MaxAttachmentsPerMessage.asJson,
          "description" -> ("Optional ABSOLUTE local paths of files to attach — any file type, up to " +
            s"${AttachContract.MaxAttachmentsPerMessage} files, each up to ${AttachContract.MaxFileBytesLabel}. " +
            "PATH MODE — on same-machine targets (`address` = `project:…` / `node:…` / `kernel…` / `Nebula` / a " +
            "team or agent short name) nothing is copied: the mail text carries each file's absolute path, byte " +
            "size and sha256, because the recipient shares your disk and reads the original — so the file must " +
            "still exist when the recipient reads it. This is NOT the vision channel: an image the recipient's " +
            "model should SEE belongs in `images`.").asJson,
          "default" -> Json.arr()
        )
      ),
      "required" -> Json.arr("message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val addr = input("address").flatMap(_.asString).getOrElse("?")
    // mailmodel batch (2026-09-25): the device/type keys are retired => the label face
    // is address-only (the old `device:` target prefix and the ` [TYPE]` tag are gone with them).
    // delivery 退役批（2026-09-15）：标签面不再有 `, queue` 形态 —— queue 模式退役后
    // 该值只可能是**已拒绝**的旧调用方，标签不得再宣称 queue 投递（描述面清理的连带面）。
    s"Mail(→$addr)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("").trim
    val message = input("message").flatMap(_.asString).getOrElse("")

    // ------------------------------------------------------------
    // 🔴 Tombstone reads (mailparams batch, 2026-09-17 case C "clear the dead face";
    // mailmodel batch 2026-09-25 adds one device tombstone in the same shape) — both
    // keys are **retired out of the schema**; these reads exist only as **retirement
    // tombstones**, not live parameters:
    //   · `delivery` tombstone: sole purpose = detect a stale caller's `"queue"` and
    //     raise `MAIL_DELIVERY_QUEUE_RETIRED` (single-point gate below). Every other
    //     value needs no branch anymore (immediate is the only delivery form left).
    //   · `device` tombstone (ruling (e)): sole purpose = detect a stale caller still
    //     sending `device=` and raise `MAIL_DEVICE_RETIRED` (the cross-device
    //     agent-mail leg retired on both ends in the same batch; the message gives a
    //     way out: SendMessage for machine-facing transport, `address` for agents).
    // Why read keys that are gone from the schema: the engine does **zero JSON-Schema
    // validation** (`protocol.scala` itself says out-of-face parameters are silently
    // ignored) => stale keys still arrive here => one read line preserves the
    // "explicit refusal, never silent downgrade" stance exactly (deleting the read
    // line = silent ignore = the opposite ruling).
    // ------------------------------------------------------------
    val deliveryTombstone = input("delivery").flatMap(_.asString).getOrElse("immediate")
    val deviceTombstone = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val chainIdRaw = input("chainId").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).filter(_ != "null")
    // taskunify batch (2026-09-24, ruling c①): the **explicit task-id parameter** (no
    // address-syntax extension -- the `address`/`to` face is being refactored by an
    // in-flight chain, so this batch only adds a schema key). It is meaningful only on the
    // `project:` leg (other legs are closed by the address face). An empty string / null is
    // synonymous with omitting it (not specified).
    val taskRaw = input("task").flatMap(_.asString).map(_.trim.stripPrefix("#")).filter(_.nonEmpty).filter(_ != "null")

    // mailmodel batch (2026-09-25, ruling e): the `device` tombstone gate — **ahead of
    // every other judgment and delivery side effect** (same order as the existing
    // fail-fast discipline; the key is no longer in the schema, only stale callers send it).
    if deviceTombstone.isDefined then
      IO.pure(Left(deviceLegRetiredError(deviceTombstone.get)))
    else if address.isEmpty then IO.pure(Left(ToolError(targetMissingMessage)))
    else if message.isEmpty then IO.pure(Left(ToolError("Missing required parameter: message")))
    // delivery retirement batch (2026-09-15 author ruling (b)): `delivery="queue"` =>
    // **显式拒绝**（零副作用，先于 chainId 校验与一切路由/投递）。
    // mailparams 批（2026-09-17 案 C）后本闸的输入来自**墓碑读取**（`deliveryTombstone`，
    // 该键已不在 schema）——判据与文案**逐字不变**：这是「删 schema 键、不删拒绝」的落点。
    // single point — structurally guarantees "zero queue entry on this tool face".
    else if deliveryTombstone == "queue" then IO.pure(Left(ToolError(deliveryQueueRetiredMessage(address))))
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
                  // mailattach 批（2026-09-17）：同机腿附件 = **附注 + 零搬字节**。
                  // 校验（形态/存在/件数/大小）与附注构造都是单点（[[attachmentsPlan]] /
                  // [[withAttachmentsNote]]），且前置于一切路由副作用；附注必须在
                  // `messageBlocks` **之前**并入正文 —— blocks 在场时 `AgentActor` 丢弃
                  // `text`（`:1712-1713` 既有口径），晚并入即静默丢。
                  attachmentsPlan(input, ctx).flatMap {
                    case Left(err) => IO.pure(Left(err))
                    case Right(attachmentPaths) =>
                      val effectiveMessage = withAttachmentsNote(message, attachmentPaths)
                      val blocks = ImageInject.messageBlocks(effectiveMessage, attachments)
                      ctx.actorSystem match
                        case None =>
                          IO.pure(Left(ToolError("No actor system available")))
                        case Some(system) =>
                          // R2 分层地址面（作者 2026-09-12 10:38 细则 + B1-a）：角色专属
                          // 地址形态先在这一层定判——命中即处理（含**显式报错**），未命中
                          // （= 该地址不属于本角色的分层面）才落回既有 team/短名瀑布。
                          // 硬禁静默兜底与模糊匹配：认不出的地址一律显式报错并指明合法面。
                          // `imagePaths` 一并下传：`project:` / `node:` 两条腿**结构上**
                          // 只能收字符串 ⇒ `images` 在它们身上是显式拒绝（B6 静默丢修）。
                              // mailmodel batch (2026-09-25): the mailType parameter is retired with
                              // the `type` key —— eventType is now judged by the **leading `[INTERRUPT]`
                              // literal of the body** (same single-point criterion as deliverToNebulaRoot):
                              // a literal first line => `interrupt`, otherwise `info`. The dispatcher->root
                              // leg's batching-window exemption uses the same judgment (see
                              // deliverToNebulaRoot / isDispatcherMailInterrupt).
                          layeredRoute(address, effectiveMessage, blocks, imagePaths, chainId, ctx, system, taskRaw) match
                            case Some(action) => action
                            case None =>
                              // mailparams 批（2026-09-17 案 C）：本层原有一个 `delivery match`
                              // —— 「`"immediate"`」与「陌生值 ⇒ 打 WARN 后收敛到 immediate」两支。
                              // schema 键删除后**只剩一条投递路径**（immediate 是唯一形态），
                              // 故该分支连同其 WARN 一并删除（设计件 §4.4(a)「其余值不再需要分支」），
                              // 不再有任何按键值分派的逻辑。非 `queue` 的旧值由此与「键缺席」
                              // **逐字同一结果**（残差读数见交付报告）。
                              val legacyEventType = if isDispatcherMailInterrupt(effectiveMessage) then "interrupt" else "info"
                              if address.contains("://") then deliverToAddress(address, effectiveMessage, blocks, legacyEventType, ctx, system)
                              else deliverToShortName(address, effectiveMessage, blocks, legacyEventType, ctx, system)
                  }
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
  /** kernel leg (mailmodel batch 2026-09-25, ruling (b)): the `kernel:<id>` continuation
    * form's prefix (the bare `kernel` form = start a new instance, judged in
    * [[layeredRoute]]). id = the full session id `delegate-kernel-<8hex>` (the registry
    * key, unambiguous). */
  private val KernelPrefix = "kernel:"

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
      imagePaths: List[String],
      chainId: Option[String],
      ctx: ToolContext,
      system: ActorSystem,
      /** **Explicit task-id parameter** (taskunify batch 2026-09-24, ruling c①):
        * meaningful only on the `project:` leg (including the bare-project-name equivalent
        * shape) -- omitted ⇒ the engine creates a task automatically; supplied ⇒ continue
        * that task. Other legs are closed by the address face (this parameter is ignored on
        * non-project legs, because those legs never touch the task ledger). */
      task: Option[String] = None
  ): Option[IO[Either[ToolError, String]]] =
    val role = roleOf(ctx)
    // ── kernel leg (mailmodel batch 2026-09-25, ruling (b)): the Nebula-exclusive
    // address leg ── `kernel` = start one kernel instance; `kernel:<id>` = continue that
    // live instance. Every non-Nebula caller is explicitly refused ([[kernelExclusiveError]];
    // the refusal text ships as a §16 candidate deliverable).
    if address == "kernel" || address.startsWith(KernelPrefix) then
      Some(
        if role != SenderRole.NebulaRoot then
          IO.pure(Left(kernelExclusiveError(address, role.toString)))
        else deliverToKernel(address, message, blocks, ctx, system)
      )
    else if address.startsWith(NodePrefix) then
      val nodeId = address.stripPrefix(NodePrefix).trim
      Some(
        if nodeId.isEmpty then IO.pure(Left(ToolError(s"Malformed address '$address' — expected \"node:<节点id>\".")))
        else if role != SenderRole.Dispatcher then IO.pure(Left(outOfFaceError(address, role)))
        else countMailUsage(deliverToNode(nodeId, withChainAnnotation(message, chainId), imagePaths, ctx), chainId, ctx)
      )
    else if address.startsWith(ProjectPrefix) then
      val pname = address.stripPrefix(ProjectPrefix).trim
      Some(
        if pname.isEmpty then IO.pure(Left(ToolError(s"Malformed address '$address' — expected \"project:<项目名>\".")))
        else if role == SenderRole.Dispatcher then IO.pure(Left(outOfFaceError(address, role)))
        else deliverToProject(pname, message, imagePaths, ctx, task)
      )
    else if address == MailTool.NebulaAgentName then
      role match
        case SenderRole.NebulaRoot =>
          Some(IO.pure(Left(ToolError(
            s"Address \"Nebula\" is your own (self) address — it is not in your address face ($nebulaFace)."
          ))))
        case SenderRole.Dispatcher =>
          Some(countMailUsage(
            deliverToNebulaRoot(address, withChainAnnotation(message, chainId), blocks, ctx, system),
            chainId, ctx))
        case SenderRole.Teamish => None // 既有 canMailNebula 闸不变
    else if role == SenderRole.NebulaRoot then
      // Nebula 的裸名形态 = 裸项目名（等价接受）；认不出的地址显式报错。
      Some(
        ProjectRuntimeRegistry.get(address).flatMap {
          case Some(_) => deliverToProject(address, message, imagePaths, ctx, task)
          case None    => IO.pure(Left(unresolvableError(address, role)))
        }
      )
    else if role == SenderRole.Dispatcher then
      // 分发器不给自己项目发（细则）——裸名一律越界报错。
      Some(IO.pure(Left(outOfFaceError(address, role))))
    else None
  end layeredRoute

  /** chainId 逐字进注入文本（R-18；腿②③一致）。只影响注入体——**不落 Mail 自身任何库**
    * （R-17/B2-x「只校验、不落库」逐字保留）；引用**计数**另经批三+ 的 `mail-usage` 面钩子
    * 记进链号台账（[[countMailUsage]] → `ChainLedgerStore.noteReference` 的 `externalRefs`）
    * ——「mail 参数入库」与「引用面计数」是两件事，本行前句不因后者改写。 */
  private def withChainAnnotation(message: String, chainId: Option[String]): String =
    chainId match
      case Some(id) => s"[mail chainId: $id]\n$message"
      case None     => message

  /** chainmodel 批三+：引用面 id 字面量（与 `ChainLedger.ReferenceFaces` 登记逐字同值）。 */
  private val FaceMailUsage = "mail-usage"

  /** **引用面 `mail-usage` 计数钩子（轴 b）** —— 登记表 `incWhen` 的逐字落点：「Mail 携带
    * chainId **且投递成功**」。
    *
    * 只包**正文已注入链号注解**的两条腿（`node:` 腿 / Nebula 腿，注解单点 =
    * [[withChainAnnotation]]）：注解没进正文的腿（`project:` / 裸项目名 / 短名 / 设备腿 ——
    * 设备腿的 chainId 明确「只校验不带出」）**结构上不产生**该引用 ⇒ 不计数。
    * `Left`（未投递 / 越界 / 终态拒绝 / 校验失败）⇒ **零计数**（引用没发生）。
    * best-effort：落账失败只 WARN（见 `NodeEngine.noteChainReference`），不回滚已投递的 Mail。 */
  private def countMailUsage(
      action: IO[Either[ToolError, String]],
      chainId: Option[String],
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    action.flatMap {
      case r @ Right(_) if chainId.nonEmpty =>
        noteChainReferences(ctx, chainId.toList, FaceMailUsage).as(r)
      case r => IO.pure(r)
    }

  /** 引用面计数落点（best-effort；`projectName` 空 ⇒ 无可计之处）。
    *
    * 「项目未挂载」对本钩子**不可达**：`node:` 腿在项目未挂载时本就投递失败（⇒ `Left` ⇒
    * 不计数），Nebula 腿的发信会话恒属已挂载项目。故该分支为断言式 `IO.unit`（不猜、不静默
    * 吞错——真出偏差时 `NodeEngine` 侧 WARN 仍可观察）。 */
  private def noteChainReferences(ctx: ToolContext, ids: List[String], faceId: String): IO[Unit] =
    ctx.projectName.map(_.trim).filter(_.nonEmpty) match
      case None => IO.unit
      case Some(name) =>
        ProjectRuntimeRegistry.get(name).flatMap {
          case Some(rt) =>
            ids.distinct.foldLeft(IO.unit)((acc, id) => acc *> rt.engine.noteChainReference(id, faceId))
          case None => IO.unit
        }

  /** B2-x / R-17 + **chainmodel 批三 ①（chainmail）**：`chainId` **只校验、不落库**
    * （本方法零副作用；台账只**读**、**禁回填**）。无项目上下文时只做形态校验（无链集可对）；
    * 有项目上下文则按**台账解析**给出可达集合：
    *   声明链 ∪ 兜底派生链 ∪ 链级依赖目标链 ∪ 台账已登记旧号别名
    * （判据单点 = `NodeEngine.mailChainIds` / `resolveMailChainId` → `FlowMapStore`；
    * 热面未命中再经台账冷档兜底 ⇒ 「链号改号后旧号永久可达」，见设计件 §二(b)/(c) 第 4 面）。
    *
    * 🔴 **负判据保留**（判红线）：完全未登记号（含归档区封存链号）照旧
    * `MAIL_CHAIN_NOT_FOUND`（`MAIL_CHAIN_NOT_FOUND` = 非空但不可达）——禁把校验放宽成
    * 「未知也放行」。改造前口径 = 纯派生链集比对（零台账），旧号在链号重归后必然失效。 */
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
                rt.engine.resolveMailChainId(id).flatMap {
                  case Some(_) => IO.pure(Right(Some(id): Option[String]))
                  case None =>
                    // 错误路径才取「已知链」清单（避免每次成功校验都多一次全量派生）
                    rt.engine.mailChainIds.map { ids =>
                      Left(ToolError(
                        s"Unknown chainId '$id' in project '$projectName' (MAIL_CHAIN_NOT_FOUND). " +
                          s"Known chains: ${if ids.isEmpty then "(none registered)" else ids.toList.sorted.mkString(", ")}. " +
                          "chainId is validated only — it is never persisted; omit it if the Mail does not belong to a batch."
                      ))
                    }
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
      eventType: String,
      ctx: ToolContext,
      intake: Option[String] = None
  ): IO[InjectionAttribution] =
    val senderName = ctx.agentDef.map(_.name).getOrElse(MailTool.NebulaAgentName)
    TeamSessionRegistry.teamOfSession(ctx.sessionId.getOrElse("")).map { team =>
      InjectionAttribution(
        sender = Some(senderName),
        senderTeam = team,
        eventType = Some(eventType.toLowerCase),
        intake = intake,
        project = ctx.projectName
      )
    }

  /** 腿②（分发器 → 节点）：**复用引擎侧单点** `NodeEngine.sendNodeMessage`——三态判据
    * 与三个错误码**不复制**（复制必然漂移）。注入 source 保持 `"system"`（D-3：
    * 节点侧既有呈现零 UI 行为变化）；来源标注经 attribution 参数传（sender/
    * senderTeam/eventType），节点会话蓝气泡顶栏因此可辨「来自谁」。 */
  private def deliverToNode(
      nodeId: String,
      message: String,
      imagePaths: List[String],
      ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // B6（静默丢修，mailattach 2026-09-17）：本腿结构上只能收字符串 ⇒ `images` 无承载面。
    // 旧行为 = 静默丢（零报错、零投递）；本批 = 显式拒绝（判据与文案见
    // [[sameMachineVisionUnsupportedError]]）。
    if imagePaths.nonEmpty then IO.pure(Left(sameMachineVisionUnsupportedError(s"node:$nodeId", imagePaths.size)))
    else
      ctx.projectName match
        case None | Some("") =>
          IO.pure(Left(ToolError(
            s"Cannot route to node '$nodeId' — this session has no project context (the 'node:' leg resolves the project from the calling session)."
          )))
        case Some(projectName) =>
          ProjectRuntimeRegistry.get(projectName).flatMap {
            case Some(rt) =>
              mailAttribution("info", ctx).flatMap { attribution =>
                rt.engine.sendNodeMessage(nodeId, message, Some(attribution)).map(_.left.map(ToolError(_)))
              }
            case None =>
              IO.pure(Left(ToolError(
                s"Project '$projectName' is not mounted — cannot route to node '$nodeId'. Re-mount / restart (projects mount at startup)."
              )))
          }

  // ============================================================
  // kernel leg (mailmodel batch 2026-09-25, author ruling (b) "only Nebula may use it; Nebula dispatches it"):
  // Mail-triggered Kernel = the **Nebula-exclusive address leg**; the dispatch receipt carries the
  // continuation address (the follow-up-by-address family).
  //   - `kernel`   = spawn one kernel instance (the SAME spawn chain as Delegate.call: same
  //                  kernel def resolution, same R9 <=4-per-root-session concurrency gate, same
  //                  BackoffSupervisor adapter and 3600s budget — zero second implementation);
  //   - `kernel:<id>` = inject into that **live** instance (registry kind=Delegate with session-id match);
  //     after the instance goes terminal (one-shot Delegate semantics: the result has already been
  //     returned to root along source="delegate") the address is **unroutable** => explicit refusal
  //     ([[kernelNotLiveError]]), same family as the `node:` leg's three-state judgment
  //     ("running injectable / terminal refused").
  // Unauthorized callers are refused in [[layeredRoute]] (SenderRole != NebulaRoot => MAIL_KERNEL_EXCLUSIVE);
  // this layer only delivers. The result-return chain has zero tool-face change (the existing
  // BackoffSupervisor parent uplink).
  // ============================================================

  /** Kernel-leg delivery (the caller's NebulaRoot identity has already been verified in [[layeredRoute]]). */
  private def deliverToKernel(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      ctx: ToolContext,
      system: ActorSystem
  ): IO[Either[ToolError, String]] =
    (ctx.sharedResources, ctx.actorSystem) match
      case (Some(res), Some(sys)) =>
        if address == "kernel" then
          // New instance: the kernel spawn brief is plain-text only
          // ([[DelegateTool.spawnBackground]]'s initialPrompt has no blocks parameter)
          // => `images` is explicitly refused on this leg (same family as B6).
          if blocks.exists(_.exists {
                case _: nebflow.shared.ContentBlock.Image => true
                case _                                    => false
              }) then
            IO.pure(Left(sameMachineVisionUnsupportedError("kernel", blocks.get.collect {
              case _: nebflow.shared.ContentBlock.Image => ()
            }.size)))
          else
            DelegateTool.spawnKernelForMail(message, "mail-triggered kernel task", ctx).map {
              case Left(err) => Left(err)
              case Right(id) =>
                Right(
                  s"[kernel #$id] Kernel instance started (Delegate inner-core: seven fixed tools, " +
                    "no project context, no memory). Continuation address: \"kernel:" + id +
                    "\" — later Mails about THIS instance must carry address=\"kernel:" + id +
                    "\". Its result will be delivered back to your session when the instance finishes."
                )
            }
        else
          // Continuation leg: `kernel:<id>` -> the registry single point looks up the **live**
          // instance (kind=Delegate with id match with kernel session prefix); a hit => UserInput
          // injection (consumed at the turn boundary, replyTo = the supervisor adapter —— the
          // turn's terminal state still follows the source="delegate" return chain); a miss => fail-closed.
          val id = address.stripPrefix(KernelPrefix).trim
          if id.isEmpty then
            IO.pure(Left(ToolError(s"Malformed address '$address' — expected \"kernel:<instanceId>\" (the id the start receipt carried).")))
          else
            res.agentRegistry.get.flatMap { reg =>
              reg.get(id) match
                case Some(rec) if rec.kind == AgentKind.Delegate && id.startsWith("delegate-kernel-") =>
                  (rec.ref ! AgentCommand.UserInput(
                    text = message,
                    replyTo = rec.supervisorRef,
                    source = Some("mail"),
                    sender = ctx.agentDef.map(_.name),
                    eventType = Some("info"),
                    blocks = blocks,
                    // (2) server-side injection (agent-to-agent mail), NOT human input — stated explicitly.
                    fromUser = false
                  )).void *>
                    nebflow.core.UsageTracker.record("mail", ctx.sessionId.getOrElse("")) *>
                    IO.pure(Right(
                      s"[kernel #$id] Message injected into the live kernel instance — it will process it " +
                        "at its next turn boundary; its next result is delivered back to your session."
                    ))
                case _ => IO.pure(Left(kernelNotLiveError(id)))
            }
      case _ =>
        IO.pure(Left(ToolError("Cannot deliver to the kernel leg: missing resources.")))

  // ============================================================
  // mailattach 批（2026-09-17 作者四答 = 路线 A）——`attachments` 通用附件面
  //   设计要点（plan §2 方案 A / §1 面 3）：
  //   ① **上限只引用 `AttachContract`**（件数 9 / 单件 1 GiB，作者给定数）——本文件
  //      零硬编码副本（判据：`grep` 只见 `AttachContract` 单点）；
  //   (2) validation **precedes every delivery side effect** (same order as the existing images judgment G3);
  //   (3) same-machine leg semantics (mailmodel batch 2026-09-25: the device leg is retired => only
  //       this one remains): **zero bytes are moved**; the body gets a note with "absolute path +
  //       byte count + sha256" ([[withAttachmentsNote]])
  //        —— 接收方与发送方共享同一磁盘，路径即取件；
  //   ④ 🔴 附件**不进 LLM 上下文**（与 `FriendMessageTool.scala:85` 逐字口径一致）。
  // ============================================================

  /** `attachments` 参数的**形态解析**（纯函数，与 `ImageInject.parseImagesParam` 同族口径）：
    * 缺省 ⇒ `Nil`；非数组 / 含非字符串项 ⇒ 显式错误；空白项丢弃；**件数闸**用
    * `AttachContract.checkAttachmentCount`（作者给定数单点）。 */
  private[tools] def parseAttachments(input: JsonObject): Either[ToolError, List[String]] =
    val raw = input("attachments") match
      case Some(arr) =>
        arr.asArray match
          case Some(items) =>
            items.flatMap(_.asString) match
              case strings if strings.size == items.size => Right(strings.map(_.trim).filter(_.nonEmpty).toList)
              case _ => Left(ToolError("attachments must be an array of file path strings."))
          case None => Left(ToolError("attachments must be an array of file path strings."))
      case None => Right(Nil)
    raw.flatMap(paths =>
      AttachContract.checkAttachmentCount(paths.size).left.map(e =>
        ToolError(s"${e.render} — pass at most ${AttachContract.MaxAttachmentsPerMessage} files " +
          s"(got ${paths.size}). Nothing was sent.")
      ).map(_ => paths)
    )

  /** `attachments` 的**投递前判据**（单点；两条腿共用同一次校验，任一不过 ⇒ 显式错误、
    * **零投递副作用**）。判据序：
    *   ① 形态/件数 = [[parseAttachments]]（含 `AttachContract.checkAttachmentCount`）；
    *   ② 绝对路径形态：对**原始串**判（`os.Path` 构造会把相对段绝对化 ⇒ 构造后再判恒真）；
    *   ③ 存在且**是文件**（不存在 / 是目录 ⇒ 各自显式错误，不合并成一句）；
    *   ④ 单件大小 = `AttachContract.checkFileSize`（>1 GiB ⇒ `ATTACH_TOO_LARGE` + actual/limit）。
    * 返回 = 通过判据的本地绝对路径串（`Nil` = 本次未带附件，两条腿零改动）。 */
  private[tools] def attachmentsPlan(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, List[String]]] =
    parseAttachments(input) match
      case Left(err)  => IO.pure(Left(err))
      case Right(Nil) => IO.pure(Right(Nil))
      case Right(paths) =>
        IO.blocking {
          val relative = paths.filter(p => !nebflow.core.PathUtil.isAbsolute(p))
          if relative.nonEmpty then
            Left(ToolError(
              "attachments paths must be absolute, got: " + relative.map(p => s"'$p'").mkString(", ") +
                ". The recipient resolves the path note as-is on this machine, so the paths must be " +
                "resolvable as-is — pass ABSOLUTE paths of files on this machine. Nothing was sent."
            ))
          else
            val missing = paths.filter(p => !java.nio.file.Files.exists(java.nio.file.Paths.get(p)))
            val dirs = paths.filter(p =>
              java.nio.file.Files.exists(java.nio.file.Paths.get(p)) &&
                java.nio.file.Files.isDirectory(java.nio.file.Paths.get(p))
            )
            if missing.nonEmpty then
              Left(ToolError(
                "attachments does not exist: " + missing.map(p => s"'$p'").mkString(", ") +
                  ". Nothing was sent — check the path (a file must still exist at send time; worktree or " +
                  "temporary files are often cleaned up)."
              ))
            else if dirs.nonEmpty then
              Left(ToolError(
                "attachments is a directory, not a file: " + dirs.map(p => s"'$p'").mkString(", ") +
                  ". Attach individual files."
              ))
            else
              val sizes = paths.map(p => java.nio.file.Files.size(java.nio.file.Paths.get(p)))
              AttachContract.checkMessage(sizes) match
                case Left(e) =>
                  Left(ToolError(s"${e.render} — the limit is ${AttachContract.MaxFileBytesLabel} per file and " +
                    s"${AttachContract.MaxAttachmentsPerMessage} files per message. Nothing was sent."))
                case Right(_) => Right(paths)
        }

  /** 同机腿的附件附注（**零搬字节**：接收方与发送方共享同一磁盘，路径即取件）。
    *
    * 逐件给三读数（plan §1 面 5 腿一 A-1 逐字）：**绝对路径 + 字节数 + sha256（发送时算）**。
    * 🔴 附件**不进 LLM 上下文**（与 `FriendMessageTool.scala:85` 逐字口径一致）——附注只是
    * 文本，模型想看内容必须自己 `Read`。
    * `Nil` ⇒ 原样返回（本次未带附件 ⇒ 字节级零改动，既有调用方零漂移）。 */
  private[tools] def withAttachmentsNote(message: String, attachmentPaths: List[String]): String =
    if attachmentPaths.isEmpty then message
    else
      val lines = attachmentPaths.map { p =>
        val path = java.nio.file.Paths.get(p)
        s"  - $p (${java.nio.file.Files.size(path)} B, sha256 ${sha256OfFile(path)})"
      }
      message +
        s"\n[Mail 附件] ${attachmentPaths.size} 件通用文件（本机同盘，未复制）——用 Read 读下列绝对路径取内容" +
        "（附件不进 LLM 上下文，必须先 Read）：\n" + lines.mkString("\n")

  /** 逐件 sha256（hex）。由 `SeedService.sha256File:597-599` 同款口径（`MessageDigest` +
    * `%02x`），供同机腿附注的「发送时算」读数用。读盘异常 ⇒ 附注里退化成
    * `unreadable`（附注绝不因为算摘要失败而中断投递；存在性已在 [[attachmentsPlan]] 判过）。 */
  private def sha256OfFile(path: java.nio.file.Path): String =
    try
      val md = java.security.MessageDigest.getInstance("SHA-256")
      md.digest(java.nio.file.Files.readAllBytes(path)).map("%02x".format(_)).mkString
    catch case _: Exception => "unreadable"

  /** 腿①（Nebula → 项目分发器）：保留既有内核（`ProjectActor.TriggerDispatcher`）。
    * B6（静默丢修，mailattach 2026-09-17）：本腿结构上只能收字符串
    * （`TriggerDispatcher(message: String, …)` 无 blocks 形参）⇒ `images` 无承载面，
    * 旧行为 = 静默丢，本批 = 显式拒绝（覆盖三入口：`project:` / Nebula 裸项目名 / 分发器）。 */
  private def deliverToProject(
      name: String,
      message: String,
      imagePaths: List[String],
      ctx: ToolContext,
      /** **Task id** (taskunify batch 2026-09-24): see [[layeredRoute]]. */
      task: Option[String] = None
  ): IO[Either[ToolError, String]] =
    if imagePaths.nonEmpty then IO.pure(Left(sameMachineVisionUnsupportedError(s"project:$name", imagePaths.size)))
    else
      routeToProject(name, message, ctx, task).flatMap {
        case Some(r) => IO.pure(r)
        case None =>
          IO.pure(Left(ToolError(
            s"Project '$name' is not mounted — Mail to a project triggers its dispatcher (ProjectActor.TriggerDispatcher). " +
              "Mounted projects mount at gateway startup; re-mount / restart, or check the exact name."
          )))
      }

  /** 腿③（分发器 → root）：**解析到真正的 Nebula root 会话**（追加条款②，2026-09-12）。
    * 硬禁三种静默行为：① 回落成发信者自身 ② 落到非 Nebula 的 Root 会话
    * ③ 解析失败仍报成功——解析不到即**显式报错**（并不指明合法地址面）。
    *
    * mailack 批（2026-09-23）：**分发器身份**（`ctx.isDispatcher`）的回复走
    * [[enqueueDispatcherReply]] 打包窗（B），窗末注入（C 的背压读数同处）；其余身份
    * (root / team / node) verbatim unchanged. Window closed (`≤0`) or a P0 leg = inject immediately.
    *
    * mailmodel batch (2026-09-25, ruling (d)): the `type` key is retired => the P0 exemption is
    * now judged on the **body's leading `[INTERRUPT]` literal** ([[isDispatcherMailInterrupt]],
    * a mechanism, not a message type — the A3/R7 bypass pair stays intact); the exempt leg's
    * injected eventType is always `interrupt` (the presentation-side pair is preserved), all
    * other legs always `info`. */
  private def deliverToNebulaRoot(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      ctx: ToolContext,
      system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val eventType = if isDispatcherMailInterrupt(message) then "interrupt" else "info"
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))
      case Some(res) =>
        resolveNebulaRootRef(res, senderSessionId, ctx.rootSessionId).flatMap {
          case Some((sid, ref)) =>
            val deliver = (text: String) =>
              sendMail(ref, NebulaAgentName, text, blocks, eventType, ctx, system).flatMap {
                case Right(_) => onMailDelivered(senderSessionId, sid, NebulaAgentName, text, ctx)
                case Left(err) => logger.warn(s"batched dispatcher reply delivery failed: ${err.message}")
              }
            val immediate: IO[Either[ToolError, String]] =
              sendMail(ref, NebulaAgentName, message, blocks, eventType, ctx, system).flatMap {
                case Right(_) => onMailDelivered(senderSessionId, sid, NebulaAgentName, message, ctx).as(Right(
                    s"Message sent to Nebula (root session ${sid.take(8)}). The root agent will process it."
                  ))
                case Left(err) => IO.pure(Left(err))
              }
            if !ctx.isDispatcher then immediate
            else
              enqueueDispatcherReply(message, deliver).flatMap {
                case None =>
                  // 已入窗 ⇒ 窗末由 flushDispatcherReplies 注入。
                  IO.pure(Right(
                    s"Message queued for Nebula (root session ${sid.take(8)}); dispatcher replies are coalesced per window " +
                      s"(${dispatcherMailWindowMs}ms) and injected once at the window's end."
                  ))
                case Some(_) =>
                  // 关窗 / P0 豁免 ⇒ 走立即路径（与旧行为逐字同）。
                  immediate
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
  // B/C · dispatcher→root **回复打包窗 + 背压可见化**（mailack 批 2026-09-23；本批止损主体）
  // ============================================================
  //
  // == 问题（2026-09-23 audit；作者令「任务分发器就没停过，一直在收 Mail」）==
  // 分发器每次回复 root 都是一次**立即注入**（`ref ! ImmediateInput`）⇒ root 醒一轮；
  // root 再发令又触发分发器 ⇒ 一轮往返。实测级联率 **100%**（root 40 封中 39 封在收执
  // 后 3 分钟内被再触发）、双向 **20.9 封/小时**，且分发器回执 **73% 是纯 ACK**。
  // 机制面还有两个放大器：①分发器**不回收**（空闲腿硬前提 `pendingInjected == 0`），
  // 会话实测存活 11h；②root 通知腿（`NodeEngine.enqueueRootNotify`）早有打包窗，
  // 而**分发器→root 这条腿没有** ⇒ 同一「同族通知」两腿语义不对称。
  //
  // == 方案（对齐既有先例，零新语义）==
  // 本腿加**生产者侧打包窗**，形态逐字照 `NodeEngine.RootNotifyBatch` 先例：
  // 首件起算（不随新件延长）滚动窗 + 窗末把 N 件合并为**一次**注入 + 条数上限（溢出
  // 留队下窗，不丢件）+ `windowMs <= 0` 关窗（回旧行为，运维回滚面/测试接缝）。
  // (mailmodel 2026-09-25: the P0 window exemption is judged on the BODY's first-line
  // [INTERRUPT] literal — mechanism, not a message type; see isDispatcherMailInterrupt.)
  //
  // == 作用域（硬边界）==
  // 只作用 **`ctx.isDispatcher == true` 的发送者**（引擎侧身份判据，非字符串匹配）
  // —— root / team / 节点会话走 `sendMail` 的其余调用点，**逐字不变**。
  //
  // == C · 背压可见化 ==
  // 窗口排队时长与件数**每次入队出一条 INFO 日志**；排队 >30s 时每件补一条 WARN
  // （= 「mail 排队」告警，形态对齐既有 mount-stalled 族「只告警不阻断」纪律）。
  // 积压件数另有只读读数 [[dispatcherMailPendingCount]] 供验收/面板机械核对。

  /** One batching-window entry of this leg: body + enqueue time (for backpressure
    * readings) + the **delivery closure** (used for end-of-window injection; captures
    * the ref/ctx and eventType at enqueue time — the closure is bound to this item's
    * `info`/`interrupt` grade when constructed inside [[deliverToNebulaRoot]], so the
    * end-of-window root re-resolution cannot deliver the item to the wrong session.
    * Semantics = "the reply is decided, only its injection is deferred"). mailmodel
    * batch (2026-09-25): the `mailType` field is deleted with the retired `type` key
    * (every in-window item is a non-exempt leg => eventType is always `info`; nothing
    * needs to be carried per item). */
  private final case class DispatcherMailEntry(
      text: String,
      atMs: Long,
      deliver: String => IO[Unit]
  )

  /** 缓冲状态：`entries` = 尚未注入的件（FIFO）；`windowArmed` = 本窗计时在走
    * （防同窗第二件重复起算 ⇒ 保持「首件起算、不随新件延长」）。 */
  private final case class DispatcherMailState(
      entries: Vector[DispatcherMailEntry] = Vector.empty,
      windowArmed: Boolean = false
  )

  /** 进程内缓冲（与 `NodeEngine.rootNotifyBatchState` 同款 `Ref.unsafe` 单点；进程重启
    * 即清空——遗留件**不丢**：入队只发生在注入之前，未注入件只存在于内存，重启窗口内
    * root 侧本就没有该回复，语义等价于「回复尚未发生」）。 */
  private val dispatcherMailState: Ref[IO, DispatcherMailState] =
    Ref.unsafe[IO, DispatcherMailState](DispatcherMailState())

  /** 生效窗长（现读 prop `nebflow.mail.dispatcherBatchMs`，默认 5000；`≤ 0` = 关窗）。 */
  private[tools] def dispatcherMailWindowMs: Long =
    sys.props.getOrElse("nebflow.mail.dispatcherBatchMs", "5000").trim.toLongOption.getOrElse(5000L)

  /** 生效条数上限（现读 prop `nebflow.mail.dispatcherBatchMax`，默认 10；`< 1` 归一到 1，
    * 防 0/负值把窗口变成永不排空）。 */
  private[tools] def dispatcherMailBatchMax: Int =
    math.max(1, sys.props.getOrElse("nebflow.mail.dispatcherBatchMax", "10").trim.toIntOption.getOrElse(10))

  /** 积压件数只读读数（验收/面板机械核对；不写状态、不派发）。 */
  private[tools] def dispatcherMailPendingCount: IO[Int] =
    dispatcherMailState.get.map(_.entries.size)

  /** 本窗计时是否在走（只读读数）。 */
  private[tools] def dispatcherMailWindowArmed: IO[Boolean] =
    dispatcherMailState.get.map(_.windowArmed)

  /** P0 exemption grade (mailmodel batch 2026-09-25, ruling (d) form d-1): a leg whose
    * body's **first line is the `[INTERRUPT]` literal** does not enter the buffer —
    * mechanism-based judgment, not a message type (the `type` key is retired).
    * Criterion = the first **non-empty** line, trimmed, is **verbatim-equal** to
    * `[INTERRUPT]`; the A3/R7 bypass pair stays intact (exempt => inject immediately
    * with eventType always `interrupt`; non-exempt => always enters the window). */
  private[tools] def isDispatcherMailInterrupt(text: String): Boolean =
    text.linesIterator.find(_.trim.nonEmpty).exists(_.trim == "[INTERRUPT]")

  /** 背压告警阈值：排队超过本值即对**该件**补一条 WARN（件不丢、不阻断，只提示）。 */
  private[tools] val DispatcherMailBackpressureWarnMs: Long = 30000L

  /** **入队 + 首件起算滚动窗**（分发器回复 root 的生产者侧打包入口）。
    * 返回 `None` = 已入窗（调用方按「已受理、窗末投递」回报）；
    * 返回 `Some(text)` = 旁路（关窗或 P0 豁免）⇒ 调用方走既有立即注入路径。 */
  private def enqueueDispatcherReply(
      text: String,
      deliver: String => IO[Unit]
  ): IO[Option[String]] =
    val windowMs = dispatcherMailWindowMs
    if windowMs <= 0 || isDispatcherMailInterrupt(text) then IO.pure(Some(text))
    else
      val nowMs = System.currentTimeMillis()
      dispatcherMailState
        .modify { s =>
          val arm = !s.windowArmed
          (s.copy(entries = s.entries :+ DispatcherMailEntry(text, nowMs, deliver), windowArmed = true),
            (arm, s.entries.size + 1))
        }
        .flatMap { case (armWindow, depth) =>
          (if depth >= 5 then
             logger.warn(
               s"[mail-backpressure] dispatcher reply queued (pending=$depth, window=${windowMs}ms) — dispatcher replies are coalesced per window; root is NOT woken until the window closes"
             )
           else IO.unit) *>
            (if armWindow then (IO.sleep(windowMs.millis) *> flushDispatcherReplies()).start.void else IO.unit)
              .as(None)
        }

  /** **窗口结束的唯一出口**：取队首 ≤N 件 → **一次**注入（N=1 ⇒ 正文逐字不变，走该件
    * 自带的投递闭包；N≥2 ⇒ 合并正文，走**首件**闭包并在正文内分节）⇒ 溢出件留队、计时
    * restarts with it (the previous window's remaining time is not carried over, same as the
    * existing precedent). The backpressure reading emits its WARN before injection.
    * mailmodel batch: merged items' eventType is always `info` (every in-window item is a
    * non-exempt leg — exempt items never enter the window; after the `type` key's retirement
    * there is no failed/blocked grade left to tell apart => conservative normalization, no new
    * batch-level semantics). */
  private[tools] def flushDispatcherReplies(): IO[Unit] =
    dispatcherMailState
      .modify { s =>
        val (drained, rest) = s.entries.splitAt(dispatcherMailBatchMax)
        (s.copy(entries = rest, windowArmed = rest.nonEmpty), drained.toList)
      }
      .flatMap { entries =>
        if entries.isEmpty then IO.unit
        else
          val waitedMs = entries.map(e => System.currentTimeMillis() - e.atMs).max
          val bpWarn =
            if waitedMs >= DispatcherMailBackpressureWarnMs then
              logger.warn(
                s"[mail-backpressure] dispatcher replies waited up to ${waitedMs / 1000}s before injection (batched=${entries.size})"
              )
            else IO.unit
          val body = if entries.size == 1 then entries.head.text else mergedDispatcherReplyText(entries)
          bpWarn *> entries.head.deliver(body) *>
            logger.info(s"dispatcher reply batch flushed (entries=${entries.size}, type=info)")
      }

  /** 合并正文（N≥2）：批头一行 + 逐件分节，**每件正文全文**（不折叠、不摘要、不截断）
    * => mechanically verifiable (multiset / order-preserving / lossless). Section lines look like `── [i/N] ──`
    * (mailmodel batch: the `[type]` segment is deleted with the retired `type` key). */
  private def mergedDispatcherReplyText(entries: List[DispatcherMailEntry]): String =
    val head = s"[Dispatcher 本窗 ${entries.size} 件回复（分发器→root 打包窗合并）]"
    val body = entries.zipWithIndex
      .map((e, i) => s"── [${i + 1}/${entries.size}] ──\n${e.text}")
      .mkString("\n\n")
    s"$head\n$body"

  // ── 测试接缝（`private[tools]`：spec 直调，避开真实窗长等待与 root 会话依赖；
  //    与 `NodeEngine.flushRootNotify` 的 `private[project]` spec 接缝同款先例）──
  private[tools] def enqueueDispatcherReplyForTest(
      text: String,
      deliver: String => IO[Unit]
  ): IO[Option[String]] = enqueueDispatcherReply(text, deliver)

  private[tools] def flushDispatcherRepliesWithMaxForTest(n: Int): IO[Unit] =
    dispatcherMailState
      .modify { s =>
        val (drained, rest) = s.entries.splitAt(math.max(1, n))
        (s.copy(entries = rest, windowArmed = rest.nonEmpty), drained.toList)
      }
      .flatMap { entries =>
        if entries.isEmpty then IO.unit
        else
          val body = if entries.size == 1 then entries.head.text else mergedDispatcherReplyText(entries)
          entries.head.deliver(body)
      }


  // ============================================================
  // Legacy queue layer: persisted FIFO, drained one-per-turn
  // ============================================================
  // 退役登记（delivery 退役批，2026-09-15 作者裁定 (b)；**未摘除面**）：
  // queue 模式退役、且 `delivery` 键已退役出 schema（mailparams 批，2026-09-17）⇒
  // 本层自 2026-09-15 起**在本工具面零生产调用方**（`call` 的单点前置闸——喂给它的
  // 正是墓碑读取 `deliveryTombstone`——已显式拒绝一切非设备腿的 `delivery="queue"`，
  // `layeredRoute` 结构上不再持有 `delivery` 参数）。本层**保留不动**（禁摘除）：① spec 直调
  // （`MailToolRootSenderSpec` / `MailQueueNebulaSpec` / `ColdQueueActivationSpec` /
  // `MailIdleGateWiringSpec` —— 它们钉的是 idle-gate / 冷激活 / 根解析等**已落契约**，
  // 与本批退役面正交）；② 在库消费者仍在（`AgentActor` 的 legacy 队列排空、
  // `RestApiRoutes` 的队列检视/取消端点 —— 本批禁碰）。摘除属另批另议。

  /** #28 阶段 0 §3.2：Mail(→project) 触发分发器（试点期新旧并存）。
    * queue/immediate 两个入口共用——address 是已挂载 project → 返回
    * Some(结果)（已处理：触发 ProjectActor.TriggerDispatcher 或挂载错误）；
    * 非 project 名 → None（调用方继续旧路由）。Mail 仅触发、无回报——
    * Node results are delivered along out edges (§2.7), never back through Mail.
    *
    * -- taskunify batch (2026-09-24, ruling c①: **an explicit parameter, no address-syntax
    * extension**) --
    * Semantics (the "first Mail" concept is gone):
    *   ① **without** `task` ⇒ treated as creating a dispatcher: the engine **creates a task
    *      automatically** and spawns it, and the receipt header returns the **task number**
    *      (the caller continues from it);
    *   ② **with** `task` ⇒ **continue** that task's dispatcher (active ⇒ inject / inactive ⇒
    *      append in the same way as a node message), 🔴 **never create a new one**;
    *   ③ `task` pointing at a **non-existent / already terminal** task ⇒ **refused** + a
    *      readable reason (fail-closed).
    * Also: after a successful delivery the engine **appends the Mail body as one record on
    * that task's note timeline** (structured: `from` + timestamp + body; the only write
    * path, and the tool layer has no note parameter). */
  private def routeToProject(
      address: String,
      message: String,
      ctx: ToolContext,
      task: Option[String] = None
    ): IO[Option[Either[ToolError, String]]] =
    ProjectRuntimeRegistry.get(address).flatMap {
      case None => IO.pure(None)
      case Some(rt) =>
        rt.actorRef match
          case None =>
            IO.pure(Some(Left(ToolError(s"Project '$address' has no mounted ProjectActor — re-mount it"))))
          case Some(ref) =>
            val rootSid = ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")
            // Attribution resolution (ruling c: the explicit parameter is primary, the
            // address syntax is not extended).
            // ① without task ⇒ the engine creates a task automatically (new watermark; the
            //    "first Mail" concept is gone).
            // ② with task ⇒ continue it (it must exist and not be terminal; otherwise
            //    refused, fail-closed).
            // ③ with task pointing at a non-existent / terminal task ⇒ refused + a readable
            //    reason.
            val ledger = nebflow.core.project.TaskLedgerStore.open()
            val resolved: IO[Either[ToolError, String]] = task match
              case None =>
                // r2 P0 fix (2026-09-25): the auto-create consumes the PURE ledger id
                // (`createSyncReturningId`) -- never the rendered `[OK] ...` banner. The
                // id travels below into the slot binding (TriggerDispatcher), the note
                // append and the `[task #N]` receipt header; the banner stays the `Task`
                // tool's display face only. (Pre-fix flow: the whole banner string
                // traveled as the task id -- slot keys poisoned, appendNoteSync missed,
                // and every session attached from the receipt got a TaskInfo
                // TASK_NOT_FOUND.)
                IO.blocking(ledger.createSyncReturningId(
                  title = s"$address — ${message.take(120).replace("\n", " ")}",
                  actor = nebflow.core.project.TaskLedgerHistory.Actors.Dispatcher))
              case Some(tid) =>
                IO.blocking(ledger.findSync(tid.stripPrefix("#").trim)).map {
                  case None =>
                    Left(ToolError(
                      s"Mail: no task '#${tid.stripPrefix("#")}' in the ledger — it was never created (or it was pruned after reaching " +
                        s"a terminal state). Omit `task` to have the engine create a new one and return its number. (${nebflow.core.project.TaskLedgerStore.Codes.NotFound})"))
                  case Some(entry) if entry.status != nebflow.core.project.TaskLedgerStore.Status.Open =>
                    Left(ToolError(
                      s"Mail: task '#${entry.id}' is '${entry.status}' (terminal) — a terminal task cannot be continued. " +
                        s"Omit `task` to have the engine create a new one and return its number. (${nebflow.core.project.TaskLedgerStore.Codes.Status})"))
                  case Some(entry) => Right(entry.id)
                }
            resolved.flatMap {
              case Left(err) => IO.pure(Some(Left(err)))
              case Right(taskIdStr) =>
                // Leg ① source annotation (bluebubble batch 2026-09-12): the sender travels
                // with the trigger message into the dispatcher session's injection bubble
                // header (source stays = task, ruling D-5: the value is not renamed).
                // mailbadge batch (2026-09-13, option C): **only this leg** sets `intake` --
                // the dispatcher intake face is the landing point of the author's "annotate
                // Mail with a blue bubble" ruling; `source` stays `"task"` untouched (the
                // bridge's single consumption-count point `ProjectActor:622` and the
                // `idleSince` 30 min idle window are unchanged line by line).
                // mailmodel batch (2026-09-25): the `type` key is retired — the
                // dispatcher-intake attribution is pinned to `info` (the P0 window
                // exemption is judged on the body's first-line [INTERRUPT] literal at
                // the root-injection side, not on a message type here).
                mailAttribution("info", ctx, Some(InjectionAttribution.IntakeMail)).flatMap { attribution =>
                  (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(
                    message, rootSid, ProjectActor.SourceTask, Some(attribution), Some(taskIdStr))).void *>
                    // Automatic note append (ruling n: the only write path is engine-side,
                    // never through the tool layer): the Mail body becomes one record on that
                    // task's note timeline. A history-append failure does not fail the main
                    // delivery (the Mail is already sent) but it **must be stated explicitly**
                    // (never silently).
                    IO.blocking(ledger.appendNoteSync(
                      taskIdStr, message,
                      from = nebflow.core.project.TaskLedgerHistory.Origins.Nebula,
                      actor = nebflow.core.project.TaskLedgerHistory.Actors.Nebula)) *>
                    IO.pure(Some(Right(s"[task #$taskIdStr] Project '$address' dispatcher triggered")))
                }
            }
    }

  private[tools] def deliverQueue(
      address: String,
      message: String,
      eventType: String,
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
            if canMail then resolveAndQueue(address, message, eventType, imagePaths, ctx, system, senderSessionId)
            else IO.pure(Left(nebulaDeniedError(senderName)))
          }
        else resolveAndQueue(address, message, eventType, imagePaths, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName, ctx).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None        => resolveAndQueue(address, message, eventType, imagePaths, ctx, system, senderSessionId)
        }
    }
  end deliverQueue

  /** Resolve target session (team name → lead, or short name) then queue the mail. */
  private def resolveAndQueue(
      address: String,
      message: String,
      eventType: String,
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
              case Some(targetSid) => queueToSession(targetSid, team.lead, message, eventType, imagePaths, ctx, system, senderSessionId)
              case None =>
                IO.pure(Left(ToolError(s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first.")))
          yield r
        case None =>
          for
            // Mail(→project) 路由（#28 阶段 0，§3.2 试点期新旧并存）：
            // project 名 → ProjectActor.TriggerDispatcher（触发分发器会话）。
            // 团队名路由优先（旧体系照常）；project 名兜底（新体系试点）。
            // Mail 仅做触发、无回报——节点结果沿 out 边投递（§2.7），不靠 Mail。
            pr <- routeToProject(address, message, ctx).flatMap {
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
                            queueToSession(targetSid, address, message, eventType, imagePaths, ctx, system, senderSessionId)
                          case Right(None) if address == NebulaAgentName =>
                            queueToNebula(message, eventType, imagePaths, ctx, system, senderSessionId, address, None)
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
      eventType: String,
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
            queueToSession(nebulaSid, address, withChainAnnotation(message, chainId), eventType, imagePaths, ctx, system, senderSessionId)
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
    * Visibility (device-mail batch, 2026-09-15): `private` -> `private[nebflow]` —— the former
    * device-mail intake leg (`nebflow.neblink.DeviceMailInbox`) injected into this machine's
    * Nebula session through this **single resolution point**.
    * mailmodel batch (2026-09-25, ruling (e-1)): that intake leg is retired with the tunnel face
    * (the tunnel side logs WARN and ignores `agent_mail` frames), but the **visibility is not
    * reverted** — the same-machine injection path and the specs still go through this single
    * point (a second copy of the same expression is forbidden: two copies always drift). Zero
    * semantic change, zero authorization-face change. */
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
      eventType: String,
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
      `type` = eventType,
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
      eventType: String,
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    system.resolve[AgentCommand](address).attempt.flatMap {
      case Right(ref) => sendMail(ref, address, message, blocks, eventType, ctx, system)
      case Left(err) => IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
    }

  private def deliverToShortName(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      eventType: String,
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    TeamSessionRegistry.teamOfSession(senderSessionId).flatMap {
      case None =>
        if address == NebulaAgentName && senderName != NebulaAgentName then
          canMailNebula(ctx, senderName, senderSessionId).flatMap { canMail =>
            if canMail then deliverShortNameUnscoped(address, message, blocks, eventType, ctx, system, senderSessionId)
            else IO.pure(Left(nebulaDeniedError(senderName)))
          }
        else deliverShortNameUnscoped(address, message, blocks, eventType, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName, ctx).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None =>
            deliverShortNameUnscoped(address, message, blocks, eventType, ctx, system, senderSessionId)
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
    eventType: String,
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
                  res <- deliverToSession(targetSid, team.lead, message, blocks, eventType, ctx, system)
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
            pr <- routeToProject(address, message, ctx).flatMap {
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
                              res <- deliverToSession(targetSid, address, message, blocks, eventType, ctx, system)
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
                                        res <- sendMail(ref, NebulaAgentName, message, blocks, eventType, ctx, system)
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
    eventType: String,
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
            case Some(ref) => sendMail(ref, shortName, message, blocks, eventType, ctx, system)
            case None =>
              TeamSessionRegistry.getParentActor(sessionId).flatMap {
                case Some(ref) => sendMail(ref, shortName, message, blocks, eventType, ctx, system)
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
    eventType: String,
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
        eventType = Some(eventType.toLowerCase),
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
