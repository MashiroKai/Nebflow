package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.*
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowMailStore, MailQueueStore, TeamSessionRegistry}
import nebflow.core.project.*
import nebflow.shared.{MailQueueItem, NebflowLogger, *}

import scala.concurrent.duration.*

// 定位器参数窄化,AgentActor 构造改经工厂镜像
/**
 * Agent-to-agent communication tool.
 *
 * Delivery — **every Mail is immediate**: one mode, and no parameter to choose it
 * (async send, injected at the next turn boundary — an idle recipient starts a new
 * turn, a busy recipient gets it merged into the current turn).
 *
 * The former `queue` mode (serialized FIFO persisted to disk, one mail per turn —
 * for serial task chains: "do this, then that") was **retired**:
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
 * 案 C「清死面」: the `delivery` **key has been
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
 * Mail 模型裁定族:
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
 * - the **`kernel` address leg** is added (Nebula-exclusive):
 *   `address="kernel"` starts a Kernel instance (the Delegate inner-core sub-agent)
 *   and the receipt carries the continuation address `kernel:<id>`; a later Mail to
 *   `kernel:<id>` continues that live instance. Non-Nebula callers are refused
 *   (`MAIL_KERNEL_EXCLUSIVE`) — the kernel trigger face belongs to the Nebula root
 *   only. The result of a kernel instance is delivered back to the Nebula session by
 *   the existing `source="delegate"` uplink (zero tool-face change on the return leg).
 *
 * The former `ask` mode (synchronous context fork) was removed entirely
 * — see git history if that mechanism is ever needed.
 */
object MailTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Routing rule: senders without a team context mail TEAM names only. */
  private val TeamOnlyRoutingError =
    "Agents outside a team mail TEAM names only (e.g. \"nebflow-project\") — the team Manager dispatches to members. \"team/agent\" explicit addresses and bare short names are not routable from outside a team."

  /** Nebula root agent 定义名（分层地址面与 root 解析的判据单点；值单源于 RootAgentIdentity.Name）。 */
  private val RootAgentName = RootAgentIdentity.Name

  // ============================================================
  //  target face and retirement word
  // table (**single source**; the report, specs and descriptions cite this same source;
  // a second literal copy is forbidden).
  //   MAIL_TARGET_MISSING    both missing: `address` not filled (the device leg is
  //                          retired; the target face is address-only)
  //   MAIL_DEVICE_RETIRED    a stale caller still sends `device=` => explicit refusal
  //                          (fail-closed tombstone, same shape as the `delivery`
  //                          tombstone — the schema key is deleted and the engine
  //                          does zero JSON-Schema validation => the stale key still
  //                          reaches call(), where one read line refuses it)
  //   MAIL_KERNEL_EXCLUSIVE  the kernel continuation leg is Nebula-exclusive:
  //                          every non-Nebula caller is refused
  //   MAIL_KERNEL_NOT_LIVE   the `kernel:<id>` instance is not live (terminal state /
  //                          unknown id) => explicit refusal
  //   MAIL_KERNEL_SPAWN_RETIRED  a bare `kernel` address (the former spawn leg) => explicit
  //                          refusal (Delegate is the only
  //                          kernel trigger; Mail communicates only)
  //   MAIL_PROJECT_TRIGGER_UNSUPPORTED  a project address (`project:<name>` or a bare
  //                          mounted project name) => explicit refusal — Mail carries no
  //                          project trigger face (task dispatching is Delegate's entry;
  //                          Mail communicates only)
  //   MAIL_TASK_PARAM_UNSUPPORTED  a stale caller still sends `task=` => explicit refusal
  //                          (fail-closed tombstone, same shape as the `delivery` /
  //                          `device` tombstones — the schema key is deleted and the
  //                          engine does zero JSON-Schema validation => the stale key
  //                          still reaches call(), where one read line refuses it)
  // ============================================================
  val ErrTargetMissing: String = "MAIL_TARGET_MISSING"
  val ErrDeviceLegRetired: String = "MAIL_DEVICE_UNSUPPORTED"
  val ErrKernelExclusive: String = "MAIL_KERNEL_EXCLUSIVE"
  val ErrKernelNotLive: String = "MAIL_KERNEL_NOT_LIVE"
  val ErrKernelSpawnRetired: String = "MAIL_KERNEL_SPAWN_UNSUPPORTED"
  val ErrProjectTriggerUnsupported: String = "MAIL_PROJECT_TRIGGER_UNSUPPORTED"
  val ErrTaskParamUnsupported: String = "MAIL_TASK_PARAM_UNSUPPORTED"

  /** Project-address refusal (pure constructor, for direct spec testing). */
  private[tools] def projectTriggerUnsupportedError(address: String): ToolError =
    ToolError(
      s"[$ErrProjectTriggerUnsupported] Mail does not trigger project dispatchers — '$address' is a " +
        "project address, and project addresses are not Mail targets (a bare mounted project name is " +
        "not either). Mail is a pure communication primitive: it creates no task, continues none and " +
        "revives none. Task dispatching is Delegate's face — call Delegate(task=<self-contained brief>, " +
        "project=<mounted project>) instead. Nothing was sent."
    )

  /** Stale-caller refusal for the removed `task=` parameter (pure constructor, for direct spec testing). */
  private[tools] def taskParamUnsupportedError(task: String): ToolError =
    ToolError(
      s"[$ErrTaskParamUnsupported] This tool takes no 'task' parameter — Mail carries no task-dispatch " +
        "face (it cannot create, continue or revive a task; the ledger's task numbers are not Mail " +
        "addresses). Dispatch or continue work with Delegate(task=<self-contained brief>, " +
        "project=<mounted project>) instead. Nothing was sent (task='" + task + "')."
    )

  /**
   * Both-missing error (verbatim word table; after the device leg retired, the target face is `address`-only).
   *
   * The tail pointer is SEAL-AWARE. With the
   * friends feature sealed (the default posture), SendMessage's friend/group/
   * local targets — including the this-machine local copy — answer
   * `FRIENDS_SEALED`, so the pointer names the surviving `device:` target
   * explicitly; unsealed, the original sentence is kept verbatim (zero
   * behavior change while the flag is on). `def` (not `val`): the latch is a
   * boot-time constant, but this word table must follow it without a second
   * source.
   */
  private[tools] def targetMissingMessage: String =
    s"[$ErrTargetMissing] Missing target: fill 'address' (an agent/team target — e.g. " +
      "\"delegate:<id>\"/\"node:<id>\" (Nebula root), a team name, or a member short name; project " +
      "addresses are not Mail targets — dispatching is Delegate's face). This tool takes no 'device' " +
      "parameter (MAIL_DEVICE_UNSUPPORTED); " +
      (if nebflow.core.FriendsSeal.isSealed then
         "files for another machine go via SendMessage's `device:` target — its friend/group/local " +
           "targets (including the local this-machine copy) are sealed (FRIENDS_SEALED) until the " +
           "feature is unsealed."
       else "files for another machine's user go via SendMessage.")

  /** Retirement refusal for a stale caller's `device=` (pure constructor, for direct spec testing). */
  private[tools] def deviceLegRetiredError(device: String): ToolError =
    ToolError(
      s"[$ErrDeviceLegRetired] This tool takes no 'device' parameter — cross-device " +
        "agent mail is not a Mail face, so nothing was sent and nothing is queued. Got device='" + device + "'. To move files or " +
        "text to another MACHINE use SendMessage (pure transport; that machine's agent is not informed). To " +
        "reach an AGENT use 'address': \"delegate:<id>\"/\"kernel:<id>\" or \"node:<id>\" (Nebula root only), a team name or " +
        "member short name. Project addresses are not Mail targets — dispatching is Delegate's face."
    )

  /** Kernel-leg unauthorized-caller refusal (Nebula-exclusive). */
  private[tools] def kernelExclusiveError(address: String, role: String): ToolError =
    ToolError(
      s"[$ErrKernelExclusive] The kernel address leg is EXCLUSIVE to the Nebula root session: dispatchers, " +
        "team agents and nodes cannot start or continue a kernel instance. " +
        s"Nothing was sent (address='$address', your role: $role). Route the work through Nebula instead — " +
        "mail your report to Nebula and let it dispatch."
    )

  /** Explicit refusal for the RETIRED bare-kernel spawn leg (Delegate triggers kernels; Mail communicates). */
  private[tools] def kernelSpawnRetiredError(address: String): ToolError =
    ToolError(
      s"[$ErrKernelSpawnRetired] A bare 'kernel' address does not start an instance — kernel " +
        "triggering is Delegate-only (the Delegate result carries the continuation address " +
        "kernel:<id>). Nothing was sent (address='" + address + "'). To continue a LIVE instance, " +
        "mail its address \"kernel:<id>\"; to start a new one, call Delegate."
    )

  /** Explicit refusal when the `kernel:<id>` instance is not live (fail-closed). */
  private[tools] def kernelNotLiveError(id: String): ToolError =
    ToolError(
      s"[$ErrKernelNotLive] No live kernel instance 'kernel:$id' — kernel instances are one-shot Delegate " +
        "sessions: when an instance finishes, its result is delivered back to your session and its address " +
        "stops being routable. Nothing was sent. Start a new instance with Delegate(task=...)."
    )

  // (W1 shim note: the Q5 address-face description subsystem below already
  // exists PR-side under the renamed members `descriptionRoot` /
  // `AddressFaceRoot` — main's `descriptionNebulaRoot` / `AddressFaceNebulaRoot`
  // map onto those; no duplicate was added here.)

  // ============================================================
  // dispatcher-reply 打包窗。
  // (W1 provisional shim: the subsystem below is ported verbatim from main's
  // MailTool so main-side specs compile. The SEND-path wiring that enqueues
  // dispatcher replies in production (deliverToNebulaRoot leg) lands with the
  // owning wave — until then the buffer simply stays empty.)
  // ============================================================

  /**
   * One batching-window entry of this leg: body + enqueue time (for backpressure
   * readings) + the **delivery closure** (used for end-of-window injection; captures
   * the ref/ctx and eventType at enqueue time). Semantics = "the reply is decided,
   * only its injection is deferred".
   */
  private final case class DispatcherMailEntry(
    text: String,
    atMs: Long,
    deliver: String => IO[Unit]
  )

  /**
   * 缓冲状态：`entries` = 尚未注入的件（FIFO）；`windowArmed` = 本窗计时在走
   * （防同窗第二件重复起算 ⇒ 保持「首件起算、不随新件延长」）。
   */
  private final case class DispatcherMailState(
    entries: Vector[DispatcherMailEntry] = Vector.empty,
    windowArmed: Boolean = false
  )

  /** 进程内缓冲（与 `NodeEngine.rootNotifyBatchState` 同款 `Ref.unsafe` 单点）。 */
  private val dispatcherMailState: Ref[IO, DispatcherMailState] =
    Ref.unsafe[IO, DispatcherMailState](DispatcherMailState())

  /** 生效窗长（现读 prop `nebflow.mail.dispatcherBatchMs`，默认 5000；`≤ 0` = 关窗）。 */
  private[tools] def dispatcherMailWindowMs: Long =
    sys.props.getOrElse("nebflow.mail.dispatcherBatchMs", "5000").trim.toLongOption.getOrElse(5000L)

  /** 生效条数上限（现读 prop `nebflow.mail.dispatcherBatchMax`，默认 10；`< 1` 归一到 1）。 */
  private[tools] def dispatcherMailBatchMax: Int =
    math.max(1, sys.props.getOrElse("nebflow.mail.dispatcherBatchMax", "10").trim.toIntOption.getOrElse(10))

  /** 积压件数只读读数（验收/面板机械核对；不写状态、不派发）。 */
  private[tools] def dispatcherMailPendingCount: IO[Int] =
    dispatcherMailState.get.map(_.entries.size)

  /** 本窗计时是否在走（只读读数）。 */
  private[tools] def dispatcherMailWindowArmed: IO[Boolean] =
    dispatcherMailState.get.map(_.windowArmed)

  /**
   * P0 exemption grade (): a leg whose body's **first line is the
   * `[INTERRUPT]` literal** does not enter the buffer — mechanism-based judgment.
   */
  private[tools] def isDispatcherMailInterrupt(text: String): Boolean =
    text.linesIterator.find(_.trim.nonEmpty).exists(_.trim == "[INTERRUPT]")

  /** 背压告警阈值：排队超过本值即对**该件**补一条 WARN（件不丢、不阻断，只提示）。 */
  private[tools] val DispatcherMailBackpressureWarnMs: Long = 30000L

  /**
   * **入队 + 首件起算滚动窗**（分发器回复 root 的生产者侧打包入口）。
   * 返回 `None` = 已入窗；返回 `Some(text)` = 旁路（关窗或 P0 豁免）。
   */
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
          (
            s.copy(entries = s.entries :+ DispatcherMailEntry(text, nowMs, deliver), windowArmed = true),
            (arm, s.entries.size + 1)
          )
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

    end if

  end enqueueDispatcherReply

  /**
   * **窗口结束的唯一出口**：取队首 ≤N 件 → **一次**注入（N=1 ⇒ 正文逐字不变；
   * N≥2 ⇒ 合并正文，走**首件**闭包并在正文内分节）⇒ 溢出件留队、计时重启。
   */
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

  /** 合并正文（N≥2）：批头一行 + 逐件分节，**每件正文全文**。 */
  private def mergedDispatcherReplyText(entries: List[DispatcherMailEntry]): String =
    val head = s"[Dispatcher 本窗 ${entries.size} 件回复（分发器→root 打包窗合并）]"
    val body = entries.zipWithIndex
      .map((e, i) => s"── [${i + 1}/${entries.size}] ──\n${e.text}")
      .mkString("\n\n")
    s"$head\n$body"

  // ── 测试接缝（`private[tools]`：spec 直调，避开真实窗长等待与 root 会话依赖）──
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
  // delivery 退役（保留字段、退役 queue 模式语义）
  //   ＋ 案 C「清死面」——
  // The unified explicit-refusal text for `delivery="queue"` on **every leg** (**single source**; specs cite this same source).
  //   · 语义 = `delivery` 字段已**退役出 schema**（案 C：参数面 8 → 7，该键
  //     不再对模型可见），但 `call()` 内**保留一行墓碑读取**（`deliveryTombstone`）
  //     —— every leg receiving `"queue"` is still **immediately and explicitly refused**: zero delivery side effects, zero queue persistence.
  //   · 选「显式拒绝」而非「立即化」的理由：调用方声明的**串行链语义**无法被立即投递
  //     honoured by immediate delivery —— a silent switch = a silent semantic loss (forbidden face), same direction as the existing `node:` leg's
  //     「显式拒绝，禁静默降级」先例同向；调用方拿到可读错误即可自纠。
  //   · 墓碑读取成立的判据（不是权宜）：引擎**零 JSON-Schema 校验**（`protocol.scala`
  //     自陈「面外参数会被静默忽略」）⇒ 删 schema 键**不会**让旧键到不了 `call()`；
  //     故「删键 + 墓碑判」= fail-closed，而「删键 + 删判」才是静默降级。
  //   · (the device leg's own v2.1 queue-refusal literal died with
  //     the leg itself — the single gate below now structurally covers every leg.)
  // ============================================================
  val ErrDeliveryQueueRetired: String = "MAIL_DELIVERY_QUEUE_UNSUPPORTED"

  // ============================================================
  // 附件面路线 A——「静默丢」修 B6 的**唯一来源**词表。
  //   MAIL_VISION_UNSUPPORTED_LEG  `node:` receiving `images` => explicit refusal.
  // Rationale: this leg is **structurally string-only** (`NodeEngine.sendNodeMessage(nodeId, message, _)`
  // lacks a blocks parameter) => the old behavior was "load + base64, then never use it" = silent drop (plan risk 1).
  // Now an explicit refusal, with the message giving **category + reason + alternative path** and
  // naming the legs that can carry images.
  // (the `project:` leg and the bare `kernel` spawn leg are refused earlier with their own
  // codes — MAIL_PROJECT_TRIGGER_UNSUPPORTED / MAIL_KERNEL_SPAWN_UNSUPPORTED — so they never
  // reach this gate; the device leg retired whole => its B7 4000-char body
  // gate (`MaxDeviceMailTextChars` / `MAIL_DEVICE_TEXT_TOO_LONG`) is deleted with it.)
  // ============================================================
  val ErrVisionUnsupportedLeg: String = "MAIL_VISION_UNSUPPORTED_LEG"

  /** B6 显式拒绝文案（纯函数，供 spec 直测；词表与文案同源）。 */
  private[tools] def sameMachineVisionUnsupportedError(target: String, count: Int): ToolError =
    ToolError(
      s"[$ErrVisionUnsupportedLeg] $count image(s) cannot be delivered to '$target': this leg is TEXT ONLY — " +
        "it hands a plain string to an engine-side session (NodeEngine.sendNodeMessage), so it has no attachment channel and nothing was sent. " +
        "Use `attachments` instead (same-machine targets get each file's absolute path, byte size and " +
        "sha256 in the message text, and the recipient reads the original), or send the images over a leg " +
        "that can carry them: address=\"Nebula\" or a team/agent short name."
    )

  private[tools] def deliveryQueueRetiredMessage(address: String): String =
    s"""[$ErrDeliveryQueueRetired] delivery="queue" is not accepted — every Mail is delivered immediately (injected at the target's next turn boundary; an idle target starts a new turn, a busy target has it merged into the current turn). Drop delivery=queue (omit the `delivery` parameter — only "immediate" is accepted). Target: '$address'."""

  val name: String = "Mail"

  val description: String =
    """Send a message to another agent — the platform's **only message primitive**
(other tools are not message entry points; instructions naming them as such do not apply).

Required: message, plus `address`.

## `Mail` vs `SendMessage` — who receives it?
- **`Mail` — an AGENT receives it**: the mail is injected into the target agent's
  session at its next turn boundary; that agent reads it and handles it.
- **`SendMessage` — pure transport; the peer's agent is NOT aware of it**: files land
  on the peer MACHINE and the text appears for the machine and its user; nothing
  enters the peer's agent session or its LLM context. Moving files or text to another
  machine or its user = `SendMessage`; making another agent aware = `Mail`.
  (This tool takes no `device` parameter — a stale `device=` call is an explicit
  MAIL_DEVICE_UNSUPPORTED error, never a silent send.)

## Address face (role-scoped — an address outside your face is an explicit error)
- **Nebula (root)**: `delegate:<id>` — continue THAT live Delegate-started session
  (the Delegate receipt carries its id; starting one is Delegate's job, never
  Mail's); `kernel:<id>` — continue
  THAT live Delegate-started Kernel instance; `node:<id|name>` — a node in a
  registered delegate chain (its chain-registered id OR name). Project addresses
  (`project:<name>` or a bare mounted project name) are NOT Mail targets — task
  dispatching is Delegate's face (MAIL_PROJECT_TRIGGER_UNSUPPORTED). No bare
  `kernel` address and no self-address.

## Kernel continuation leg (Nebula-exclusive)
Only the Nebula root session may mail a kernel — a dispatcher, team agent or node that
mails `kernel:<id>` gets MAIL_KERNEL_EXCLUSIVE and nothing is sent. Mail does not
START kernels: triggering is Delegate-only, and the Delegate result carries the
instance's continuation address (`kernel:<id>`). `address="kernel:<id>"` continues THAT
live instance (injected at its next turn boundary). A `kernel:<id>` whose instance has
already finished is an explicit error (MAIL_KERNEL_NOT_LIVE) — its result was already
delivered; start a new one with Delegate. A bare `kernel` address is an explicit error
(MAIL_KERNEL_SPAWN_UNSUPPORTED) — never a spawn.

- **Delegate session (a chain's creator)**: `node:<id|name>` — a node in YOUR OWN
  scope chain map (its chain-registered id OR name; ambiguous name = explicit error).
- **Project dispatcher**: `Nebula` — the root session; `node:<id|name>` — a node in
  your current project (from NodeList; the node's id OR its name). You do not mail
  your own project.
- **Team context**: a team name (e.g. "nebflow-project") routed to its
  lead agent, a bare member short name (resolved within your team first), or an
  explicit "team/agent" route.

An address that is not recognizable in your face is an **explicit error** — there
is no silent fallback and no fuzzy matching.

## `node:<id>` routing semantics (by target node status; engine: `NodeEngine.sendNodeMessage`)
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

Registered task chains resolve this address through the chain ledger: `node:<…>` may carry a chain
node's id OR its name (exact id match wins; an ambiguous name is an explicit error listing the
candidates). Authorization: the creating delegate and the root — pending nodes get the text
appended to their brief, running nodes receive it at their next turn boundary, finished nodes
refuse (re-run via a fresh chain instead).

Images (optional `images` parameter — up to 5 absolute local image paths,
PNG/JPG/JPEG/GIF/WEBP/BMP), injected as **vision blocks**. **Agent targets (`address`)
that reach a chat turn** (Nebula / a team or agent short name / a live `kernel:<id>`
instance): the recipient sees the images directly (vision models) plus their paths as
text. **`node:` targets are text-only** — that
leg hands a plain string to an engine-side session, so `images` there is an **explicit
error** and nothing is sent; use `attachments` (path mode) instead, or target `Nebula` /
a team agent.

Attachments (optional `attachments` parameter — up to 9 absolute local paths of ANY file
type, each up to 1024 MB = 1 GiB) are how a non-image file — or any file you do not need
the model to *see* — reaches the recipient. **Same-machine targets** (`address` =
`node:…` / `kernel:<id>` / `Nebula` / a team or agent short name): nothing is
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
  There is no delivery mode to choose: every Mail is immediate. A caller that
  passes `delivery="queue"` (a serialized FIFO mode — one Mail per turn — that
  this tool does not offer) is an
  explicit error (MAIL_DELIVERY_QUEUE_UNSUPPORTED) on every target — it is
  NEVER silently downgraded to immediate.
  Dispatcher-to-root replies are coalesced into ONE injection per 5-second window;
  a reply that must bypass the window leads its body with the literal token
  [INTERRUPT] on the first line."""

  // ============================================================
  // 地址面**只分化 `description`**，`inputSchema`
  // 逐字节不变（规格 §4.4 D-2 的差距 = 一个角色读到另两个角色的地址面：
  // `:49-60` 工具级 address 节 + `:106` 参数级 description）。
  //
  // 做法 = **段投影（纯删段）**：地址面按角色切成逐字段常量（全部取自基础
  // `description` 的既有字节），分化变体 = 基础里**只保留本角色的那一段**。
  // 零新造字（spec `ToolFaceVariantSpec` 断言每个段常量逐字节出现在基础里 +
  // 变体 = 基础删去他角色段的结果）；非地址面字节一律**逐字节原样**（子串手术，
  // 不重排、不改写）。
  //
  // 未分化面（登记在案）：`team context` 段 —— 定义层
  // 无 team 成员身份维度；`inputSchema.properties.address.description`（参数级
  // 三面并集）—— Q5 口径冻结 `inputSchema` 不动。
  // ============================================================

  /**
   * **基础变体**（= 上面的 `description`）：**逐字节不变**——未登记/未知身份的
   * 会话看到的那一份（fail-closed 默认面）。
   */
  val descriptionBase: String = description

  /** 地址面各段的**逐字常量**（逐字节取自基础 `description`；由 spec 逐段断言）。 */
  private[nebflow] val AddressFaceHeader: String =
    "## Address face (role-scoped — an address outside your face is an explicit error)\n"

  private[nebflow] val AddressFaceRoot: String =
    "- **Nebula (root)**: `delegate:<id>` — continue THAT live Delegate-started session\n  (the Delegate receipt carries its id; starting one is Delegate's job, never\n  Mail's); `kernel:<id>` — continue\n  THAT live Delegate-started Kernel instance; `node:<id|name>` — a node in a\n  registered delegate chain (its chain-registered id OR name). Project addresses\n  (`project:<name>` or a bare mounted project name) are NOT Mail targets — task\n  dispatching is Delegate's face (MAIL_PROJECT_TRIGGER_UNSUPPORTED). No bare\n  `kernel` address and no self-address.\n\n## Kernel continuation leg (Nebula-exclusive)\nOnly the Nebula root session may mail a kernel — a dispatcher, team agent or node that\nmails `kernel:<id>` gets MAIL_KERNEL_EXCLUSIVE and nothing is sent. Mail does not\nSTART kernels: triggering is Delegate-only, and the Delegate result carries the\ninstance's continuation address (`kernel:<id>`). `address=\"kernel:<id>\"` continues THAT\nlive instance (injected at its next turn boundary). A `kernel:<id>` whose instance has\nalready finished is an explicit error (MAIL_KERNEL_NOT_LIVE) — its result was already\ndelivered; start a new one with Delegate. A bare `kernel` address is an explicit error\n(MAIL_KERNEL_SPAWN_UNSUPPORTED) — never a spawn.\n\n"

  private[nebflow] val AddressFaceDelegate: String =
    "- **Delegate session (a chain's creator)**: `node:<id|name>` — a node in YOUR OWN\n  scope chain map (its chain-registered id OR name; ambiguous name = explicit error).\n"

  private[nebflow] val AddressFaceDispatcher: String =
    "- **Project dispatcher**: `Nebula` — the root session; `node:<id|name>` — a node in\n  your current project (from NodeList; the node's id OR its name). You do not mail\n  your own project.\n"

  private[nebflow] val AddressFaceTeam: String =
    "- **Team context**: a team name (e.g. \"nebflow-project\") routed to its\n  lead agent, a bare member short name (resolved within your team first), or an\n  explicit \"team/agent\" route.\n"

  private[nebflow] val AddressFaceClosing: String =
    "\nAn address that is not recognizable in your face is an **explicit error** — there\nis no silent fallback and no fuzzy matching."

  /**
   * 地址面投影：保留首尾公共段 + **本角色的段**，其余字节原样。任一段定位失败
   * （文案被改动）⇒ **fail-closed 回落基础 description**（绝不产出半截地址面）。
   */
  private def addressFaceProjection(keep: String): String =
    val h = descriptionBase.indexOf(AddressFaceHeader)
    val c = descriptionBase.indexOf(AddressFaceClosing)
    if h < 0 || c < 0 then descriptionBase
    else
      descriptionBase.substring(0, h) + AddressFaceHeader + keep + AddressFaceClosing +
        descriptionBase.substring(c + AddressFaceClosing.length)

  /**
   * **分化变体 · Nebula root**：地址面只留 root 自己的那一面（`project:<name>`）。
   */
  val descriptionRoot: String = addressFaceProjection(AddressFaceRoot)

  /**
   * **分化变体 · project dispatcher**：地址面只留分发器自己的面（`Nebula` / `node:<id>`）。
   */
  val descriptionDispatcher: String = addressFaceProjection(AddressFaceDispatcher)

  /**
   * 定义期变体（[[nebflow.agent.AgentCore.schemaVariantFor]] 消费）：**只替换
   * `description`**——`inputSchema` 逐字节不变（Q5 判据）。身份未知 ⇒ 基础面。
   */
  def addressFaceVariant(base: ToolDefinition, nebulaRoot: Boolean, dispatcher: Boolean): ToolDefinition =
    if nebulaRoot then base.copy(description = descriptionRoot)
    else if dispatcher then base.copy(description = descriptionDispatcher)
    else base

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "address" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Role-scoped address. Mail is a pure communication primitive — it carries no task-dispatch face. Nebula (root): \"delegate:<id>\" continues THAT live Delegate-started session (the Delegate receipt carries its id — starting one is Delegate's job, never Mail's); \"node:<id|name>\" addresses a node in a registered delegate chain. Project dispatcher: \"Nebula\" or \"node:<nodeId>\". Team context: a team name, a member short name, or \"team/agent\". A project address (\"project:<name>\" or a bare mounted project name) is an explicit error (MAIL_PROJECT_TRIGGER_UNSUPPORTED) — dispatching is Delegate's face. An address outside your face is an explicit error.".asJson
        ),
        "message" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or question to send".asJson
        ),
        "chainId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional chain id (e.g. \"chain-n-933b5a8c\") of the batch this Mail belongs to. Validated against the project's chain registry: the derived chain set (declared chains ∪ fallback-derived components) ∪ chain-level dependency targets ∪ every chain id registered in the chain ledger (after a chain re-id the superseded old id stays reachable via its alias). An id that is registered nowhere is an explicit error (MAIL_CHAIN_NOT_FOUND). Not persisted anywhere; when provided it is embedded verbatim in the injected text so the recipient can quote it back. REQUIRED when reporting a batch close-out to Nebula.".asJson
        ),
        "images" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> 5.asJson,
          "description" -> ("Optional absolute local image paths (PNG/JPG/JPEG/GIF/WEBP/BMP, max 5), injected as " +
            "vision blocks. For `address` targets that reach a chat turn (Nebula / team / agent short name / a " +
            "live `kernel:<id>` instance) the recipient sees the images directly plus their paths as text. NOT " +
            "supported on `node:` (or the kernel START leg) — those legs are text-only, so passing " +
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
    // the device/type keys are retired => the label face
    // is address-only (the old `device:` target prefix and the ` [TYPE]` tag are gone with them).
    // delivery 退役：标签面不再有 `, queue` 形态 —— queue 模式退役后
    // 该值只可能是**已拒绝**的旧调用方，标签不得再宣称 queue 投递（描述面清理的连带面）。
    s"Mail(→$addr)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("").trim
    val message = input("message").flatMap(_.asString).getOrElse("")

    // ------------------------------------------------------------
    // 🔴 墓碑读取（案 C「清死面」
    // 以同一形态加了 device 墓碑）——**这两个键都已退役出 schema**，本段仅作**退役墓碑**，
    // 不是活参数：
    //   · `delivery` 的唯一用途 = 判旧调用方送来的 `"queue"` 并报
    //     `MAIL_DELIVERY_QUEUE_RETIRED`（见下方单点闸）。其余值一律不再需要分支
    //     （投递形态只剩 immediate）。
    //   · `device` 的唯一用途 = 判旧调用方仍送 `device=` 并报 `MAIL_DEVICE_RETIRED`
    //     （跨设备 agent-mail 腿两端同批退役；文案给出出路：机器面用 SendMessage、
    //     agent 面用 `address`）。
    // 为什么删了 schema 键还要读它：引擎**零 JSON-Schema 校验**（`protocol.scala` 自陈
    // 「面外参数会被静默忽略」）⇒ 旧键照样到达此处 ⇒ 读一行即可把「显式拒绝、禁静默降级」
    // 口径原样维持（删净本段 = 静默忽略 = 判例反向）。
    // ------------------------------------------------------------
    val deliveryTombstone = input("delivery").flatMap(_.asString).getOrElse("immediate")
    val deviceTombstone = input("device").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val chainIdRaw = input("chainId").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).filter(_ != "null")
    // 墓碑读取（project-trigger 拆除批）：`task` 键已退役出 schema —— 它曾是 project
    // 腿的「续任务/复活」参数，该腿整体拆除后此键零消费者。保留一行墓碑读取：旧调用方
    // 送来的任何非空 `task=` 一律显式拒绝（fail-closed，与 `delivery` / `device` 墓碑
    // 同形态——引擎零 JSON-Schema 校验 ⇒ 旧键照样到达此处；静默忽略 = 静默语义损失：
    // 调用方会以为任务收到了这条消息）。
    val taskTombstone =
      input("task").flatMap(_.asString).map(_.trim.stripPrefix("#")).filter(_.nonEmpty).filter(_ != "null")

    // Mail 模型裁定: the `device` tombstone gate — **ahead of
    // every other judgment and delivery side effect** (same order as the existing
    // fail-fast discipline; the key is no longer in the schema, only stale callers send it).
    if deviceTombstone.isDefined then IO.pure(Left(deviceLegRetiredError(deviceTombstone.get)))
    else if address.isEmpty then IO.pure(Left(ToolError(targetMissingMessage)))
    else if message.isEmpty then IO.pure(Left(ToolError("Missing required parameter: message")))
    // delivery 退役：`delivery="queue"` ⇒
    // **显式拒绝**（零副作用，先于 chainId 校验与一切路由/投递）。
    // 案 C 后本闸的输入来自**墓碑读取**（`deliveryTombstone`，
    // 该键已不在 schema）——判据与文案**逐字不变**：这是「删 schema 键、不删拒绝」的落点。
    // 单点兜住全部腿（`node:` / `project:` / Nebula / team 短名 / 裸项目名）——结构性保证
    // 「本工具面零 queue 入口」。（设备腿已退役 ⇒ 不再有它自己的字面量；
    // 旧设备腿调用方在上面的 device 墓碑处被拒，永不走到本闸。）
    else if deliveryTombstone == "queue" then IO.pure(Left(ToolError(deliveryQueueRetiredMessage(address))))
    // task 墓碑闸：任何非空 `task=` 显式拒绝（先于 chainId 校验与一切路由/投递）。
    else if taskTombstone.isDefined then IO.pure(Left(taskParamUnsupportedError(taskTombstone.get)))
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
                  // 同机腿附件 = **附注 + 零搬字节**。
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
                          // R2 分层地址面（细则 + B1-a）：角色专属
                          // 地址形态先在这一层定判——命中即处理（含**显式报错**），未命中
                          // （= 该地址不属于本角色的分层面）才落回既有 team/短名瀑布。
                          // 硬禁静默兜底与模糊匹配：认不出的地址一律显式报错并指明合法面。
                          // `imagePaths` 一并下传：`project:` / `node:` 两条腿**结构上**
                          // 只能收字符串 ⇒ `images` 在它们身上是显式拒绝（B6 静默丢修）。
                          layeredRoute(
                            address,
                            effectiveMessage,
                            blocks,
                            imagePaths,
                            chainId,
                            ctx,
                            system
                          ) match
                            case Some(action) => action
                            case None =>
                              // 案 C：本层原有一个 `delivery match`
                              // —— 「`"immediate"`」与「陌生值 ⇒ 打 WARN 后收敛到 immediate」两支。
                              // schema 键删除后**只剩一条投递路径**（immediate 是唯一形态），
                              // 故该分支连同其 WARN 一并删除（设计件 §4.4(a)「其余值不再需要分支」），
                              // 不再有任何按键值分派的逻辑。非 `queue` 的旧值由此与「键缺席」
                              // **逐字同一结果**（残差读数见交付报告）。
                              // the `type` parameter is retired with
                              // the key —— eventType is now judged by the **leading `[INTERRUPT]`
                              // literal of the body** (same single-point criterion as deliverToRoot):
                              // a literal first line => `interrupt`, otherwise `info`. The dispatcher->root
                              // leg's batching-window exemption uses the same judgment (see
                              // deliverToRootAgent / isDispatcherMailInterrupt).
                              val legacyEventType =
                                if isDispatcherMailInterrupt(effectiveMessage) then "interrupt" else "info"
                              if address.contains("://") then
                                deliverToAddress(address, effectiveMessage, blocks, legacyEventType, ctx, system)
                              else deliverToShortName(address, effectiveMessage, blocks, legacyEventType, ctx, system)
                      end match
                  }
              }
      }
    end if
  end call

  // ============================================================
  // R2 分层地址面
  // ============================================================

  /**
   * 发送者角色（授权面分层判据 = **引擎侧身份**，不用 senderName 字符串匹配）。
   * S6=C（N1 刀3 · v3 §7.2）新增 Delegate 面：delegate 会话（引擎侧
   * `DelegateSessions.isDelegateSession` 前缀判据单点）的地址面 = 自己作用域的
   * `node:<id|name>` 腿（挂载面授权在 buildAllowedToolSet 尾段，工具内本枚举
   * 分层为第二保险——双保险同 TaskBoard/node_report 纪律）。
   */
  private enum SenderRole:
    case Root, Dispatcher, Delegate, Teamish

  private def roleOf(ctx: ToolContext): SenderRole =
    if ctx.isDispatcher then SenderRole.Dispatcher
    else if nebflow.core.delegate.DelegateSessions.isDelegateSession(ctx.sessionId) then SenderRole.Delegate
    else if ctx.agentDef.exists(_.name == MailTool.RootAgentName) then SenderRole.Root
    else SenderRole.Teamish

  private val NodePrefix = "node:"
  private val ProjectPrefix = "project:"

  /**
   * kernel leg: the `kernel:<id>` continuation
   * form's prefix (the bare `kernel` form = start a new instance, judged in
   * [[layeredRoute]]). id = the full session id `delegate-kernel-<8hex>` (the registry
   * key, unambiguous).
   */
  private val KernelPrefix = "kernel:"

  /** 统一 delegate 续聊地址前缀。 */
  private val DelegatePrefix = "delegate:"

  private def rootFace: String =
    "\"delegate:<id>\"/\"kernel:<id>\"（续聊）或 \"node:<节点id或名>\"（已登记 delegate 链）——项目地址（project:<名> 或裸挂载项目名）不是 Mail 面，派发走 Delegate"
  private def dispatcherFace: String = "\"Nebula\"（root）或 \"node:<节点id或名>\""
  private def delegateFace: String = "\"node:<节点id或名>\"（你自己作用域链图内的节点）"

  /** 分层地址面的显式越界报错（细则：错误消息必须指明**该角色的合法地址面**）。 */
  private def outOfFaceError(address: String, role: SenderRole): ToolError =
    val face = role match
      case SenderRole.Root => rootFace
      case SenderRole.Dispatcher => dispatcherFace
      case SenderRole.Delegate => delegateFace
      case SenderRole.Teamish => "a team name, a member short name, or \"team/agent\""
    ToolError(
      s"Address '$address' is outside your address face. Your role may only mail: $face. " +
        "There is no silent fallback and no fuzzy matching — use one of the listed forms."
    )

  private def unresolvableError(address: String, role: SenderRole): ToolError =
    ToolError(
      s"Cannot resolve address '$address' — it is not a recognizable target in your address face (${role match
          case SenderRole.Root => rootFace
          case SenderRole.Dispatcher => dispatcherFace
          case SenderRole.Delegate => delegateFace
          case SenderRole.Teamish => "a team name, a member short name, or \"team/agent\""
        }). No fallback was applied."
    )

  /**
   * 分层地址分派。返回 Some(结果) = 本地址形态属分层面（已处理，含显式报错）；
   * None = 不属分层面（调用方继续既有 team/短名瀑布——D-6：legacy 面不随批收口）。
   *
   * delivery 退役：本层**不再持有 `delivery` 参数**——「非设备腿禁
   * queue」由 `call` 的单点前置闸统一下判（该闸在设备腿分支之后、本层之前），
   * 故本层结构上**零 queue 分支**（`project:` 腿原先「两分支同体」也已折成单支）。
   * project-trigger 拆除批：本层**不再持有 `task` 参数**——project 腿已拆除，
   * `task=` 由 `call` 的墓碑闸统一下判。project 地址（`project:<name>` 形态与
   * Root 的裸挂载项目名）= 显式拒绝，零触发面（派发 = Delegate 唯一入口）。
   */
  private def layeredRoute(
    address: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    imagePaths: List[String],
    chainId: Option[String],
    ctx: ToolContext,
    system: ActorSystem
  ): Option[IO[Either[ToolError, String]]] =
    val role = roleOf(ctx)
    // ── delegate leg: the Nebula-exclusive
    // continuation address ── `delegate:<id>` 续聊/补充；旧 `kernel:<id>` 形态
    // 作为别名同路（同一 id 空间，存量回执不打断），裸 `kernel` 保持墓碑。
    if address == "kernel" then Some(IO.pure(Left(kernelSpawnRetiredError(address))))
    else if address.startsWith(DelegatePrefix) || address.startsWith(KernelPrefix) then
      val (prefix, isLegacyForm) =
        if address.startsWith(DelegatePrefix) then (DelegatePrefix, false) else (KernelPrefix, true)
      Some(
        if role != SenderRole.Root then IO.pure(Left(kernelExclusiveError(address, role.toString)))
        else deliverToDelegate(address.stripPrefix(prefix).trim, message, blocks, ctx, system, isLegacyForm)
      )
    else if address.startsWith(NodePrefix) then
      // S6=C（N1 刀3 · v3 §7.2）：`node:<id|name>` 授权面扩 = 创建者 delegate ＋
      // root（现分发器语境照旧）。三腿分治：
      //   - delegate 会话 → 自己作用域链图（NodeTools.resolveRuntime 作用域单点，
      //     双寻址 A5 序：id 精确命中即止 → name 唯一 → 歧义列候选不猜）；
      //   - project dispatcher → 既有 per-project 腿（ctx.projectName 解析域），
      //     同一双寻址单点；
      //   - root → 跨全部已挂载 delegate 作用域解析（root 无单一项目语境；>1 作用
      //     域命中 = 显式歧义列 scope 限定候选）。
      //   - teamish → 既有越界报错（合法面文案指明，零静默兜底）。
      val nodeRef = address.stripPrefix(NodePrefix).trim
      Some(
        if nodeRef.isEmpty then IO.pure(Left(ToolError(s"Malformed address '$address' — expected \"node:<节点id或名>\".")))
        else if role == SenderRole.Delegate then
          countMailUsage(
            deliverToScopeNode(nodeRef, withChainAnnotation(message, chainId), imagePaths, ctx),
            chainId,
            ctx
          )
        else if role == SenderRole.Dispatcher then
          countMailUsage(
            deliverToNode(nodeRef, withChainAnnotation(message, chainId), imagePaths, ctx),
            chainId,
            ctx
          )
        else if role == SenderRole.Root then
          countMailUsage(
            deliverToRootNodeRef(nodeRef, withChainAnnotation(message, chainId), imagePaths, ctx),
            chainId,
            ctx
          )
        else IO.pure(Left(outOfFaceError(address, role)))
      )
    else if address.startsWith(ProjectPrefix) then
      // Project-trigger 拆除：project 地址对**一切角色**都是显式拒绝（Mail 不再
      // 触发项目分发器，零建任务、零复活、零 TriggerDispatcher——派发面唯一入口 =
      // Delegate）。与裸 `kernel` 起实例腿同款退役形态：统一拒绝词 + 指路 Delegate，
      // 硬禁静默降级。
      Some(IO.pure(Left(projectTriggerUnsupportedError(address))))
    else if address == MailTool.RootAgentName then
      role match
        case SenderRole.Root =>
          Some(
            IO.pure(
              Left(
                ToolError(
                  s"Address \"Nebula\" is your own (self) address — it is not in your address face ($rootFace)."
                )
              )
            )
          )
        case SenderRole.Dispatcher =>
          Some(
            countMailUsage(
              deliverToRootAgent(address, withChainAnnotation(message, chainId), blocks, ctx, system),
              chainId,
              ctx
            )
          )
        case SenderRole.Teamish => None // 既有 canMailRoot 闸不变
        // S6=C：delegate 会话的面 = 自己作用域的 node: 腿——Nebula 地址 = 显式越界
        // （合法面文案指明；零静默兜底，与 canMailRoot 的 team 闸语义不同层不混）。
        case SenderRole.Delegate => Some(IO.pure(Left(outOfFaceError(address, role))))
    else if role == SenderRole.Root then
      // Nebula 的裸名形态 = 裸项目名。Project-trigger 拆除后：命中已挂载项目 ⇒
      // 与 `project:` 形态同款显式拒绝（零触发）；未命中 ⇒ 显式 unresolvable。
      // 注册表探测在此只作**分类**（这是不是项目名），不作触发。
      Some(
        ProjectRuntimeRegistry.get(address).flatMap {
          case Some(_) => IO.pure(Left(projectTriggerUnsupportedError(address)))
          case None => IO.pure(Left(unresolvableError(address, role)))
        }
      )
    else if role == SenderRole.Dispatcher then
      // 分发器不给自己项目发（细则）——裸名一律越界报错。
      Some(IO.pure(Left(outOfFaceError(address, role))))
    else None
    end if
  end layeredRoute

  /**
   * chainId 逐字进注入文本（R-18；腿②③一致）。只影响注入体——**不落 Mail 自身任何库**
   * （R-17/B2-x「只校验、不落库」逐字保留）；引用**计数**另经批三+ 的 `mail-usage` 面钩子
   * 记进链号台账（[[countMailUsage]] → `ChainLedgerStore.noteReference` 的 `externalRefs`）
   * ——「mail 参数入库」与「引用面计数」是两件事，本行前句不因后者改写。
   */
  private def withChainAnnotation(message: String, chainId: Option[String]): String =
    chainId match
      case Some(id) => s"[mail chainId: $id]\n$message"
      case None => message

  /** chainmodel 批三+：引用面 id 字面量（与 `ChainLedger.ReferenceFaces` 登记逐字同值）。 */
  private val FaceMailUsage = "mail-usage"

  /**
   * **引用面 `mail-usage` 计数钩子（轴 b）** —— 登记表 `incWhen` 的逐字落点：「Mail 携带
   * chainId **且投递成功**」。
   *
   * 只包**正文已注入链号注解**的两条腿（`node:` 腿 / Nebula 腿，注解单点 =
   * [[withChainAnnotation]]）：注解没进正文的腿（`project:` / 裸项目名 / 短名 / 设备腿 ——
   * 设备腿的 chainId 明确「只校验不带出」）**结构上不产生**该引用 ⇒ 不计数。
   * `Left`（未投递 / 越界 / 终态拒绝 / 校验失败）⇒ **零计数**（引用没发生）。
   * best-effort：落账失败只 WARN（见 `NodeEngine.noteChainReference`），不回滚已投递的 Mail。
   */
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

  /**
   * 引用面计数落点（best-effort；`projectName` 空 ⇒ 无可计之处）。
   *
   * 「项目未挂载」对本钩子**不可达**：`node:` 腿在项目未挂载时本就投递失败（⇒ `Left` ⇒
   * 不计数），Nebula 腿的发信会话恒属已挂载项目。故该分支为断言式 `IO.unit`（不猜、不静默
   * 吞错——真出偏差时 `NodeEngine` 侧 WARN 仍可观察）。
   */
  private def noteChainReferences(ctx: ToolContext, ids: List[String], faceId: String): IO[Unit] =
    ctx.projectName.map(_.trim).filter(_.nonEmpty) match
      case None => IO.unit
      case Some(name) =>
        ProjectRuntimeRegistry.get(name).flatMap {
          case Some(rt) =>
            ids.distinct.foldLeft(IO.unit)((acc, id) => acc *> rt.engine.noteChainReference(id, faceId))
          case None => IO.unit
        }

  /**
   * B2-x / R-17 + **chainmodel 批三 ①（chainmail）**：`chainId` **只校验、不落库**
   * （本方法零副作用；台账只**读**、**禁回填**）。无项目上下文时只做形态校验（无链集可对）；
   * 有项目上下文则按**台账解析**给出可达集合：
   *   声明链 ∪ 兜底派生链 ∪ 链级依赖目标链 ∪ 台账已登记旧号别名
   * （判据单点 = `NodeEngine.mailChainIds` / `resolveMailChainId` → `FlowMapStore`；
   * 热面未命中再经台账冷档兜底 ⇒ 「链号改号后旧号永久可达」，见设计件 §二(b)/(c) 第 4 面）。
   *
   * 🔴 **负判据保留**（判红线）：完全未登记号（含归档区封存链号）照旧
   * `MAIL_CHAIN_NOT_FOUND`（`MAIL_CHAIN_NOT_FOUND` = 非空但不可达）——禁把校验放宽成
   * 「未知也放行」。改造前口径 = 纯派生链集比对（零台账），旧号在链号重归后必然失效。
   */
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
                      Left(
                        ToolError(
                          s"Unknown chainId '$id' in project '$projectName' (MAIL_CHAIN_NOT_FOUND). " +
                            s"Known chains: ${
                                if ids.isEmpty then "(none registered)" else ids.toList.sorted.mkString(", ")
                              }. " +
                            "chainId is validated only — it is never persisted; omit it if the Mail does not belong to a batch."
                        )
                      )
                    }
                }
            }

  /**
   * 本会话作为发信方的**来源标注**（单点构造）：
   * 接管方 agent 自身名（与前端 `ownAgentName()` 同名空间，见
   * [[nebflow.agent.InjectionAttribution]] 的字段名/取值域契约）+ 所属 Team 名
   * + 邮件类型（eventType）。两条项目腿（腿① 项目、腿② 节点）与 leg③
   * `sendMail` 共用同一取值口径 —— 三种 Mail 形态的气泡顶栏因此同源。
   *
   * `intake`（「必须显示 MAIL」⇒ 选项 C）是
   * **调用方声明的收件通道判别**，**不由本方法统一置位**：本方法是共用构造点，
   * 各腿收件面**呈现口径不同** ——
   *   - 旧腿①（`routeToProject` → 分发器收件面）：曾传
   *     [[InjectionAttribution.IntakeMail]] ⇒ 标签显示 `Mail`。Project-trigger
   *     拆除批后该腿整体退役 ⇒ 本工具面**零置位点**（`intake` 键保留 = 存量
   *     历史行渲染 + 透传面契约不变；落盘行照常解码）；
   *   - 腿②（`deliverToNode` → 节点收件面）：**不传**（默认 `None`）——
   *     `source` 保持 `"system"` ⇒ 标签恒 `System` + `[NODE-MESSAGE]` 文本头
   *     逐字不变（节点收件面单独立项）；
   *   - 腿③（`sendMail` → 非 project 面）：**不传**（默认 `None`）——
   *     `source` 已是 `"mail"` ⇒ 标签恒 `Mail`，呈现零漂移。
   * 若在此统一置位，腿② 的标签会被抬成 `Mail` ⇒ **越界扩面**（禁动面）。
   *
   * `project` 与 `intake` **相反**：**三条腿同源置位**
   * ——取本会话所属项目（`ToolContext.projectName`）= 四段式 `PROJECT` 段的**链首级**
   * 「发送方所属项目」。与 `intake`（收件通道的**呈现判别**，逐腿口径不同）无关：
   * 「发送方项目域」在任一收件面上都不改变该腿的标签语义，故单点置位零越界。
   * 腿②（→`node:`，发送方与本会话同项目）往返零漂移；腿①（`project:`，跨项目 /
   * 越面调用）由此从 **收件方**项目**纠正为发送方项目**。`None`（本会话无项目上下文，
   * 如 Nebula root / 团队会话）⇒ 发射面回落根域 `NEBULA`（`leg1SenderProject`）。
   */
  private def mailAttribution(
    eventType: String,
    ctx: ToolContext,
    intake: Option[String] = None
  ): IO[InjectionAttribution] =
    val senderName = ctx.agentDef.map(_.name).getOrElse(MailTool.RootAgentName)
    TeamSessionRegistry.teamOfSession(ctx.sessionId.getOrElse("")).map { team =>
      InjectionAttribution(
        sender = Some(senderName),
        senderTeam = team,
        eventType = Some(eventType.toLowerCase),
        intake = intake,
        project = ctx.projectName
      )
    }

  end mailAttribution

  /**
   * 腿②（分发器 → 节点）：**复用引擎侧单点** `NodeEngine.sendNodeMessage`——三态判据
   * 与三个错误码**不复制**（复制必然漂移）。注入 source 保持 `"system"`（D-3：
   * 节点侧既有呈现零 UI 行为变化）；来源标注经 attribution 参数传（sender/
   * senderTeam/eventType），节点会话蓝气泡顶栏因此可辨「来自谁」。
   *
   * S6=C（N1 刀3 · A5 双寻址）：地址 payload 升格为 `<id|name>`——解析单点 =
   * [[NodeTools.resolveNodeRefIO]]（id 精确命中即止 → name 唯一 → 歧义显式列候选
   * `CHAIN_ADDR_AMBIGUOUS` / 零命中 `CHAIN_NODE_NOT_FOUND`；活动区未命中兜归档区，
   * 与 NodeEdit/NodeList/NodeCancel 三调用点同一判据源）。解析域 = 调用会话的
   * per-project face（ctx.projectName，既有判定零改动）。
   */
  private def deliverToNode(
    nodeRef: String,
    message: String,
    imagePaths: List[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // B6（静默丢修）：本腿结构上只能收字符串 ⇒ `images` 无承载面。
    // 旧行为 = 静默丢（零报错、零投递）；现行为 = 显式拒绝（判据与文案见
    // [[sameMachineVisionUnsupportedError]]）。
    if imagePaths.nonEmpty then IO.pure(Left(sameMachineVisionUnsupportedError(s"node:$nodeRef", imagePaths.size)))
    else
      ctx.projectName match
        case None | Some("") =>
          IO.pure(
            Left(
              ToolError(
                s"Cannot route to node '$nodeRef' — this session has no project context (the 'node:' leg resolves the project from the calling session)."
              )
            )
          )
        case Some(projectName) =>
          ProjectRuntimeRegistry.get(projectName).flatMap {
            case Some(rt) =>
              NodeTools.resolveNodeRefIO(rt, nodeRef).flatMap {
                case Left(err) => IO.pure(Left(ToolError(err)))
                case Right(node) =>
                  mailAttribution("info", ctx).flatMap { attribution =>
                    rt.engine.sendNodeMessage(node.id, message, Some(attribution)).map(_.left.map(ToolError(_)))
                  }
              }
            case None =>
              IO.pure(
                Left(
                  ToolError(
                    s"Project '$projectName' is not mounted — cannot route to node '$nodeRef'. Re-mount / restart (projects mount at startup)."
                  )
                )
              )
          }

  /**
   * 腿②′（S6=C · N1 刀3 · v3 §7.2 ①授权面扩）：**delegate 会话 → 自己作用域链图**
   * 的 `node:<id|name>` 腿。解析域 = [[NodeTools.resolveRuntime]]（delegate 会话只
   * 解析自己作用域图——查无宿主 `DELEGATE_SCOPE_UNAVAILABLE` fail-closed、显式他项目
   * `DELEGATE_SCOPE_FOREIGN`，与 NodeEdit/NodeList/NodeCancel 同一单点）；双寻址 =
   * [[NodeTools.resolveNodeRefIO]]（A5 序，同上）。投递 = 同一引擎单点
   * `sendNodeMessage`（三态判据 + `node-message` EventLog 审计皆现成，零第二实现）。
   * 挂载面授权在 buildAllowedToolSet 尾段（本腿为工具内第二保险）。
   */
  private def deliverToScopeNode(
    nodeRef: String,
    message: String,
    imagePaths: List[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    if imagePaths.nonEmpty then IO.pure(Left(sameMachineVisionUnsupportedError(s"node:$nodeRef", imagePaths.size)))
    else
      NodeTools.resolveRuntime(None, ctx).flatMap {
        case Left(err) => IO.pure(Left(ToolError(err)))
        case Right(rt) =>
          NodeTools.resolveNodeRefIO(rt, nodeRef).flatMap {
            case Left(err) => IO.pure(Left(ToolError(err)))
            case Right(node) =>
              mailAttribution("info", ctx).flatMap { attribution =>
                rt.engine.sendNodeMessage(node.id, message, Some(attribution)).map(_.left.map(ToolError(_)))
              }
          }
      }

  /**
   * 腿②″（S6=C · N1 刀3 · v3 §7.2 ①授权面扩）：**root → 已登记 delegate 链节点**。
   * root 无单一项目语境 ⇒ 解析域 = **全部已挂载 delegate 作用域宿主**
   * （[[nebflow.core.project.DelegateFlowHosts.all]]）；每域内 A5 序双寻址（同一
   * [[NodeTools.resolveNodeRefIO]] 单点），跨域 >1 命中 = 显式歧义列 **scope 限定**
   * 候选（不猜不取首）；恰 1 命中 = 投该域引擎（同一 `sendNodeMessage` 单点）；零
   * 命中 = 显式未找到（提示到属主 delegate 会话 NodeList 核对）。per-project 分发
   * 器腿不经此腿（逐字节不变）。
   */
  private def deliverToRootNodeRef(
    nodeRef: String,
    message: String,
    imagePaths: List[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    if imagePaths.nonEmpty then IO.pure(Left(sameMachineVisionUnsupportedError(s"node:$nodeRef", imagePaths.size)))
    else
      nebflow.core.project.DelegateFlowHosts.all.flatMap { hosts =>
        hosts
          .traverse { h =>
            NodeTools.resolveNodeRefIO(h.projectRuntime, nodeRef).map(_.map(n => (h, n)))
          }
          .flatMap { hits =>
            val resolved = hits.collect { case Right(pair) => pair }
            val failures = hits.collect { case Left(err) => err }
            if resolved.size > 1 then
              val candidates =
                resolved
                  .map((h, n) => s"scope ${h.delegateId} → ${n.id}(${n.task.getOrElse("").take(30)})")
                  .mkString(", ")
              IO.pure(
                Left(
                  ToolError(
                    s"'$nodeRef' matches ${resolved.size} nodes across delegate scopes — $candidates — " +
                      "address by node id, or rename. (CHAIN_ADDR_AMBIGUOUS)"
                  )
                )
              )
            else
              resolved.headOption match
                case Some((h, node)) =>
                  mailAttribution("info", ctx).flatMap { attribution =>
                    h.engine.sendNodeMessage(node.id, message, Some(attribution)).map(_.left.map(ToolError(_)))
                  }
                case None =>
                  IO.pure(
                    Left(
                      ToolError(
                        s"no node '$nodeRef' (id or name) in any mounted delegate chain scope" +
                          (if hosts.isEmpty then " (no delegate scopes are mounted right now)"
                           else
                             s" (${hosts.size} scope(s) checked${
                                 if failures.isEmpty then ""
                                 else "; per-scope lookup notes: " + failures.mkString(" | ")
                               })") +
                          " — check NodeList on the owning delegate session. (CHAIN_NODE_NOT_FOUND)"
                      )
                    )
                  )
            end if
          }
      }

  // ============================================================
  // kernel leg ("Delegate triggers kernels; Mail is demoted to communication"): the ONLY kernel address leg left is
  // `kernel:<id>` = inject into that **live** Delegate-spawned instance (registry
  // kind=Delegate with session-id match); after the instance goes terminal (one-shot
  // Delegate semantics: the result has already been returned to root along
  // source="delegate") the address is **unroutable** => explicit refusal
  // ([[kernelNotLiveError]]), same family as the `node:` leg's three-state judgment
  // ("running injectable / terminal refused").
  // The bare-kernel SPAWN leg (`address="kernel"`) is
  // RETIRED: triggering is Delegate-only; a bare "kernel" address is an
  // explicit MAIL_KERNEL_SPAWN_RETIRED refusal in [[layeredRoute]] (fail-closed
  // tombstone, never a silent forward).
  // The Nebula-exclusivity ruling carries over verbatim: a non-root caller mailing
  // `kernel:<id>` is refused ([[kernelExclusiveError]], SenderRole != Root =>
  // MAIL_KERNEL_EXCLUSIVE). The result-return chain has zero tool-face change (the
  // existing BackoffSupervisor parent uplink).
  // ============================================================

  /**
   * Kernel-leg delivery — continuation only (the caller's NebulaRoot identity has
   * already been verified in [[layeredRoute]]; the bare-kernel SPAWN leg retired in
   * Delegate is the only kernel trigger).
   */
  /**
   * delegate 续聊腿——`delegate:<id>`：
   *   - **live**：注入 UserInput（replyTo=supervisor，turn 边界消费，结果仍走
   *     `source="delegate"` 回投链）——与原 kernel 腿逐字同机制；
   *   - **不 live（终态/重启后）**：内置执行器 ⇒ 以补充信息为输入**续起**一个
   *     新会话（同 executor/project/cwd/taskId 记账，DelegateRegistry 是数据源）；
   *     外部执行器 ⇒ ExternalSessionRunner 续聊（#1225 真通路：CLI resume ／
   *     mailbox 排队——live 来信排队、终态一条 resume 消化；S15 注入通道，
   *     合并轮同门）。地址面零新面（增补任务书 3.5）。
   * `kernel:<id>` 是同 id 空间的旧形态别名（isLegacyForm=true 时回执提示新形态）。
   */
  private def deliverToDelegate(
    id: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    ctx: ToolContext,
    system: ActorSystem,
    isLegacyForm: Boolean
  ): IO[Either[ToolError, String]] =
    (ctx.sharedResources, ctx.actorSystem) match
      case (Some(res), Some(sys)) =>
        if id.isEmpty then
          IO.pure(
            Left(
              ToolError(
                s"Malformed address — expected \"delegate:<instanceId>\" (the id the Delegate receipt carried)."
              )
            )
          )
        else
          DelegateRegistry.find(id) match
            case None =>
              IO.pure(
                Left(
                  ToolError(
                    s"No delegate instance '$id' on record (DELEGATE_NOT_FOUND) — the Delegate receipt's continuation address is authoritative; check it verbatim."
                  )
                )
              )
            case Some(record) =>
              res.agentRegistry.get.flatMap { reg =>
                reg.get(id) match
                  case Some(rec) if rec.kind == AgentKind.Delegate =>
                    (rec.ref ! AgentCommand.UserInput(
                      text = message,
                      replyTo = rec.supervisorRef,
                      source = Some("mail"),
                      sender = ctx.agentDef.map(_.name),
                      eventType = Some("info"),
                      blocks = blocks,
                      fromUser = false
                    )).void *>
                      nebflow.core.UsageTracker.record("mail", ctx.sessionId.getOrElse("")) *>
                      IO.pure(
                        Right(
                          s"[delegate #$id] Message injected into the live instance — it will process it at its next turn boundary; its next result is delivered back to your session."
                        )
                      )
                  case _ =>
                    // 不 live：外部执行器 ⇒ resume 续聊 / mailbox 排队（external-executors 真通路替换 DELEGATE_EXECUTOR_PENDING 墓碑——同一
                    // `delegate:<id>` 地址面零新面，S15 注入通道）；内置执行器 ⇒ 续起。
                    // 续起腿的 parentRef = 原记录 parentSessionId 的**活引用**（续聊
                    // 结果回传锚点）——历史上这里传 None ⇒ BackoffSupervisor/
                    // ExternalSessionRunner 的终态通知成了 no-op，续聊会话跑完后结果
                    // 静默蒸发（实测 2026-10-09 两起：会话从存活列表消失、父会话零
                    // 消息、工作实际已完成）。父不 live 时传 None 也可，监督者的
                    // terminal-result-undelivered 兜底会发出可见告警，绝不静默。
                    resolveContinuationParentRef(res, record).flatMap { parentRef =>
                      if record.executor != nebflow.core.executor.ExecutorRegistry.DefaultId then
                        nebflow.core.executor.ExternalSessionRunner
                          .continueExternal(
                            record,
                            message,
                            parentRef = parentRef,
                            wsSend = ctx.wsSend,
                            resources = res
                          )
                          .map(_.left.map(ToolError(_)))
                      else continueDelegate(record, id, message, ctx, sys, res, parentRef)
                    }
              }
      case _ =>
        IO.pure(Left(ToolError("Cannot deliver to the delegate leg: missing resources.")))

  /**
   * 续聊腿的结果回传锚点：原 delegate 记录里 `parentSessionId`（当初派发者会话，
   * 通常是 Nebula 根）的**当前活引用**。None = 父会话此刻不在统一注册表（已删除/
   * 重启后未重挂）——续聊仍可起，但其终态结果没有可投递对象，由监督者的
   * terminal-result-undelivered 兜底发出可见告警（绝不允许静默丢失）。
   */
  private def resolveContinuationParentRef(
    res: nebflow.core.AgentRuntimePort,
    record: DelegateRegistry.Record
  ): IO[Option[ActorRef[AgentCommand]]] =
    if record.parentSessionId.isEmpty then IO.pure(None)
    else res.agentRegistry.get.map(_.get(record.parentSessionId).map(_.ref))

  /** 终态 delegate 的续起：补充信息即新输入，座位/项目/台账继承记录。 */
  private def continueDelegate(
    record: DelegateRegistry.Record,
    id: String,
    message: String,
    ctx: ToolContext,
    system: ActorSystem,
    res: AgentRuntimePort,
    parentRef: Option[ActorRef[AgentCommand]]
  ): IO[Either[ToolError, String]] =
    val brief =
      s"""[continuation of delegate $id — the instance finished; this supplement is your new primary instruction]
[workspace] ${record.cwd} (file tools need absolute paths; Bash cwd is not guaranteed)

$message"""
    DelegateTool
      .spawnBackground(
        // 提示词来源拆分批（2026-10-08）：续聊复活腿同属 delegate 面——走
        // `delegateEntry`（delegate_report 纪律的系统提示词），与新鲜派发腿
        // （DelegateTool.spawnKernel）同路；节点面恒走 entry，两来源机械可分。
        agentDef = nebflow.core.entity.BuiltinAgents.delegateEntry(DelegateTool.KernelAgentName).map(_.toAgentDef).get,
        task = brief,
        description = s"continuation: ${record.title}",
        workspace = record.cwd,
        project = record.project,
        taskId = record.taskId,
        system = system,
        resources = res,
        parentDepth = 0,
        // 结果回传锚点（mail-continuation delivery 修 2026-10-09）：续起会话的监督者
        // 必须拿到原父会话的活引用，终态 ExternalEvent 才有投递对象——此前恒 None，
        // 续聊结果在 notifyParentAndStop 的 None 分支被静默丢弃。
        parentRef = parentRef,
        wsSend = None,
        parentSessionId = Some(record.parentSessionId),
        rootSessionId = record.parentSessionId
      )
      .map {
        case Left(err) => Left(err)
        case Right((newId, _)) =>
          record.taskId.foreach { tid =>
            scala.util.Try(
              TaskLedgerStore
                .open()
                .appendNoteSync(
                  tid,
                  s"Mail supplement → continued as $newId",
                  from = TaskLedgerHistory.Origins.Nebula,
                  actor = TaskLedgerHistory.Actors.Nebula
                )
            )
          }
          val legacy =
            if id != newId then s" (the finished instance $id stays archived; this is a fresh session)" else ""
          val parentLiveNote = parentRef match
            // 父不 live：立即说破（Mail 时点可见），终态还有监督者兜底告警——双端都不静默。
            case None =>
              s"\nNOTE: the original parent session '${record.parentSessionId}' is not live right now, so this continuation's " +
                "result CANNOT be delivered back to it automatically — when the session finishes the outcome is logged loudly " +
                "and recorded in the task panel instead of being injected."
            case Some(_) => ""
          Right(
            s"[delegate #$id] Instance was not live — your message CONTINUED the delegate as a fresh session $newId$legacy, same workspace and task ledger. Its result is delivered back when it finishes.$parentLiveNote"
          )
      }
  end continueDelegate

  // ============================================================
  // 附件面路线 A——`attachments` 通用附件面
  //   设计要点（plan §2 方案 A / §1 面 3）：
  //   ① **上限只引用 `AttachContract`**（件数 9 / 单件 1 GiB）——本文件
  //      零硬编码副本（判据：`grep` 只见 `AttachContract` 单点）；
  //   (2) validation **precedes every delivery side effect** (same order as the existing images judgment G3);
  //   (3) same-machine leg semantics (the device leg is retired => only
  //       this one remains): **zero bytes are moved**; the body gets a note with "absolute path +
  //       byte count + sha256" ([[withAttachmentsNote]])
  //        —— 接收方与发送方共享同一磁盘，路径即取件；
  //   ④ 🔴 附件**不进 LLM 上下文**（与 `FriendMessageTool.scala:85` 逐字口径一致）。
  // ============================================================

  /**
   * `attachments` 参数的**形态解析**（纯函数，与 `ImageInject.parseImagesParam` 同族口径）：
   * 缺省 ⇒ `Nil`；非数组 / 含非字符串项 ⇒ 显式错误；空白项丢弃；**件数闸**用
   * `AttachContract.checkAttachmentCount`（上限数单点）。
   */
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
      AttachContract
        .checkAttachmentCount(paths.size)
        .left
        .map(e =>
          ToolError(
            s"${e.render} — pass at most ${AttachContract.MaxAttachmentsPerMessage} files " +
              s"(got ${paths.size}). Nothing was sent."
          )
        )
        .map(_ => paths)
    )

  end parseAttachments

  /**
   * `attachments` 的**投递前判据**（单点；两条腿共用同一次校验，任一不过 ⇒ 显式错误、
   * **零投递副作用**）。判据序：
   *   ① 形态/件数 = [[parseAttachments]]（含 `AttachContract.checkAttachmentCount`）；
   *   ② 绝对路径形态：对**原始串**判（`os.Path` 构造会把相对段绝对化 ⇒ 构造后再判恒真）；
   *   ③ 存在且**是文件**（不存在 / 是目录 ⇒ 各自显式错误，不合并成一句）；
   *   ④ 单件大小 = `AttachContract.checkFileSize`（>1 GiB ⇒ `ATTACH_TOO_LARGE` + actual/limit）。
   * 返回 = 通过判据的本地绝对路径串（`Nil` = 本次未带附件，两条腿零改动）。
   */
  private[tools] def attachmentsPlan(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, List[String]]] =
    parseAttachments(input) match
      case Left(err) => IO.pure(Left(err))
      case Right(Nil) => IO.pure(Right(Nil))
      case Right(paths) =>
        IO.blocking {
          val relative = paths.filter(p => !nebflow.shared.PathUtil.isAbsolute(p))
          if relative.nonEmpty then
            Left(
              ToolError(
                "attachments paths must be absolute, got: " + relative.map(p => s"'$p'").mkString(", ") +
                  ". The recipient resolves the path note as-is on this machine, so the paths must be " +
                  "resolvable as-is — pass ABSOLUTE paths of files on this machine. Nothing was sent."
              )
            )
          else
            val missing = paths.filter(p => !java.nio.file.Files.exists(java.nio.file.Paths.get(p)))
            val dirs = paths.filter(p =>
              java.nio.file.Files.exists(java.nio.file.Paths.get(p)) &&
                java.nio.file.Files.isDirectory(java.nio.file.Paths.get(p))
            )
            if missing.nonEmpty then
              Left(
                ToolError(
                  "attachments does not exist: " + missing.map(p => s"'$p'").mkString(", ") +
                    ". Nothing was sent — check the path (a file must still exist at send time; worktree or " +
                    "temporary files are often cleaned up)."
                )
              )
            else if dirs.nonEmpty then
              Left(
                ToolError(
                  "attachments is a directory, not a file: " + dirs.map(p => s"'$p'").mkString(", ") +
                    ". Attach individual files."
                )
              )
            else
              val sizes = paths.map(p => java.nio.file.Files.size(java.nio.file.Paths.get(p)))
              AttachContract.checkMessage(sizes) match
                case Left(e) =>
                  Left(
                    ToolError(
                      s"${e.render} — the limit is ${AttachContract.MaxFileBytesLabel} per file and " +
                        s"${AttachContract.MaxAttachmentsPerMessage} files per message. Nothing was sent."
                    )
                  )
                case Right(_) => Right(paths)
            end if
          end if
        }

  /**
   * 同机腿的附件附注（**零搬字节**：接收方与发送方共享同一磁盘，路径即取件）。
   *
   * 逐件给三读数（plan §1 面 5 腿一 A-1 逐字）：**绝对路径 + 字节数 + sha256（发送时算）**。
   * 🔴 附件**不进 LLM 上下文**（与 `FriendMessageTool.scala:85` 逐字口径一致）——附注只是
   * 文本，模型想看内容必须自己 `Read`。
   * `Nil` ⇒ 原样返回（本次未带附件 ⇒ 字节级零改动，既有调用方零漂移）。
   */
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

  /**
   * 逐件 sha256（hex）。由 `SeedService.sha256File:597-599` 同款口径（`MessageDigest` +
   * `%02x`），供同机腿附注的「发送时算」读数用。读盘异常 ⇒ 附注里退化成
   * `unreadable`（附注绝不因为算摘要失败而中断投递；存在性已在 [[attachmentsPlan]] 判过）。
   */
  private def sha256OfFile(path: java.nio.file.Path): String =
    try
      val md = java.security.MessageDigest.getInstance("SHA-256")
      md.digest(java.nio.file.Files.readAllBytes(path)).map("%02x".format(_)).mkString
    catch case _: Exception => "unreadable"

  /**
   * 腿③（分发器 → root）：**解析到真正的 Nebula root 会话**。
   * 硬禁三种静默行为：① 回落成发信者自身 ② 落到非 Nebula 的 Root 会话
   * ③ 解析失败仍报成功——解析不到即**显式报错**（并不指明合法地址面）。
   */
  private def deliverToRootAgent(
    address: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    // the `type` parameter is retired with the key --
    // the event type is judged here by the body's leading `[INTERRUPT]` literal
    // (same single-point criterion as the dispatcher-reply window exemption;
    // [[isDispatcherMailInterrupt]]). A literal first line => `interrupt`, else `info`.
    val eventType = if isDispatcherMailInterrupt(message) then "interrupt" else "info"
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))
      case Some(res) =>
        resolveRootRef(res, senderSessionId, ctx.rootSessionId).flatMap {
          case Some((sid, ref)) =>
            sendMail(ref, RootAgentName, message, blocks, eventType, ctx, system).flatMap {
              case Right(_) =>
                onMailDelivered(senderSessionId, sid, RootAgentName, message, ctx).as(
                  Right(
                    s"Message sent to Nebula (root session ${sid.take(8)}). The root agent will process it."
                  )
                )
              case Left(err) => IO.pure(Left(err))
            }
          case None => IO.pure(Left(rootUnresolvedError(senderSessionId)))
        }
    end match
  end deliverToRootAgent

  private def rootUnresolvedError(senderSessionId: String): ToolError =
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
  // 退役登记（delivery 退役；**未摘除面**）：
  // queue 模式退役、且 `delivery` 键已退役出 schema（案 C）⇒
  // 本层自此起**在本工具面零生产调用方**（`call` 的单点前置闸——喂给它的
  // 正是墓碑读取 `deliveryTombstone`——已显式拒绝一切非设备腿的 `delivery="queue"`，
  // `layeredRoute` 结构上不再持有 `delivery` 参数）。本层**保留不动**（禁摘除）：① spec 直调
  // （`MailToolRootSenderSpec` / `MailQueueNebulaSpec` / `ColdQueueActivationSpec` /
  // `MailIdleGateWiringSpec` —— 它们钉的是 idle-gate / 冷激活 / 根解析等**已落契约**，
  // 与退役面正交）；② 在库消费者仍在（`AgentActor` 的 legacy 队列排空、
  // `RestApiRoutes` 的队列检视/取消端点 —— 禁碰）。摘除属另议。
  //
  // Project-trigger 拆除批：本层原有的 queue→project 对称腿（`routeToProject` 直调，
  // 「#28 阶段 0 §3.2 试点期新旧并存」）已随触发链整体拆除——queue 形态下项目名
  // 不再触达 `ProjectActor.TriggerDispatcher`（该形态本就生产不可达；摘除的是
  // D-5 登记的「退役未摘除」残面，与立即路径的拆除同批对称收口）。

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
        if address == RootAgentName && senderName != RootAgentName then
          canMailRoot(ctx, senderName, senderSessionId).flatMap { canMail =>
            if canMail then resolveAndQueue(address, message, eventType, imagePaths, ctx, system, senderSessionId)
            else IO.pure(Left(rootDeniedError(senderName)))
          }
        else resolveAndQueue(address, message, eventType, imagePaths, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName, ctx).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None => resolveAndQueue(address, message, eventType, imagePaths, ctx, system, senderSessionId)
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
              case Some(targetSid) =>
                queueToSession(targetSid, team.lead, message, eventType, imagePaths, ctx, system, senderSessionId)
              case None =>
                IO.pure(
                  Left(
                    ToolError(s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first.")
                  )
                )
          yield r
        case None =>
          // Project-trigger 拆除批：本 case 原有的 queue→project 对称腿
          // （`routeToProject`，#28 阶段 0 §3.2）已整体拆除——queue 形态下项目名
          // 不再触达分发器触发（生产本就不可达；见本层头部退役登记）。
          for
            senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
            sr <- senderTeamOpt match
              case None if address != RootAgentName =>
                IO.pure(Left(ToolError(TeamOnlyRoutingError)))
              case _ =>
                for
                  targetRes <- ctx.sharedResources match
                    case Some(res) =>
                      TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
                    case None => IO.pure(Right(None))
                  sr2 <- targetRes match
                    case Left(ambErr) => IO.pure(Left(ToolError(ambErr)))
                    case Right(Some(targetSid)) =>
                      queueToSession(
                        targetSid,
                        address,
                        message,
                        eventType,
                        imagePaths,
                        ctx,
                        system,
                        senderSessionId
                      )
                    case Right(None) if address == RootAgentName =>
                      queueToRoot(message, eventType, imagePaths, ctx, system, senderSessionId, address, None)
                    case Right(None) => mailNotFound(address)
                yield sr2
          yield sr
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
   * canMailRoot senders reach here with address == "Nebula".
   */
  private[tools] def queueToRoot(
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
        resolveRootSession(res, senderSessionId, ctx.rootSessionId).flatMap {
          case Some(rootSid) =>
            queueToSession(
              rootSid,
              address,
              withChainAnnotation(message, chainId),
              eventType,
              imagePaths,
              ctx,
              system,
              senderSessionId
            )
          case None => IO.pure(Left(rootUnresolvedError(senderSessionId)))
        }
      case None => IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))

  /**
   * The Nebula root agent's sessionId — **the true Nebula root**, not "any Root record"
   * (追加条款). 解析序（两档，任一档解析不出 ⇒ None ⇒ 调用方显式报错）：
   *   ① **preferredRootSid**（调用方会话的 `ctx.rootSessionId`）——spawn 本分发器会话的
   *      那个 root 会话，确定性最高、零歧义；命中失败**不**静默改选别的 Root 会话；
   *   ② 无 preferred 时按 **session meta**（`agentName == "Nebula"`）判定，且必须唯一。
   * 两档都**排除发信者自身**（硬禁「回落成发信者自身」）。
   */
  private[tools] def resolveRootSession(
    res: AgentRuntimePort,
    senderSessionId: String,
    preferredRootSid: Option[String] = None
  ): IO[Option[String]] =
    resolveRoots(res, senderSessionId, preferredRootSid).map(_.headOption.map(_._1))

  /**
   * Root records eligible as "the Nebula root"（判据见 [[resolveRootSession]] 文档）。
   * 返回 0 或 ≥2 项都由调用方判为「解析不出」——**绝不**静默挑一条。
   *
   * Visibility `private` -> `private[nebflow]` —— the former
   * device-mail intake leg (`nebflow.neblink.DeviceMailInbox`) injected into this machine's
   * Nebula session through this **single resolution point**.
   *  that intake leg is retired with the tunnel face
   * (the tunnel side logs WARN and ignores `agent_mail` frames), but the **visibility is not
   * reverted** — the same-machine injection path and the specs still go through this single
   * point (a second copy of the same expression is forbidden: two copies always drift). Zero
   * semantic change, zero authorization-face change.
   */
  private[nebflow] def resolveRoots(
    res: AgentRuntimePort,
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
                if meta.flatMap(_.agentName).contains(RootAgentName) then List((rec.sessionId, rec.ref)) else Nil
              }
            }
    }

  private def resolveRootRef(
    res: AgentRuntimePort,
    senderSessionId: String,
    preferredRootSid: Option[String]
  ): IO[Option[(String, ActorRef[AgentCommand])]] =
    resolveRoots(res, senderSessionId, preferredRootSid).map {
      case List(one) => Some(one)
      case _ => None // 0 命中或 ≥2 命中（歧义）都算解析不出 → 显式报错
    }

  /**
   * Persist to MailQueueStore, activate target, send MailQueued command.
   * private[tools] for ColdQueueActivationSpec (issue #22).
   */
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
    val senderName = ctx.agentDef.map(_.name).getOrElse(RootAgentIdentity.Name)
    val item = MailQueueItem(
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
          _ <- emitWsEvent(
            ctx,
            Json.obj(
              "type" -> "mailQueued".asJson,
              "sessionId" -> sessionId.asJson,
              "from" -> senderName.asJson,
              "to" -> shortName.asJson,
              "preview" -> message.take(200).asJson,
              "pendingCount" -> pendingCount.asJson,
              "timestamp" -> item.timestamp.asJson
            )
          )
        yield refOpt match
          case Some(_) =>
            Right(
              s"Message queued to $shortName. Will be processed after current work completes (position #$pendingCount in queue)."
            )
          case None =>
            // #22 honesty: activation failed (session meta or agent def not
            // loadable). The item IS on disk and will drain on the agent's
            // next successful activation — but claiming "will be processed"
            // unconditionally was a silent-loss lie.
            Left(
              ToolError(
                s"Message persisted to $shortName's queue (position #$pendingCount), but cold activation FAILED — session metadata or agent definition not found for session ${sessionId.take(8)}. " +
                  s"The item will drain once the agent is loadable (check the agent exists in its team). See logs: \"[mail] activation failed\"."
              )
            )
      case _ =>
        IO.pure(Left(ToolError(s"Cannot deliver queue mail to '$shortName': missing resources")))
    end match
  end queueToSession

  private def emitWsEvent(ctx: ToolContext, event: Json): IO[Unit] =
    ctx.wsSend match
      case Some(send) => send(event).handleErrorWith(_ => IO.unit)
      case None => IO.unit

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
        if address == RootAgentName && senderName != RootAgentName then
          canMailRoot(ctx, senderName, senderSessionId).flatMap { canMail =>
            if canMail then deliverShortNameUnscoped(address, message, blocks, eventType, ctx, system, senderSessionId)
            else IO.pure(Left(rootDeniedError(senderName)))
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
   * `ctx.isDispatcher`)** — 该细则 narrows "who can reach root" to that
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
  private[tools] def canMailRoot(ctx: ToolContext, senderName: String, senderSessionId: String): IO[Boolean] =
    if ctx.isDispatcher then IO.pure(true)
    else
      TeamSessionRegistry.isManager(senderSessionId).flatMap { isMgr =>
        if isMgr then IO.pure(true)
        else if senderName.isEmpty then IO.pure(false)
        else EntityLoader.listTeams().map(_.values.exists(_.lead == senderName))
      }

  /**
   * 「不能给 root 发」的显式归因文案（R-15）：对**项目分发器**身份，旧文案
   * 「You are a team worker」是错误归因（它不是 team worker）——分发器走
   * `ctx.isDispatcher` 判据本就不会命中本分支；此处文案按身份分档。
   */
  private def rootDeniedError(senderName: String): ToolError =
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
          // Project-trigger 拆除批（D-4 缺口同批关闭）：裸名先过**挂载项目**分类——
          // 命中已挂载项目 ⇒ 与 `project:` 形态同款显式拒绝（零触发；旧形态在此
          // 会隐式触发分发器，且无发送方角色闸）。未命中 ⇒ 照旧落 short-name 瀑布。
          ProjectRuntimeRegistry.get(address).flatMap {
            case Some(_) => IO.pure(Left(projectTriggerUnsupportedError(address)))
            case None =>
              // Not a team name — short agent name. Routable only for senders
              // with a team context (same-team priority); outside a team, only
              // team names and "Nebula" are valid (user ruling 08-24 — the
              // team/agent escape hatch is closed for root senders).
              for
                senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
                sr <- senderTeamOpt match
                  case None if address != RootAgentName =>
                    IO.pure(Left(ToolError(TeamOnlyRoutingError)))
                  case _ =>
                    for
                      targetRes <- ctx.sharedResources match
                        case Some(res) =>
                          TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
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
                          val isRootTarget = address == RootAgentName || address.endsWith(s"/$RootAgentName")
                          if isRootTarget then
                            // 追加条款：**必须**落到真正的 Nebula root 会话。
                            // 旧实现取 `getParentActor(senderSessionId)`（last-writer-wins 注册）
                            // 并在缺失时取「第一条 Root 记录」——两条都可能落到发信者自身或
                            // 其它 Root 会话，且解析失败仍返回成功文案（静默）。现改为单点解析
                            // `resolveNebulaRootRef`（session meta agentName == "Nebula" ∧ 排除
                            // 发信者自身 ∧ 唯一），解析不到即**显式报错**，不投递。
                            ctx.sharedResources match
                              case Some(res) =>
                                resolveRootRef(res, senderSessionId, ctx.rootSessionId).flatMap {
                                  case Some((sid, ref)) =>
                                    for
                                      res <- sendMail(ref, RootAgentName, message, blocks, eventType, ctx, system)
                                      _ <- res match
                                        case Right(_) =>
                                          onMailDelivered(senderSessionId, sid, RootAgentName, message, ctx)
                                        case Left(_) => IO.unit
                                    yield res
                                  case None => IO.pure(Left(rootUnresolvedError(senderSessionId)))
                                }
                              case None =>
                                IO.pure(Left(ToolError("Cannot resolve the Nebula root session: missing resources.")))
                          else mailNotFound(address)
                          end if
                    yield sr2
              yield sr
          }
    yield result

  /** Marker a team's rules.md can set to opt in to cross-team explicit Mail. */
  private val CrossTeamMailMarker = "allow-cross-team-mail: true"

  /**
   * Explicit "team/agent" addresses targeting another team are blocked by
   * default for non-lead senders (decision 20) — the escalation path is
   * Manager → Nebula. A team opts in by writing
   * `<!-- allow-cross-team-mail: true -->` in its rules.md. Leads (any team
   * Manager / lead — same judgment as canMailRoot) always pass.
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
      canRoot <- canMailRoot(ctx, senderName, senderSessionId)
      crossTeam <- checkCrossTeamExplicitRoute(address, teamName, canRoot)
    yield teamOpt match
      case Some(_) if address == teamName =>
        None
      case Some(_) =>
        Some(s"Cannot mail outside your team. You are in team '$teamName'. Use your Manager to escalate to Nebula.")
      case None if address == RootAgentName && canRoot =>
        None
      case None if address == RootAgentName =>
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

  /**
   * getRunningActor + liveness probe (#22): a cached ref whose actor loop
   * has exited (crash / TTL / stop whose deathwatch cleanup missed) still
   * accepts offers into an unconsumed queue — a queue-mode Mail delivered to
   * it persists, sends MailQueued into the void, and NEVER activates the
   * agent: no error, no retry, silent loss (issue #22's mechanism). Probe
   * the actor system registry; dead ref → reactivate.
   */
  private def liveActorOrActivate(
    sessionId: String,
    resources: AgentRuntimePort,
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
                case true => IO.pure(Some(rec.ref))
                case false => activateAgent(sessionId, resources, system, ctx)
              }
            case None => activateAgent(sessionId, resources, system, ctx)
        }
    }

  /**
   * Activate a team agent session by spawning an AgentActor (replaces FlowAgentActivator).
   * Package-visible for the lifecycle spec (respawn history + death watch).
   */
  private[tools] def activateAgent(
    sessionId: String,
    resources: AgentRuntimePort,
    actorSystem: ActorSystem,
    ctx: ToolContext
  ): IO[Option[ActorRef[AgentCommand]]] =
    for
      sessionOpt <- resources.sessionStore.getSessionMeta(sessionId)
      _ <- IO {
        if sessionOpt.isEmpty then
          logger.warn(
            s"[mail] activation failed: session ${sessionId.take(8)} not found in session store (issue #22 observability)"
          )
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
          // permshield：有效档位 = 应用级全局持久值，走**唯一入口**
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
              // AgentActor 构造改经工厂
              // 镜像(resources 以 this 代入,实参逐字,零行为差)。
              ref <- actorSystem.spawn(
                resources.agentActorBehavior(
                  agentDef = agentDef,
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
   * Death watch for Mail-activated team agents (deep follow-up): MailTool spawns these actors outside FlowTreeActor's
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
    resources: AgentRuntimePort
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
                  logger
                    .info(
                      s"team agent actor stopped: agent=$agentName session=$sessionId — registry unregistered, busy cleared"
                    )
                    .as(Behaviors.stopped[SystemSignal])
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
    val senderName = ctx.agentDef.map(_.name).getOrElse(RootAgentIdentity.Name)
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
        // 气泡四段式统一 · R-A 补：四段式 `PROJECT` 段链首级 =
        // **发送方所属项目**。本腿（分发器 → root）的收件会话是 root 会话（无项目
        // 上下文）⇒ 不置位时第二段恒落根域 `NEBULA`，与「发送方所属项目」判据相反。
        // 置本会话项目域即与 `mailAttribution` 同源（构造点 = 发送方侧，唯一取值处）。
        project = ctx.projectName,
        // ② 服务端注入（agent→agent 邮件），不是真人输入 —— 显式表态。
        fromUser = false
      )
      _ <- nebflow.core.UsageTracker.record("mail", senderSid)
    yield Right(s"Message sent to $label. The agent will process it.")
    end for

  end sendMail

  private def mailNotFound(address: String): IO[Either[ToolError, String]] =
    val msg =
      if address == RootAgentName then
        s"Cannot deliver to '$RootAgentName': no Nebula root session could be resolved (NEBULA_ROOT_UNRESOLVED). " +
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
      senderName = fromOpt.orElse(ctx.agentDef.map(_.name)).getOrElse(RootAgentIdentity.Name)
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
