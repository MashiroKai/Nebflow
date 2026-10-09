package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.agent.*
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord} // W1 shim: main exported these from nebflow.agent (protocol/AgentState); the merge moved them to actor
import nebflow.core.FileChangeTracker
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.{RateLimiter, SessionStore} // W1 shim: main had nebflow.gateway.{RateLimiter, SessionStore}; PR re-homed both to core
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.ThinkingConfig // W1 shim: main had nebflow.llm.ThinkingConfig; PR moved it to shared
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * mailmodel batch (2026-09-25, rulings (b)/(d)/(e)) - the retirement + kernel-leg face
 * of the Mail tool.
 *
 * Pinned here (each mechanically decidable):
 *  1. **(e) device key retired**: `device` is gone from the schema; a stale `device=`
 *     call refuses with `MAIL_DEVICE_RETIRED` BEFORE everything else (even with an empty
 *     message / missing address - the tombstone outranks the required-parameter gates);
 *  2. **(d) type key retired (fail-open by ruling)**: `type` is gone from the schema;
 *     a stale `type=` value is INDISTINGUISHABLE from the key being absent (no tombstone:
 *     both engine branches it used to select are re-homed - the P0 window exemption is
 *     now the body's first-line `[INTERRUPT]` literal, the device-leg branch died with
 *     the leg - so a stale value carries zero semantic the engine could silently drop);
 *  3. **(d) the P0 exemption is the literal**: `isDispatcherMailInterrupt` positive/negative
 *     polarity (first non-empty line, exact case) - the A3/R7 bypass pair survives as a
 *     mechanism, not a message type;
 *  4. **(b) kernel continuation leg is Nebula-exclusive** (ruling (b) carries over):
 *     a non-root sender mailing `kernel:<id>` gets `MAIL_KERNEL_EXCLUSIVE` and nothing
 *     is sent (precedes every
 *     routing/resource gate); the Nebula-root role passes the exclusive gate (the error
 *     degrades to the fixture-level missing-resources one, never the exclusive one);
 *     a `kernel:<id>` continuation miss on the real registry is `MAIL_KERNEL_NOT_LIVE`;
 *     the bare-kernel SPAWN leg is RETIRED (builtin-def batch 2026-10-03, author
 *     directive ② "Delegate triggers kernels; Mail communicates"): any role mailing a
 *     bare `kernel` gets the explicit `MAIL_KERNEL_SPAWN_RETIRED` tombstone;
 *  5. **model-visible text**: the base description carries the kernel continuation-leg section, the
 *     device retirement note and the `[INTERRUPT]` window-bypass line; the
 *     `AddressFaceRoot` role projection carries the kernel forms (the dispatcher /
 *     team faces must NOT - the kernel leg is Nebula-exclusive, so projecting it into
 *     other roles would advertise an address that only errors for them).
 *
 * Zero network, zero real kernel spawn (triggering is DelegateTool's
 * spawn engine and is exercised there; this file pins the Mail-tool face of ruling (b)).
 */
class MailModelRetiredSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ── fixtures ──

  private def qIn(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  private def ctx(
      dispatcher: Boolean = false,
      nebulaRoot: Boolean = false
  ): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mailmodel"),
      isDispatcher = dispatcher,
      projectName = None,
      agentDef = if nebulaRoot then Some(AgentDef(name = "Nebula", description = "spec fixture")) else None
    )

  private def callRes(input: JsonObject, c: ToolContext): Either[ToolError, String] =
    MailTool.call(input, c).unsafeRunSync()

  /** Assert the call failed and return the error text (red-side evidence = the got text). */
  private def errOf(res: Either[ToolError, String], label: String): String =
    res match
      case Left(e)  => e.message
      case Right(v) => fail(s"$label: must be rejected, got: $v")

  private def prop(schema: JsonObject, name: String): JsonObject =
    schema("properties").flatMap(_.asObject).flatMap(_(name)).flatMap(_.asObject)
      .getOrElse(fail(s"parameter '$name' missing from the Mail schema"))

  private def propDesc(schema: JsonObject, name: String): String =
    prop(schema, name)("description").flatMap(_.asString).getOrElse(fail(s"$name has no description"))

  // ── resources fixture (kernel continuation leg reads the real agent registry) ──

  private class RecordingLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  // ============================================================
  // 1. (e) device key retired - tombstone first
  // ============================================================

  test("① schema: the property set is exactly the 6 surviving keys; `device` and `type` are gone; required stays [message]"):
    val props = MailTool.inputSchema("properties").flatMap(_.asObject).getOrElse(fail("no properties"))
    assertEquals(
      props.keys.toSet,
      Set("address", "message", "chainId", "task", "images", "attachments"),
      s"the parameter face must be the 6 surviving keys, got: ${props.keys.toList.sorted}"
    )
    assertEquals(
      MailTool.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil),
      List("message"),
      "required must stay exactly [message]"
    )
    // the address face must declare the delegate continuation form (model-visible).
    // P1-1 对齐（2026-10-03，任务书 P1-1）：unified-delegate 批把 `kernel` 起实例腿
    // 退役——address 面现宣传 **delegate** 续聊语义（`delegate:<id>`，裸 `kernel`
    // 是墓碑），旧断言钉的 `kernel`/`kernel:<id>` 两形态已与 `MailTool.scala` 的
    // 墓碑自相矛盾。本批按裁定把断言对齐 delegate 语义（保留「地址面必须声明续聊
    // 形态」的强度，不是删断言）。
    val addr = propDesc(MailTool.inputSchema, "address")
    assert(addr.contains("delegate:<id>"), s"the address description must name the delegate continuation form: $addr")
    assert(
      !addr.contains("\"kernel\"") && !addr.contains("\"kernel:<id>\""),
      s"the address description must NOT advertise the retired kernel forms (delegate is the trigger): $addr"
    )

  test("① device tombstone: a stale `device=` call refuses with MAIL_DEVICE_UNSUPPORTED before everything (even before the required-parameter gates)"):
    // with NO message and NO address: the tombstone still wins (it is read first)
    val bare = errOf(callRes(qIn("device" -> "KAI"), ctx()), "device + bare")
    assert(bare.contains(MailTool.ErrDeviceLegRetired), s"the tombstone must fire on a bare stale call, got: $bare")
    // with a full valid-shaped call: still the tombstone
    val full = errOf(callRes(qIn("device" -> "KAI", "address" -> "project:any", "message" -> "hi"), ctx()), "device + full")
    assert(full.contains(MailTool.ErrDeviceLegRetired), s"the tombstone must fire on a shaped stale call, got: $full")
    // pure-constructor pin (the spec-visible seam the error text is built from)
    val pure = MailTool.deviceLegRetiredError("KAI")
    assert(pure.message.contains(MailTool.ErrDeviceLegRetired))
    assert(pure.message.contains("KAI"), s"the error must echo the offending value: ${pure.message}")
    assert(pure.message.contains("SendMessage"), s"the error must give the way out (files go via SendMessage): ${pure.message}")

  // ============================================================
  // 2. (d) type key retired - fail-open BY RULING (stale value == absent)
  // ============================================================

  test("② type: a stale `type=` value is indistinguishable from the key being absent (no tombstone - both engine branches are re-homed)"):
    val c = ctx(dispatcher = true)
    val withType = callRes(qIn("address" -> "node:n-9", "message" -> "hi", "type" -> "FOLLOW_UP"), c)
    val withoutType = callRes(qIn("address" -> "node:n-9", "message" -> "hi"), c)
    assertEquals(withType, withoutType,
      "a stale type= value must be silently ignored (zero semantic left for it to carry)")

  test("② the P0 window exemption is the [INTERRUPT] literal (mechanism, not a type) - polarity table"):
    assert(MailTool.isDispatcherMailInterrupt("[INTERRUPT]\nbody"), "exact first line must match")
    assert(MailTool.isDispatcherMailInterrupt("\n  [INTERRUPT]  \nbody"), "blank leading lines are skipped, trim applies")
    assert(!MailTool.isDispatcherMailInterrupt("plain reply\n[INTERRUPT]"), "a later line must NOT bypass")
    assert(!MailTool.isDispatcherMailInterrupt("[interrupt]\nbody"), "lowercase must NOT bypass")
    assert(!MailTool.isDispatcherMailInterrupt("[INTERRUPT] now"), "adjacent text breaks the literal")
    assert(!MailTool.isDispatcherMailInterrupt(""), "empty body never bypasses")

  // ============================================================
  // 3. (b) kernel leg - Nebula-exclusive, not-live continuation, text-only start
  // ============================================================

  test("③ bare-kernel spawn leg: ANY role mailing a bare `kernel` gets MAIL_KERNEL_SPAWN_UNSUPPORTED (Delegate is the trigger)"):
    val system = ActorSystem(s"mailmodel-spawnret-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      for (label, c) <- List(
        ("teamish ctx", ctx().copy(actorSystem = Some(system))),
        ("dispatcher ctx", ctx(dispatcher = true).copy(actorSystem = Some(system))),
        ("root ctx", ctx(nebulaRoot = true).copy(actorSystem = Some(system)))
      ) do
        val msg = errOf(callRes(qIn("address" -> "kernel", "message" -> "hi"), c), label)
        assert(msg.contains(MailTool.ErrKernelSpawnRetired), s"$label: must carry the spawn-retired tombstone, got: $msg")
        assert(msg.contains("Delegate"), s"$label: the tombstone must point at Delegate, got: $msg")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  test("③ kernel exclusivity: a non-root sender mailing `kernel:<id>` gets MAIL_KERNEL_EXCLUSIVE (precedes every routing/resource gate)"):
    val system = ActorSystem(s"mailmodel-excl-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      for (label, addr, c) <- List(
        ("teamish ctx, continuation", "kernel:delegate-kernel-abc12345", ctx().copy(actorSystem = Some(system))),
        ("dispatcher ctx, continuation", "kernel:delegate-kernel-abc12345", ctx(dispatcher = true).copy(actorSystem = Some(system)))
      ) do
        val msg = errOf(callRes(qIn("address" -> addr, "message" -> "hi"), c), label)
        assert(msg.contains(MailTool.ErrKernelExclusive), s"$label: must carry the exclusive code, got: $msg")
        assert(!msg.contains("missing resources") && !msg.contains("No actor system"),
          s"$label: the exclusive gate must fire BEFORE the resource gates, got: $msg")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()

  test("③ kernel exclusivity: the Nebula-root role passes the exclusive gate on `kernel:<id>` (the error degrades to the fixture-level missing-resources face, never the exclusive one)"):
    val msg = errOf(callRes(qIn("address" -> "kernel:delegate-kernel-abc12345", "message" -> "hi"), ctx(nebulaRoot = true)), "root + kernel:<id>")
    assert(!msg.contains(MailTool.ErrKernelExclusive), s"the root must NOT be refused by the exclusive gate, got: $msg")
    assert(!msg.contains(MailTool.ErrKernelSpawnRetired), s"the continuation address must not hit the spawn tombstone, got: $msg")

  test("③ delegate continuation miss: `kernel:<id>`/`delegate:<id>` against an empty registry is DELEGATE_NOT_FOUND (fail-closed, unified-delegate batch)"):
    val tmp = os.temp.dir(prefix = "mailmodel-notlive")
    val system = ActorSystem(s"mailmodel-notlive-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val resources = mkResources(system, tmp, new RecordingLlm).unsafeRunSync()
      val c = ctx(nebulaRoot = true).copy(sharedResources = Some(resources), actorSystem = Some(system))
      // 旧形态 `kernel:<id>` 是 delegate: 的同 id 空间别名（存量回执不打断）——
      // 未注册 id 的失败面同一张：DELEGATE_NOT_FOUND（回显 id，指路 Delegate 回执）。
      val msg = errOf(callRes(qIn("address" -> "kernel:delegate-kernel-deadbeef", "message" -> "hi"), c), "not-live")
      assert(msg.contains("DELEGATE_NOT_FOUND"), s"a registry miss must be the not-found error, got: $msg")
      assert(msg.contains("delegate-kernel-deadbeef"), s"the error must echo the id, got: $msg")
      // 新形态同面：delegate:<id> 未注册同样是 DELEGATE_NOT_FOUND。
      val msg2 = errOf(callRes(qIn("address" -> "delegate:delegate-kernel-deadbeef", "message" -> "hi"), c), "not-live-new-form")
      assert(msg2.contains("DELEGATE_NOT_FOUND"), s"the new form shares the miss face, got: $msg2")
      // pure-constructor pin
      val pure = MailTool.kernelNotLiveError("delegate-kernel-x")
      assert(pure.message.contains(MailTool.ErrKernelNotLive))
      assert(pure.message.contains("Delegate"), s"the error must name the way out (start a new one with Delegate): ${pure.message}")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      os.remove.all(tmp)

  test("③ kernel spawn leg: a bare `kernel` tombstones even with images attached (MAIL_KERNEL_SPAWN_UNSUPPORTED), nothing is spawned"):
    val tmp = os.temp.dir(prefix = "mailmodel-kimg")
    val system = ActorSystem(s"mailmodel-kimg-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val img = tmp / "img-1.png"
      os.write(img, Array[Byte](0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte, 1, 2, 3))
      val resources = mkResources(system, tmp, new RecordingLlm).unsafeRunSync()
      val c = ctx(nebulaRoot = true).copy(sharedResources = Some(resources), actorSystem = Some(system))
      val input = qIn("address" -> "kernel", "message" -> "hi")
        .add("images", Json.arr(img.toString.asJson))
      val msg = errOf(callRes(input, c), "kernel + images")
      assert(msg.contains(MailTool.ErrKernelSpawnRetired), s"the retired spawn leg must tombstone (no resource gates first), got: $msg")
      // pure-constructor pin
      val pure = MailTool.kernelSpawnRetiredError("kernel")
      assert(pure.message.contains(MailTool.ErrKernelSpawnRetired))
      assert(pure.message.contains("Delegate"), s"the tombstone must point at Delegate: ${pure.message}")
    finally
      system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
      os.remove.all(tmp)

  test("③ kernel error texts are self-contained (the receipt faces the model actually reads)"):
    val excl = MailTool.kernelExclusiveError("kernel", "Teamish")
    assert(excl.message.contains(MailTool.ErrKernelExclusive))
    assert(excl.message.contains("Nebula"), s"the exclusive error must name who may mail a kernel: ${excl.message}")
    assert(excl.message.contains("Nothing was sent"), s"fail-closed declaration required: ${excl.message}")
    assert(excl.message.contains("Route the work through Nebula"), s"the error must give the way out: ${excl.message}")

  // ============================================================
  // 4. model-visible text: description + role projection
  // ============================================================

  test("④ base description: kernel leg, device retirement note and the [INTERRUPT] window line are all model-visible"):
    val d = MailTool.descriptionBase
    assert(d.contains("## Kernel continuation leg"), s"the kernel continuation-leg section must exist: ${d.take(200)}")
    assert(d.contains(MailTool.ErrKernelSpawnRetired), "the spawn-retired tombstone code must be named")
    assert(!d.contains("`address=\"kernel\"` starts"), "the retired start-form wording must be gone")
    assert(d.contains("kernel:<id>"), "the continuation form must be documented")
    assert(d.contains(MailTool.ErrKernelExclusive), "the exclusive error code must be named")
    assert(d.contains(MailTool.ErrKernelNotLive), "the not-live error code must be named")
    assert(d.contains(MailTool.ErrDeviceLegRetired), "the device retirement code must be named")
    assert(d.contains("[INTERRUPT]"), "the window-bypass literal must be documented")

  test("④ role projection: the kernel ADDRESS forms live ONLY in the Nebula-root face (other roles keep only the shared vision/attachment mentions of the word)"):
    val root = MailTool.descriptionRoot
    val disp = MailTool.descriptionDispatcher
    val team = MailTool.descriptionBase
    assert(root.contains("## Kernel continuation leg"), s"the root face must carry the kernel continuation-leg section")
    assert(root.contains("Delegate-only"), s"the root face must document the Delegate-only trigger face")
    assert(root.contains("`kernel:<id>`"), "the root face must document the continuation form")
    // the dispatcher/base faces must NOT advertise the kernel ADDRESS forms (the word
    // "kernel" itself may still appear in the shared images/attachments sections)
    assert(!disp.contains("## Kernel continuation leg"), "the dispatcher face must NOT carry the kernel continuation-leg section")
    assert(!disp.contains("`kernel:<id>` — continue"), "the dispatcher face must NOT carry the root address line")
    // the BASE face is the Q5 union face (fail-closed default for unknown identity): it
    // may carry the section, but ONLY together with the exclusivity statement - a
    // non-root reader of the base is still told the leg is not theirs, and the runtime
    // gate refuses them anyway (pinned above).
    if team.contains("## Kernel continuation leg") then
      assert(team.contains("Nebula-exclusive"), "the base face must carry the exclusivity statement beside the section")
    // the projection contract stays intact: every role face is derived from the base by
    // pure section deletion (ToolFaceVariantSpec pins the byte-level form)
    assert(MailTool.descriptionRoot.length <= MailTool.descriptionBase.length,
      "the projection must be deletion-only")

end MailModelRetiredSpec
