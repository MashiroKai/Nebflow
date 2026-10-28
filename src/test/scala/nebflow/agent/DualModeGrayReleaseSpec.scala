package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.delegate.{DelegateModeAllocator, DelegateRegistry, DualModeConfig, ModeDecision}
import nebflow.core.tools.{SubagentTool, ToolContext}
import nebflow.shared.PathUtil

/**
 * 双模式批（2026-10-28 · 第 6 步）**灰度断言专组**：
 *
 *  - GR1 **同一盘面记录 × 两闸态读数**（灰度核心不变量）：闸关 = Inert 单点
 *    （盘上有 mode 也 None）；同一记录闸开 = 透传——翻闸即分叉，零数据迁移。
 *  - GR2 **缺省安全方向**：出厂态（无 config 无 sys prop）= 闸关 + fail-open
 *    kernel（便宜侧）——「发布即现状」的元断言。
 *  - GR3 **决策→读面→工具面全链**（闸 on §7 形态一）：分配器判 dispatcher ⇒
 *    记录落盘 ⇒ modeFaceOf 读回 ⇒ 挂载面分叉（Subagent 摘除）⇒ 工具内第二保险
 *    拒——一条链钉死 dispatcher 形态。
 *  - GR4 全链形态二：fail-open kernel 判定（分配器抛异常）⇒ 记录 kernel ⇒
 *    读面 kernel ⇒ 编排面退场（Subagent/AgentFlow/node 四件全摘）。
 *  - GR5 **闸关逐字节终检**：同一 GR3 链在闸关下全段失效——记录读 None、
 *    工具调用走参数校验腿（绝无双模式文案）、分配器从未被问。
 */
class DualModeGrayReleaseSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  private object CoreProbe extends AgentCore:

    def allowedWithDelegateMode(defn: AgentDef, delegateSession: Boolean, delegateMode: Option[String]): Set[String] =
      buildAllowedToolSet(defn, 0, delegateSession = delegateSession, delegateMode = delegateMode)

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "dual-mode-gray")
    PathUtil.setDataRoot(tmp / "data")
    System.clearProperty(DualModeConfig.SysPropEnabled)
    DelegateModeAllocator.clear()

  override def afterEach(context: munit.AfterEach): Unit =
    System.clearProperty(DualModeConfig.SysPropEnabled)
    DelegateModeAllocator.clear()
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private val probeDef = AgentDef(name = "nebflow", description = "", tools = Nil, systemPrompt = "")

  private def putRecord(id: String, mode: Option[String]): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tmp.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-gray",
        title = s"gray $id",
        createdAt = System.currentTimeMillis() - 60_000L,
        mode = mode
      )
    )

  // ── GR1 同一记录 × 两闸态 ───────────────────────────────────────────────────

  test("GR1 同一盘面记录：闸关读 None（Inert 单点），同一记录闸开透传 dispatcher——翻闸即分叉零迁移") {
    val id = "delegate-nebflow-gr1"
    putRecord(id, Some(DualModeConfig.ModeDispatcher))
    assertEquals(
      DelegateRegistry.modeFaceOf(Some(id)),
      None,
      "gate OFF: the on-disk mode is NEVER read (Inert is a single point — the release-safe direction)"
    )
    System.setProperty(DualModeConfig.SysPropEnabled, "true")
    assertEquals(
      DelegateRegistry.modeFaceOf(Some(id)),
      Some(DualModeConfig.ModeDispatcher),
      "the SAME record flips the whole face on gate-on — zero data migration between gates"
    )
  }

  // ── GR2 缺省安全方向 ────────────────────────────────────────────────────────

  test("GR2 出厂态：无 config 无 sys prop ⇒ 闸关 + fail-open kernel（发布即现状）") {
    assertEquals(DualModeConfig.enabledNow, false, "factory default = gate OFF (byte-identical release)")
    assertEquals(DualModeConfig.failopenMode, DualModeConfig.ModeKernel, "factory fail-open side = kernel (the cheap side)")
  }

  // ── GR3/GR4 决策→读面→工具面全链（闸 on 两形态）────────────────────────────

  private def fullChain(decision: ModeDecision): (Set[String], Either[nebflow.core.tools.ToolError, String]) =
    val id = "delegate-nebflow-gray-chain"
    putRecord(id, Some(decision.mode))
    val face = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = DelegateRegistry.modeFaceOf(Some(id)))
    val subagentCall =
      SubagentTool.call(JsonObject("prompt" -> "recon".asJson), ToolContext(projectRoot = tmp.toString, sessionId = Some(id)))
        .unsafeRunSync()
    (face, subagentCall)

  test("GR3 闸 on 全链形态一（dispatcher）：分配器判 dispatcher ⇒ 读面 ⇒ 面 fork ⇒ Subagent 工具内拒") {
    System.setProperty(DualModeConfig.SysPropEnabled, "true")
    DelegateModeAllocator.install((_, _) => IO.pure(ModeDecision(DualModeConfig.ModeDispatcher, "jev", "gray chain stub")))
    val (face, subagentCall) = fullChain(ModeDecision(DualModeConfig.ModeDispatcher, "jev", "stub"))
    assert(!face.contains("Subagent"), "dispatcher face strips Subagent (mount face)")
    assert(face.contains("ChainWrite") && face.contains("AgentFlow"), "dispatcher keeps the orchestration face")
    assert(
      subagentCall.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")),
      s"the tool-internal second guard rejects the escaped call: $subagentCall"
    )
  }

  test("GR4 闸 on 全链形态二（fail-open kernel）：分配器抛异常 ⇒ kernel ⇒ 编排面整体退场") {
    System.setProperty(DualModeConfig.SysPropEnabled, "true")
    DelegateModeAllocator.install((_, _) => IO.raiseError(new RuntimeException("face exploded")))
    val (face, _) = fullChain(ModeDecision(DualModeConfig.ModeKernel, "fail-open", "degraded"))
    Set("Subagent", "AgentFlow", "NodeEdit", "NodeList", "NodeCancel", "ChainWrite").foreach { t =>
      assert(!face.contains(t), s"kernel face strips the whole orchestration face: $t")
    }
    assert(face.contains("Mail") && face.contains("Read"), "kernel keeps the executor continuation face")
  }

  // ── GR5 闸关逐字节终检 ─────────────────────────────────────────────────────

  test("GR5 闸关终检：决策链全段失效——记录读 None、工具走参数校验腿、分配器从未被问") {
    val id = "delegate-nebflow-gr5"
    var allocatorAsked = 0
    DelegateModeAllocator.install((_, _) => IO { allocatorAsked += 1; ModeDecision(DualModeConfig.ModeDispatcher, "jev", "must not run") })
    putRecord(id, Some(DualModeConfig.ModeDispatcher))
    assertEquals(DelegateRegistry.modeFaceOf(Some(id)), None, "the record read stays None gate-off")
    val face = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = DelegateRegistry.modeFaceOf(Some(id)))
    Set("Subagent", "AgentFlow", "ChainWrite").foreach(t => assert(face.contains(t), s"gate-off face keeps: $t"))
    val call = SubagentTool.call(JsonObject.empty, ToolContext(projectRoot = tmp.toString, sessionId = Some(id))).unsafeRunSync()
    assert(call.left.exists(_.message.contains("Missing required parameter")), s"the gate-off call walks the legacy validation leg: $call")
    assert(!call.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), "gate-off never produces dual-mode copy")
    assertEquals(allocatorAsked, 0, "the allocator is never consulted when the gate is off (decideMode short-circuits)")
  }
end DualModeGrayReleaseSpec
