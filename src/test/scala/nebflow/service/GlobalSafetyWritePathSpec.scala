package nebflow.service

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.{GlobalSafety, SafetyMode}
import nebflow.gateway.SessionStore
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * permshield S1（2026-09-13 作者重裁「候选 B」）· 判据②/③ 的机器化形态：
 *
 * **写路径唯一 + 真落盘 + 热读 + 新会话/重启继承**。
 *
 * 断言面：
 *  1. 档位的写入口（WS 盾牌与 REST `PUT /api/safety/mode` **共用**的那个函数
 *     `ConfigService.setSafetyDefaultMode`）真改盘面 `safety.defaultMode`；
 *  2. 定向写只碰 `safety` 子树（既有顶层键保留）；
 *  3. 改盘后**下一次热读**即生效（无需重启）——`GlobalSafety` / 唯一解析入口
 *     `SharedResources.effectiveSafetyMode` 同源；
 *  4. **新会话继承**：新建会话在会话列表出口报的就是新值（档位无会话维度）；
 *  5. **重启继承**（以"另一个进程实例"等效建模 = 新建 SharedResources + 重新
 *     `SessionStore.load` 从盘重建）：重启后读到的仍是盘上的值 ⇒ 作者口径
 *     「重启后仍生效」。
 *
 * 与 `SafetyModeRoutesSpec`（REST 路由层）互补：本 spec 打的是**持久函数 + 热读 +
 * 继承**这一段，不依赖 http4s 路由。
 */
class GlobalSafetyWritePathSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-safety-writepath"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def configPath: os.Path = tmp / "nebflow.json"

  private def onDiskMode: Option[String] =
    io.circe.parser
      .parse(os.read(configPath))
      .toOption
      .flatMap(_.hcursor.downField("safety").downField("defaultMode").as[String].toOption)

  private def writeConfig(content: String): Unit =
    os.write.over(configPath, content, createFolders = true)

  /** 最小 SharedResources：只填档位解析会读的槽位（其余 null，轻量装配先例）。 */
  private def resources: nebflow.agent.SharedResources =
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

  private def freshStore: SessionStore = SessionStore(tmp / "sessions", tmp / "tasks")

  private def exitModes(store: SessionStore): IO[List[String]] =
    for
      sessions <- store.listSessions
      json <- resources.overlaySessionList(sessions)
    yield json.asArray
      .getOrElse(Vector.empty)
      .toList
      .map(_.hcursor.downField("safetyMode").as[String].getOrElse("<absent>"))

  test("B2 写路径唯一（WS 盾牌/REST 共用同一函数）：改档 ⇒ 盘面 safety.defaultMode 变化 + 热读即生效"):
    writeConfig("""{"llm": {"providers": {}}}""")
    for
      before <- GlobalSafety.defaultMode
      _ <- ConfigService.setSafetyDefaultMode("confirm-edits")
      disk <- IO(onDiskMode)
      after <- GlobalSafety.defaultMode
      hot <- resources.effectiveSafetyMode
    yield
      assertEquals(before, SafetyMode.AutoAll, "no safety key ⇒ 启动默认顶档")
      assertEquals(disk, Some("confirm-edits"))
      assertEquals(after, SafetyMode.ConfirmEdits)
      assertEquals(hot, SafetyMode.ConfirmEdits, "唯一解析入口与 GlobalSafety 同源")
      // 定向写只碰 safety 子树
      val cfg = io.circe.parser.parse(os.read(configPath)).toOption.get
      assert(cfg.hcursor.downField("llm").downField("providers").succeeded, "existing keys must be preserved")

  test("B2b every wire value round-trips to disk (the write function is the only sink)"):
    for
      _ <- IO(writeConfig("{}"))
      _ <- ConfigService.setSafetyDefaultMode("auto-edits")
      e <- IO(onDiskMode)
      _ <- ConfigService.setSafetyDefaultMode("auto-all")
      a <- IO(onDiskMode)
      _ <- ConfigService.setSafetyDefaultMode("confirm-edits")
      c <- IO(onDiskMode)
    yield
      assertEquals(e, Some("auto-edits"))
      assertEquals(a, Some("auto-all"))
      assertEquals(c, Some("confirm-edits"))

  test("C3 新会话继承：新建会话在出口报的是新档位（无会话维度）"):
    writeConfig("""{"safety": {"defaultMode": "auto-edits"}}""")
    val store = freshStore
    for
      _ <- store.load
      _ <- store.createSession("s-new-session")
      modes <- exitModes(store)
    yield
      assert(modes.nonEmpty, "the new session must be listed")
      assertEquals(modes.distinct, List("auto-edits"))

  test("C3b 重启继承（fresh 实例 + 从盘重建索引）：盘上是新值 ⇒ 重启后仍是新值"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    val store1 = freshStore
    for
      _ <- store1.load
      _ <- store1.createSession("s-persist")
      _ <- ConfigService.setSafetyDefaultMode("auto-all")
      // "重启"：新 SessionStore 从盘重建（无任何进程内状态）+ 新 SharedResources 读盘
      restarted = freshStore
      _ <- restarted.load
      modesAfterRestart <- exitModes(restarted)
      disk <- IO(onDiskMode)
    yield
      assertEquals(disk, Some("auto-all"))
      assertEquals(modesAfterRestart.distinct, List("auto-all"), "the persisted mode survives a restart")

  test("C3c 负控：档位不受会话侧动作影响（改会话/删会话都不改盘上档位）"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    val store = freshStore
    for
      _ <- store.load
      id <- store.createSession("s-untouched").map(_.id)
      _ <- store.renameSession(id, "renamed")
      _ <- store.deleteSession(id)
      disk <- IO(onDiskMode)
      hot <- resources.effectiveSafetyMode
    yield
      assertEquals(disk, Some("confirm-edits"), "session-side actions must not touch safety.defaultMode")
      assertEquals(hot, SafetyMode.ConfirmEdits)

end GlobalSafetyWritePathSpec
