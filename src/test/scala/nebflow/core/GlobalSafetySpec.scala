package nebflow.core

import cats.effect.IO
import munit.CatsEffectSuite
import os.*

import java.nio.file.Files

/**
 * F1 (#433): GlobalSafety — the global safety mode read from nebflow.json
 * `safety.defaultMode`. A present valid value resolves hot per access (config
 * path resolves per call). 启动默认 = 全部放行 (2026-09-12 作者令): 读不到有效值
 * （缺文件 / 缺键 / 不可解析 / 读取失败）⇒ AutoAll；显式但不可识别的值仍回
 * ConfirmEdits（保守，见 permissions.scala 的取值三档注释）。
 */
class GlobalSafetySpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = Path(Files.createTempDirectory("nb-globalsafety"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def writeConfig(content: String): Unit =
    os.write.over(tmp / "nebflow.json", content)

  test("missing config file falls back to AutoAll (启动默认 = 顶档)"):
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("config without safety key falls back to AutoAll (启动默认 = 顶档)"):
    writeConfig("""{"providers": {}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("safety.defaultMode auto-all resolves to AutoAll"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}, "providers": {}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("safety.defaultMode auto-edits resolves to AutoEdits"):
    writeConfig("""{"safety": {"defaultMode": "auto-edits"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoEdits))

  test("safety.defaultMode confirm-edits still resolves to ConfirmEdits (配置优先)"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("unknown safety.defaultMode value falls back to ConfirmEdits"):
    writeConfig("""{"safety": {"defaultMode": "yolo"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("unparsable config falls back to AutoAll (读不到有效值 ⇒ 启动默认; never throws)"):
    writeConfig("""{"safety": """)
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("hot-read: value change is visible on the next access without restart"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    for
      before <- GlobalSafety.defaultMode
      _ = writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
      after <- GlobalSafety.defaultMode
    yield
      assertEquals(before, SafetyMode.ConfirmEdits)
      assertEquals(after, SafetyMode.AutoAll)

  // ── 有效档位 resolver（覆盖 ?? 全局）——2026-09-12 全局单一权威源批 ──────────
  // 设计 §13 #1 / #21：`SharedResources.effectiveSafetyMode` 是**唯一解析入口**。
  // 三个语义：① 覆盖优先；② 无覆盖跟随全局；③ 全局热改后下一判定即生效。

  /** 最小 SharedResources：只填 resolver 会读的两个槽位（permissionPolicies +
    * 隔离的 dataRoot），其余为 null（`ListFriendsToolRegistrationSpec` 先例）。 */
  private def resourcesWith(policies: Map[String, nebflow.agent.PermissionPolicy] = Map.empty)
    : nebflow.agent.SharedResources =
    nebflow.agent.SharedResources(
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
        cats.effect.Ref.unsafe[IO, Map[String, nebflow.agent.PermissionPolicy]](policies)
    )

  test("resolver: an existing override wins over the global value (覆盖优先)"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
    val res = resourcesWith(Map("root-1" -> nebflow.agent.PermissionPolicy(safetyMode = SafetyMode.ConfirmEdits)))
    res.effectiveSafetyMode("root-1").map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("resolver: no override follows the global value (无覆盖随全局)"):
    writeConfig("""{"safety": {"defaultMode": "auto-edits"}}""")
    val res = resourcesWith()
    res.effectiveSafetyMode("root-1").map(m => assertEquals(m, SafetyMode.AutoEdits))

  test("resolver: another session's override does not leak into an uncovered session"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    val res = resourcesWith(Map("root-1" -> nebflow.agent.PermissionPolicy(safetyMode = SafetyMode.AutoAll)))
    for
      covered <- res.effectiveSafetyMode("root-1")
      other <- res.effectiveSafetyMode("root-2")
    yield
      assertEquals(covered, SafetyMode.AutoAll)
      assertEquals(other, SafetyMode.ConfirmEdits)

  test("resolver: global hot-change reaches the next resolution without restart (已连会话即时生效)"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
    val res = resourcesWith()
    for
      before <- res.effectiveSafetyMode("root-1")
      _ = writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
      after <- res.effectiveSafetyMode("root-1")
    yield
      assertEquals(before, SafetyMode.AutoAll)
      assertEquals(after, SafetyMode.ConfirmEdits)

  test("resolver: an uncovered session with no config falls back to AutoAll (启动默认)"):
    // 无 nebflow.json（beforeEach 只建空 tmp 目录）
    resourcesWith().effectiveSafetyMode("root-1").map(m => assertEquals(m, SafetyMode.AutoAll))

end GlobalSafetySpec
