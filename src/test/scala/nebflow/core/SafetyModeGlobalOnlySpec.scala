package nebflow.core

import cats.effect.IO
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.shared.{PathUtil, SessionMeta}

import java.nio.file.Files

/**
 * **承重断言**：档位只有一条路径 —— 应用级全局持久值
 * （`nebflow.json` 的 `safety.defaultMode`）。`SessionMeta.safetyMode`
 * （`sessions/_index.json` 的逐会话键）是**非权威遗留字段**，取任何值都不改变
 * 有效档位。
 *
 * 沿革（为什么本文件由 `SafetyModeAuthoritySpec` 改名而来）——
 * 2026-09-13 permshield S1（作者重裁「候选 B」：盾牌 = 写全局；落全局持久，
 * 重启后仍生效）停用并删除了 2026-09-12 的「会话内临时覆盖」层
 * （`SharedResources.permissionPolicies` 覆盖桶 + 唯一合并点
 * `SafetyModeAuthority.resolve`）。**作废面逐条处置**（禁留旧语义断言）：
 *
 *  | 旧断言语义 | 处置 | 现形态 |
 *  |---|---|---|
 *  | ② 覆盖优先（覆盖存在时不看全局） | **改判据 → 负控** | 「没有会话面入参」= 旧形态在类型上不可复现（见 `no per-session dimension exists`） |
 *  | ② 覆盖按 rootSessionId 分叉 / 不泄漏 | **删判据** | 覆盖面不存在 ⇒ 分叉语义无对象（删除，非静默：此行即登记） |
 *  | ④ `overlaySessionList` 尊重覆盖 | **改判据** | 出口 = 全局值，全局热切后**所有**会话同步（见 `overlaySessionList` 用例） |
 *  | `SafetyModeAuthority.resolve` 单测 | **删判据** | 被测对象已删除（零悬空引用；`grep -rn SafetyModeAuthority src/` = 0 命中） |
 *  | ① meta 取任何值不改变有效档位 | **保留** | 原样（A-9 承重） |
 *  | ③ 出口键恒存在 / 其他字段保留 | **保留** | 原样 |
 *  | `SafetyMode.fromWire` 严格白名单 | **保留** | 原样（写入口校验） |
 *
 * 新增判据（本批的**负控**与**静默放行面**，见 §④/§⑤）：
 *  - 出口派生的 `bypassSessions`（前端 `state.bypassSessions` 的语义源）在全局 ≠
 *    auto-all 时**必为空**，即使盘上逐会话键写着 auto-all ⇒ 旧"静默放行"触发形态
 *    不成立；
 *  - 全局 = auto-all 时后端**不出卡**（`ToolReversibility` 对顶档恒 true）⇒ 前端
 *    那两条静默应答路径也没有对象（无卡可答）。
 */
class SafetyModeGlobalOnlySpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-safety-global-only"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def writeGlobal(mode: String): Unit =
    os.write.over(tmp / "nebflow.json", s"""{"safety": {"defaultMode": "$mode"}}""")

  private def meta(id: String, mode: String): SessionMeta =
    SessionMeta(id = id, name = id, createdAt = 1L, updatedAt = 1L, hasUnread = false, safetyMode = mode)

  /** 出口 JSON 里逐会话的 safetyMode（顺序与入参一致）。 */
  private def modes(json: io.circe.Json): List[String] =
    json.asArray.getOrElse(Vector.empty).toList.map { s =>
      s.hcursor.downField("safetyMode").as[String].getOrElse("<absent>")
    }

  // ── ① 承重：meta 取任何值都不改变有效档位（A-9）────────────────────────────

  test("A-9: a session's meta safetyMode never changes the effective level (global is the only source)"):
    writeGlobal("confirm-edits")
    val sessions = List(
      meta("s-auto-all", "auto-all"),
      meta("s-auto-edits", "auto-edits"),
      meta("s-confirm", "confirm-edits"),
      meta("s-garbage", "yolo")
    )
    val json = SessionMeta.withEffectiveSafetyModes(sessions, "confirm-edits")
    assertEquals(modes(json), List("confirm-edits", "confirm-edits", "confirm-edits", "confirm-edits"))

  test("A-9: meta=auto-all + global=auto-edits still reads auto-edits (no meta leakage)"):
    writeGlobal("auto-edits")
    val json = SessionMeta.withEffectiveSafetyModes(List(meta("s1", "auto-all")), "auto-edits")
    assertEquals(modes(json), List("auto-edits"))

  // ── ② 负控（改判据）：会话覆盖面已不存在，旧「覆盖优先」形态不可复现 ─────────

  test("负控: no per-session dimension exists — the exit helper has no override input at all"):
    // 旧「覆盖 ?? 全局」需要两个入参（覆盖集合 + 会话 id）才能表达"某会话有独立档位"；
    // 本批把覆盖桶与 `SafetyModeAuthority.resolve` 一并删除后，**签名上就不存在**
    // 第二个可变来源 ⇒ 同一全局值下任何会话、任何次读数必须逐字相同。
    // （旧断言「覆盖存在时不看全局」因此没有可写的对偶形态 —— 这正是"旧行为复现为
    // 不成立"的负控形态：不是断言它"现在返回别的值"，而是断言它**无法被表达**。）
    writeGlobal("confirm-edits")
    val sessions = List(meta("root-1", "auto-all"), meta("root-2", "auto-all"), meta("root-3", "confirm-edits"))
    val json = SessionMeta.withEffectiveSafetyModes(sessions, "confirm-edits")
    assertEquals(modes(json).distinct, List("confirm-edits"))
    // 盘上遗留的 auto-all 不再是任何权威读取点的来源
    assertEquals(sessions.head.safetyMode, "auto-all")

  // ── ③ 出口键恒存在（不依赖 Encoder 省略语义）───────────────────────────────

  test("the list exit always carries an explicit safetyMode key, even for confirm-edits"):
    val json = SessionMeta.withEffectiveSafetyModes(List(meta("s1", "confirm-edits")), "confirm-edits")
    // 键必须显式出现 —— 旧契约下 `confirm-edits` 会被 Encoder 省略（前端靠
    // `|| 'confirm-edits'` 兜底），新契约要求三档值在 wire 上恒存在。
    assert(
      json.asArray.get.head.hcursor.downField("safetyMode").succeeded,
      "safetyMode key must be present in the session-list exit"
    )
    assertEquals(modes(json), List("confirm-edits"))

  test("the list exit preserves the other session fields (deepMerge, not replace)"):
    writeGlobal("auto-all")
    val json = SessionMeta.withEffectiveSafetyModes(List(meta("s1", "auto-all")), "auto-all")
    val head = json.asArray.get.head
    assertEquals(head.hcursor.downField("id").as[String].toOption, Some("s1"))
    assertEquals(head.hcursor.downField("name").as[String].toOption, Some("s1"))
    assertEquals(head.hcursor.downField("hasUnread").as[Boolean].toOption, Some(false))

  // ── ④ 线上出口那条链（SharedResources.overlaySessionList）—— 全局单一来源 ───

  test("SharedResources.overlaySessionList emits the global value, hot-followed by every session"):
    writeGlobal("confirm-edits")
    val res = nebflow.agent.SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = null,
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = null,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = null
    )
    for
      // 盘上两个会话都写着 auto-all（存量形态），全局却是 confirm-edits ⇒ 出口必须报全局值
      json <- res.overlaySessionList(List(meta("s1", "auto-all"), meta("s2", "auto-all")))
      // 全局热切后，下一个出口即反映新值（**所有**会话一起变，不再只变某个会话）
      _ = writeGlobal("auto-edits")
      json2 <- res.overlaySessionList(List(meta("s1", "auto-all"), meta("s2", "auto-all")))
    yield
      assertEquals(modes(json), List("confirm-edits", "confirm-edits"))
      assertEquals(modes(json2), List("auto-edits", "auto-edits"))

  // ── ⑤ bypassSessions 静默放行面（后端侧负控 + 正控）────────────────────────

  test("负控: the exit-derived bypass set (frontend state.bypassSessions source) is empty unless global=auto-all"):
    // 前端 `state.bypassSessions` 的语义源 = 会话列表帧的逐会话 `safetyMode`：
    // 只有取值 == 'auto-all' 的会话会被收进该集合，随后 chat.js 据此**静默**
    // 回 `permissionAnswer{approved:true}`。停用覆盖层后，出口值恒 = 全局值 ⇒
    // 全局 ≠ auto-all 时该集合必为空，**即便盘上逐会话键写着 auto-all**
    // （= 旧"静默放行"的唯一触发形态已被结构性消除）。
    val staleDisk = List(meta("root-1", "auto-all"), meta("root-2", "auto-all"))
    val json = SessionMeta.withEffectiveSafetyModes(staleDisk, "confirm-edits")
    val bypass = json.asArray
      .getOrElse(Vector.empty)
      .filter(_.hcursor.downField("safetyMode").as[String].toOption.contains("auto-all"))
    assertEquals(bypass.size, 0, "no session may enter bypassSessions while the global mode is confirm-edits")

  test("正控: at global=auto-all the backend raises no card at all (the silent-answer path has no object)"):
    // 顶档下 `ToolReversibility` 恒 true ⇒ `permissionDecision` 恒 Allow ⇒ 后端从不
    // 发 `askPermission` 帧 ⇒ 前端那两条静默应答路径**无卡可答**（不是"被抑制"，
    // 而是没有对象）。对照：confirm-edits 下 Write 必须为不可逆（⇒ 出卡）。
    val write =
      JsonObject("file_path" -> io.circe.Json.fromString("/tmp/x"), "content" -> io.circe.Json.fromString("y"))
    val dangerousBash = JsonObject("command" -> io.circe.Json.fromString("rm -rf /tmp/permshield-probe"))
    assert(ToolReversibility.isReversible("Write", write, SafetyMode.AutoAll), "auto-all: Write is auto-approved")
    assert(
      ToolReversibility.isReversible("Bash", dangerousBash, SafetyMode.AutoAll),
      "auto-all: even dangerous Bash is auto-approved"
    )
    assert(
      !ToolReversibility.isReversible("Write", write, SafetyMode.ConfirmEdits),
      "confirm-edits: Write must ask (card)"
    )

  // ── 严格 wire 解析（写入口白名单；禁静默兜底）──────────────────────────────

  test("SafetyMode.fromWire accepts exactly the three wire values and rejects the rest"):
    assertEquals(SafetyMode.fromWire("confirm-edits"), Some(SafetyMode.ConfirmEdits))
    assertEquals(SafetyMode.fromWire("auto-edits"), Some(SafetyMode.AutoEdits))
    assertEquals(SafetyMode.fromWire("auto-all"), Some(SafetyMode.AutoAll))
    assertEquals(SafetyMode.fromWire("yolo"), None)
    assertEquals(SafetyMode.fromWire(""), None)
    assertEquals(SafetyMode.wireValues, Set("confirm-edits", "auto-edits", "auto-all"))

end SafetyModeGlobalOnlySpec
