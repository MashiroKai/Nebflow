package nebflow.core

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.agent.PermissionPolicy
import nebflow.shared.SessionMeta

import java.nio.file.Files

/**
 * **承重断言**：`SessionMeta.safetyMode`（`sessions/_index.json` 的逐会话键）
 * 不构成权威（设计 §8 A-9 的单测形态；全局单一权威源批 2026-09-12）。
 *
 * 语义 = 有效档位由 `SafetyModeAuthority.resolve`（覆盖 ?? 全局）唯一决定：
 *  1. meta 上的 `safetyMode` 取**任何值**（含 `auto-all`）都不改变有效档位；
 *  2. 覆盖存在时不看全局值（覆盖优先，即使全局取别的档）；
 *  3. 列表出口的 `safetyMode` 键**恒存在**（不依赖 Encoder「= confirm-edits 时
 *     省略键」的隐式契约）；
 *  4. `SharedResources.overlaySessionList`（4 个线上出口走的那条）与上面同源。
 *
 * 红了 = 有人把 meta 接回了权威读取点（设计 §6.1 残留风险 + §14.2 变异 M1/M2/M4）。
 */
class SafetyModeAuthoritySpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-safety-authority"))
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

  test("A-9: a session's meta safetyMode never changes the effective level (global wins)"):
    writeGlobal("confirm-edits")
    val sessions = List(
      meta("s-auto-all", "auto-all"),
      meta("s-auto-edits", "auto-edits"),
      meta("s-confirm", "confirm-edits"),
      meta("s-garbage", "yolo")
    )
    val json = SessionMeta.withEffectiveSafetyModes(sessions, Map.empty, SafetyMode.ConfirmEdits)
    assertEquals(modes(json), List("confirm-edits", "confirm-edits", "confirm-edits", "confirm-edits"))

  test("A-9: meta=auto-all + global=auto-edits still reads auto-edits (no meta leakage)"):
    writeGlobal("auto-edits")
    val json = SessionMeta.withEffectiveSafetyModes(
      List(meta("s1", "auto-all")),
      Map.empty,
      SafetyMode.AutoEdits
    )
    assertEquals(modes(json), List("auto-edits"))

  // ── ② 覆盖优先（覆盖存在时不看全局）────────────────────────────────────────

  test("an in-memory override wins even when the global value differs (覆盖优先)"):
    writeGlobal("confirm-edits")
    val json = SessionMeta.withEffectiveSafetyModes(
      List(meta("s1", "confirm-edits")),
      Map("s1" -> SafetyMode.AutoAll),
      SafetyMode.ConfirmEdits
    )
    assertEquals(modes(json), List("auto-all"))

  test("an override is keyed by rootSessionId — other sessions keep following the global"):
    val json = SessionMeta.withEffectiveSafetyModes(
      List(meta("root-1", "auto-all"), meta("root-2", "auto-all")),
      Map("root-1" -> SafetyMode.ConfirmEdits),
      SafetyMode.AutoAll
    )
    assertEquals(modes(json), List("confirm-edits", "auto-all"))

  // ── ③ 出口键恒存在（不依赖 Encoder 省略语义）───────────────────────────────

  test("the list exit always carries an explicit safetyMode key, even for confirm-edits"):
    val json = SessionMeta.withEffectiveSafetyModes(
      List(meta("s1", "confirm-edits")),
      Map.empty,
      SafetyMode.ConfirmEdits
    )
    // 键必须显式出现 —— 旧契约下 `confirm-edits` 会被 Encoder 省略（前端靠
    // `|| 'confirm-edits'` 兜底），新契约要求三档值在 wire 上恒存在。
    assert(
      json.asArray.get.head.hcursor.downField("safetyMode").succeeded,
      "safetyMode key must be present in the session-list exit"
    )
    assertEquals(modes(json), List("confirm-edits"))

  test("the list exit preserves the other session fields (deepMerge, not replace)"):
    writeGlobal("auto-all")
    val json = SessionMeta.withEffectiveSafetyModes(List(meta("s1", "auto-all")), Map.empty, SafetyMode.AutoAll)
    val head = json.asArray.get.head
    assertEquals(head.hcursor.downField("id").as[String].toOption, Some("s1"))
    assertEquals(head.hcursor.downField("name").as[String].toOption, Some("s1"))
    assertEquals(head.hcursor.downField("hasUnread").as[Boolean].toOption, Some(false))

  // ── ④ 线上出口那条链（SharedResources.overlaySessionList）与上面同源 ────────

  test("SharedResources.overlaySessionList honours overrides over the persisted index value"):
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
      voiceMutedRef = null,
      permissionPolicies =
        cats.effect.Ref.unsafe[IO, Map[String, PermissionPolicy]](
          Map("covered" -> PermissionPolicy(safetyMode = SafetyMode.AutoAll))
        )
    )
    for
      json <- res.overlaySessionList(List(meta("covered", "auto-all"), meta("bare", "auto-all")))
      // 全局热切后，未覆盖会话的下一个出口即反映新值
      _ = writeGlobal("auto-edits")
      json2 <- res.overlaySessionList(List(meta("covered", "auto-all"), meta("bare", "auto-all")))
    yield
      assertEquals(modes(json), List("auto-all", "confirm-edits"))
      assertEquals(modes(json2), List("auto-all", "auto-edits"))

  // ── 纯规则（唯一合并点）────────────────────────────────────────────────────

  test("SafetyModeAuthority.resolve is the single 覆盖 ?? 全局 rule"):
    assertEquals(SafetyModeAuthority.resolve(Map("a" -> SafetyMode.ConfirmEdits), "a", SafetyMode.AutoAll),
      SafetyMode.ConfirmEdits)
    assertEquals(SafetyModeAuthority.resolve(Map("a" -> SafetyMode.ConfirmEdits), "b", SafetyMode.AutoAll),
      SafetyMode.AutoAll)
    assertEquals(SafetyModeAuthority.resolve(Map.empty, "a", SafetyMode.AutoEdits), SafetyMode.AutoEdits)

  // ── 严格 wire 解析（写入口白名单；禁静默兜底）──────────────────────────────

  test("SafetyMode.fromWire accepts exactly the three wire values and rejects the rest"):
    assertEquals(SafetyMode.fromWire("confirm-edits"), Some(SafetyMode.ConfirmEdits))
    assertEquals(SafetyMode.fromWire("auto-edits"), Some(SafetyMode.AutoEdits))
    assertEquals(SafetyMode.fromWire("auto-all"), Some(SafetyMode.AutoAll))
    assertEquals(SafetyMode.fromWire("yolo"), None)
    assertEquals(SafetyMode.fromWire(""), None)
    assertEquals(SafetyMode.wireValues, Set("confirm-edits", "auto-edits", "auto-all"))

end SafetyModeAuthoritySpec
