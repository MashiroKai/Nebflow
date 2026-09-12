package nebflow.gateway

import munit.CatsEffectSuite
import nebflow.core.{GlobalSafety, PathUtil, SafetyMode, SafetyModeAuthority}

import java.nio.file.Files

/**
 * **T-2/R1 = C 回归钉**（2026-09-12 作者裁定 + 设计 §8 A-10）：索引损坏 ⇒ 孤儿会话
 * 重建时，档位必须**显式取自全局权威源**（`GlobalSafety.defaultMode`），**不得**
 * 静默落到 `SessionMeta` 的构造缺省（字面量 `"auto-all"`）。"恢复出的会话 = 顶档"
 * 正是由此而生（T-2/R1）。
 *
 * 为什么承重：本 spec 把全局设为 **confirm-edits / auto-edits**（≠ 缺省
 * `auto-all`），因此恢复路径若仍用硬编码缺省，断言必红。
 *
 * 同时钉住 A-10 的语义面：恢复后所有会话的**有效档位** = 全局值（meta 已非权威）。
 */
class SessionStoreRecoverAuthoritySpec extends CatsEffectSuite:

  private val indexedId = "11111111-1111-1111-1111-111111111111"
  private val orphanId = "22222222-2222-2222-2222-222222222222"

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-recover-authority"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def writeGlobal(mode: String): Unit =
    os.write.over(tmp / "nebflow.json", s"""{"safety": {"defaultMode": "$mode"}}""", createFolders = true)

  /** 一个索引在册的会话 + 一个磁盘上的孤儿（`<uuid>.json` 不在索引里 ⇒
    * `loadFromIndex` → `recoverOrphans` 重建它）。孤儿**不写 safetyMode** ——
    * 正是恢复路径要填的那一格。 */
  private def seedDir: os.Path =
    val sessionsDir = tmp / "sessions"
    os.makeDir.all(sessionsDir)
    os.write.over(
      sessionsDir / "_index.json",
      s"""{"activeId":"$indexedId",
         | "sessions":[{"id":"$indexedId","name":"Indexed","createdAt":1,"updatedAt":2,"hasUnread":false}],
         | "folders":[]}""".stripMargin
    )
    os.write.over(sessionsDir / s"$indexedId.json", "[]")
    os.write.over(
      sessionsDir / s"$orphanId.json",
      """[{"role":"user","content":[{"type":"text","text":"orphan session"}]}]"""
    )
    sessionsDir

  private def newStore(sessionsDir: os.Path): SessionStore = SessionStore(sessionsDir, tmp / "tasks")

  test("T-2/R1=C: recovered orphan takes the global authority value, not the hardcoded default"):
    writeGlobal("confirm-edits")
    val store = newStore(seedDir)
    store.load *> store.listSessions.map { loaded =>
      val recovered = loaded.find(_.id == orphanId).getOrElse(fail("orphan was not recovered"))
      assertEquals(recovered.name, "Recovered Session") // 消息文件解析成功的标志
      assertEquals(
        recovered.safetyMode,
        "confirm-edits",
        "recoverOrphans must read the global authority (GlobalSafety), not SessionMeta's hardcoded default"
      )
      assert(loaded.exists(_.id == indexedId), "the indexed session must survive recovery")
    }

  test("a second authority value (auto-edits) is reflected — proves it is read, not coincidental"):
    writeGlobal("auto-edits")
    val store = newStore(seedDir)
    store.load *> store.listSessions.map { loaded =>
      val recovered = loaded.find(_.id == orphanId).getOrElse(fail("orphan was not recovered"))
      assertEquals(recovered.safetyMode, "auto-edits")
    }

  test("A-10: after recovery every session's EFFECTIVE level equals the global value"):
    writeGlobal("confirm-edits")
    val store = newStore(seedDir)
    for
      _ <- store.load
      loaded <- store.listSessions
      global <- GlobalSafety.defaultMode
    yield
      assertEquals(global, SafetyMode.ConfirmEdits)
      assert(loaded.nonEmpty, "recovery must leave a non-empty session list")
      // 空覆盖映射 ⇒ 每个会话的有效档位 = 覆盖 ?? 全局 = 全局（恢复路径无特例）
      val effective = loaded.map(s => s.id -> SafetyModeAuthority.resolve(Map.empty, s.id, global))
      assert(
        effective.forall(_._2 == SafetyMode.ConfirmEdits),
        s"every recovered session must resolve to the global value, got $effective"
      )

  test("recovery persists the authority value (re-read from disk after an index write)"):
    writeGlobal("confirm-edits")
    val sessionsDir = seedDir
    val store = newStore(sessionsDir)
    for
      _ <- store.load
      _ <- store.createSession("trigger-index-write") // 任何 createSession 都会 saveIndex
      fresh = newStore(sessionsDir)
      _ <- fresh.load
      reloaded <- fresh.listSessions
    yield
      val recovered = reloaded.find(_.id == orphanId).getOrElse(fail("orphan lost after index write"))
      assertEquals(recovered.safetyMode, "confirm-edits")

end SessionStoreRecoverAuthoritySpec
