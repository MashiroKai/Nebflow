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

  // ── 唯一解析入口（`SharedResources.effectiveSafetyMode`）—— 应用级全局单一来源 ──
  // permshield S1（2026-09-13 作者重裁「候选 B」）：会话级覆盖面**已删除**，
  // 解析入口不再接受会话参数 ⇒ 有效档位恒 = 全局持久值。本段同时承担
  // **负控**：原「覆盖优先」用例（覆盖存在时不看全局）已改为不可成立的形态 ——
  // 没有任何入参可以表达"某个会话有自己的档位"，故旧行为在类型上被排除。

  /** 最小 SharedResources：只填 resolver 会读的槽位（隔离 dataRoot 的配置文件），
    * 其余为 null（`ListFriendsToolRegistrationSpec` 先例）。 */
  private def resourcesWith(): nebflow.agent.SharedResources =
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
      voiceMutedRef = null
    )

  test("resolver: no per-session input exists — the effective level is the global value"):
    writeConfig("""{"safety": {"defaultMode": "auto-edits"}}""")
    resourcesWith().effectiveSafetyMode.map(m => assertEquals(m, SafetyMode.AutoEdits))

  test("resolver: 负控 — a second read is no longer session-dependent (the old 覆盖优先 path is gone)"):
    // 旧行为「覆盖 ?? 全局」需要两个入参（覆盖桶 + rootSessionId）才能表达；本批
    // 把覆盖桶与 `SafetyModeAuthority.resolve` 一并删除后，签名上就没有第二个可变
    // 来源 ⇒ 相同全局值下任何会话的读数必须**完全相同**（本断言即负控：旧"某会话
    // 有独立档位"形态无法复现）。
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    val res = resourcesWith()
    for
      a <- res.effectiveSafetyMode
      b <- res.effectiveSafetyMode
    yield
      assertEquals(a, SafetyMode.ConfirmEdits)
      assertEquals(a, b)

  test("resolver: global hot-change reaches the next resolution without restart (已连会话即时生效)"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
    val res = resourcesWith()
    for
      before <- res.effectiveSafetyMode
      _ = writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
      after <- res.effectiveSafetyMode
    yield
      assertEquals(before, SafetyMode.AutoAll)
      assertEquals(after, SafetyMode.ConfirmEdits)

  test("resolver: with no config the single entry falls back to AutoAll (启动默认)"):
    // 无 nebflow.json（beforeEach 只建空 tmp 目录）
    resourcesWith().effectiveSafetyMode.map(m => assertEquals(m, SafetyMode.AutoAll))

end GlobalSafetySpec
