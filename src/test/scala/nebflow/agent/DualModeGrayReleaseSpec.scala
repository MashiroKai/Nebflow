package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.delegate.{DelegateRegistry, DualModeConfig, ModeDecision, ModeLeg, ModeParam}
import nebflow.core.tools.{SubagentTool, ToolContext}
import nebflow.shared.PathUtil

/**
 * 执行形态（kernel | dispatcher）**参数决定论**专组（delegate-param-decisions 批
 * 2026-11-04 · 作者令「kernel和dispatcher 由于你有任务的全部上下文 你也可以做到
 * 直接选择」——原 DualModeGrayReleaseSpec 的「灰度闸/分配器」语义已随分配器一并
 * 删除，本文件保留「决策 → 读面 → 工具面」全链断言，改钉新契约）：
 *
 *  - GR1 记录里的 mode 是**权威读面**：不再有闸门把盘上的 mode 藏起来。
 *  - GR2 出厂缺省 = 确定性 kernel（不填 `mode` ⇒ kernel，零模型调用）。
 *  - GR3 `dispatcher` 全链：记录落盘 ⇒ modeFaceOf 读回 ⇒ 挂载面分叉（Subagent
 *    摘除）⇒ 工具内第二保险拒。
 *  - GR4 `kernel` 全链：编排面整体退场。
 *  - GR5 **分配器不存在**：`auto` 在参数解析点显式报错，任何调用都不可能被分配器
 *    截获（旧的「分配器从未被问」改钉「分配器已不存在」）。
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

  override def afterEach(context: munit.AfterEach): Unit =
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

  // ── GR1 记录 = 权威读面 ─────────────────────────────────────────────────────

  test("GR1 盘上 mode 直读：没有闸门把 kernel/dispatcher 藏起来（显式选择永不被静默吞掉）") {
    val id = "delegate-nebflow-gr1"
    putRecord(id, Some(DualModeConfig.ModeDispatcher))
    assertEquals(
      DelegateRegistry.modeFaceOf(Some(id)),
      Some(DualModeConfig.ModeDispatcher),
      "the record is the authoritative face — no gate, no allocator, no migration step"
    )
    putRecord(id, Some(DualModeConfig.ModeKernel))
    assertEquals(DelegateRegistry.modeFaceOf(Some(id)), Some(DualModeConfig.ModeKernel))
    putRecord("delegate-nebflow-gr1-legacy", None)
    assertEquals(
      DelegateRegistry.modeFaceOf(Some("delegate-nebflow-gr1-legacy")),
      None,
      "legacy records stay None (zero migration)"
    )
  }

  // ── GR2 缺省安全方向 ────────────────────────────────────────────────────────

  test("GR2 不填 mode ⇒ 确定性 kernel（leg=default），auto 被显式拒绝") {
    val d = ModeParam.resolve(None).getOrElse(fail("an omitted mode must resolve, never fail"))
    assertEquals(d.mode, DualModeConfig.ModeKernel, "omitting the parameter deterministically means kernel")
    assertEquals(d.leg, ModeLeg.Default)
    assert(ModeParam.resolve(Some("auto")).left.exists(_.contains("DELEGATE_MODE_AUTO_REMOVED")))
  }

  // ── GR3/GR4 记录 → 读面 → 工具面全链 ───────────────────────────────────────

  private def fullChain(decision: ModeDecision): (Set[String], Either[nebflow.core.tools.ToolError, String]) =
    val id = "delegate-nebflow-gray-chain"
    putRecord(id, Some(decision.mode))
    val face = CoreProbe.allowedWithDelegateMode(
      probeDef,
      delegateSession = true,
      delegateMode = DelegateRegistry.modeFaceOf(Some(id))
    )
    val subagentCall =
      SubagentTool
        .call(JsonObject("prompt" -> "recon".asJson), ToolContext(projectRoot = tmp.toString, sessionId = Some(id)))
        .unsafeRunSync()
    (face, subagentCall)

  test("GR3 全链形态一（dispatcher）：参数给 dispatcher ⇒ 读面 ⇒ 面 fork ⇒ Subagent 工具内拒") {
    val (face, subagentCall) =
      fullChain(ModeDecision(DualModeConfig.ModeDispatcher, ModeLeg.Param, "explicit mode parameter"))
    assert(!face.contains("Subagent"), "dispatcher face strips Subagent (mount face)")
    assert(face.contains("ChainWrite") && face.contains("AgentFlow"), "dispatcher keeps the orchestration face")
    assert(
      subagentCall.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")),
      s"the tool-internal second guard rejects the escaped call: $subagentCall"
    )
  }

  test("GR4 全链形态二（kernel）：不填 mode ⇒ kernel ⇒ 编排面整体退场") {
    val decision = ModeParam.resolve(None).getOrElse(fail("default must resolve"))
    val (face, _) = fullChain(decision)
    Set("Subagent", "AgentFlow", "NodeEdit", "NodeList", "NodeCancel", "ChainWrite").foreach { t =>
      assert(!face.contains(t), s"kernel face strips the whole orchestration face: $t")
    }
    assert(face.contains("Mail") && face.contains("Read"), "kernel keeps the executor continuation face")
  }

  // ── GR5 分配器不存在 ───────────────────────────────────────────────────────

  test("GR5 分配器已删除：auto 在参数解析点就报错（引导语在文案里），不存在任何模型判定兜底") {
    val err = ModeParam.resolve(Some("auto")).swap.getOrElse(fail("auto must be rejected"))
    assert(err.contains("allocator has been removed"), err)
    assert(err.contains("kernel") && err.contains("dispatcher"), err)
    assert(err.contains("请直接指定 kernel 或 dispatcher，分配器已移除"), err)
    // 值域外的词同样显式拒（零静默映射）。
    assert(ModeParam.resolve(Some("jeV")).isLeft, "no fuzzy/alias mapping — only the two words are accepted")
  }
end DualModeGrayReleaseSpec
