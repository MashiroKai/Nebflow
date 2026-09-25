package nebflow.gateway

import munit.CatsEffectSuite
import nebflow.core.{GlobalSafety, PathUtil, SafetyMode}

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
 * 同时钉住 A-10 的语义面：恢复后所有会话的**有效档位** = 全局值（meta 是遗留键）。
 *
 * ⚠ 2026-09-13（permshield S1）改判据：**覆盖层已删除**，有效档位恒 = 全局值，
 * 故本 spec 里原 `Map.empty[String, SafetyMode]` 覆盖快照参数随之消失（唯一变化，
 * 断言语义不变：恢复路径仍写权威源值 / 出口仍不回显盘上遗留值）。
 *
 * ⚠ 2026-09-12 修复轮（证据面缺陷 2）：A-10 用例此前**恒真**——它只断言
 * （当时的）`SafetyModeAuthority.resolve(Map.empty, sid, global) == global`
 * （对空覆盖而言这是定义式；该 object 已于 2026-09-13 随覆盖层删除），既不读恢复
 * 产物、也不受任何变异影响（M-A 下仍绿）。现改为**变异可分辨**：
 * ① 断言恢复路径**写下的** meta 值 == 权威源值（M-A：落硬编码缺省 ⇒ 红）；
 * ② 断言线上出口组合（`SessionMeta.withEffectiveSafetyModes`）不回显盘上遗留值
 *    （出口 overlay 改读 meta ⇒ 红）；③ 前置断言"全局值 ≠ 构造缺省"且"盘上遗留值
 *    ≠ 全局值"（分辨力前提，缺一即恒真）。实证读数见交付说明的 M-A 一节。
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

  /**
   * 一个索引在册的会话 + 一个磁盘上的孤儿（`<uuid>.json` 不在索引里 ⇒
   * `loadFromIndex` → `recoverOrphans` 重建它）。孤儿**不写 safetyMode** ——
   * 正是恢复路径要填的那一格。
   *
   * @param indexedSafetyMode 索引里那个在册会话的 `safetyMode`（`None` = 不写键）。
   *   `Some("auto-all")` = **存量形态**：盘上写着顶档、全局却是 confirm-edits ——
   *   A-10 用例用它证明"逐会话键不构成权威"，同时让该用例的观测面**可分辨**
   *   （没有这个"盘上值 ≠ 全局值"的会话，任何断言都只能恒真）。
   */
  private def seedDir(indexedSafetyMode: Option[String] = None): os.Path =
    val sessionsDir = tmp / "sessions"
    os.makeDir.all(sessionsDir)
    val modeKey = indexedSafetyMode.fold("")(m => s""", "safetyMode": "$m"""")
    os.write.over(
      sessionsDir / "_index.json",
      s"""{"activeId":"$indexedId",
         | "sessions":[{"id":"$indexedId","name":"Indexed","createdAt":1,"updatedAt":2,"hasUnread":false$modeKey}],
         | "folders":[]}""".stripMargin
    )
    os.write.over(sessionsDir / s"$indexedId.json", "[]")
    os.write.over(
      sessionsDir / s"$orphanId.json",
      """[{"role":"user","content":[{"type":"text","text":"orphan session"}]}]"""
    )
    sessionsDir
  end seedDir

  private def newStore(sessionsDir: os.Path): SessionStore = SessionStore(sessionsDir, tmp / "tasks")

  test("T-2/R1=C: recovered orphan takes the global authority value, not the hardcoded default"):
    writeGlobal("confirm-edits")
    val store = newStore(seedDir())
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
    val store = newStore(seedDir())
    store.load *> store.listSessions.map { loaded =>
      val recovered = loaded.find(_.id == orphanId).getOrElse(fail("orphan was not recovered"))
      assertEquals(recovered.safetyMode, "auto-edits")
    }

  test("A-10: after recovery every session's EFFECTIVE level equals the global value (meta is not an authority)"):
    writeGlobal("confirm-edits")
    // 存量形态：索引里那个在册会话盘上写着 `safetyMode: auto-all`（≠ 全局值）
    val sessionsDir = seedDir(indexedSafetyMode = Some("auto-all"))
    val store = newStore(sessionsDir)
    for
      _ <- store.load
      loaded <- store.listSessions
      global <- GlobalSafety.defaultMode
    yield
      // 前置①：权威源 ≠ `SessionMeta` 的构造缺省（字面量 "auto-all"）——否则"读权威源"
      // 与"落硬编码缺省"两种实现**无法分辨**（旧版本用例正是栽在这个恒真形态上）。
      assertEquals(
        global,
        SafetyMode.ConfirmEdits,
        "precondition: the global value must differ from SessionMeta's hardcoded default, else this case is vacuous"
      )
      val recovered = loaded.find(_.id == orphanId).getOrElse(fail("orphan was not recovered"))
      val indexed = loaded.find(_.id == indexedId).getOrElse(fail("the indexed session vanished during recovery"))
      // 前置②：盘上遗留值必须与全局值不同 —— 观测面才有分辨力。
      assertEquals(
        indexed.safetyMode,
        "auto-all",
        "precondition: the stale per-session disk value must differ from the global value"
      )

      // 2026-09-13（permshield S1）：会话覆盖面已删除 ⇒ 有效档位恒 = 全局值，
      // 没有任何 per-session 入参可传（旧 `overrides: Map[String, SafetyMode]` 参数
      // 随覆盖层一并消失，这里正是"旧覆盖面不成立"的机械证据）。
      def effective(s: SessionMeta): SafetyMode = global

      // ① 恢复路径**写下的**盘上值 == 权威源值，且与有效档位一致。
      //    变异（恢复路径落 `SessionMeta` 构造缺省 auto-all）⇒ 本条 assert 红。
      assertEquals(
        SafetyMode.fromString(recovered.safetyMode),
        global,
        "the recovered meta must carry the authority value (mutation: the hardcoded default would be auto-all)"
      )
      assertEquals(effective(recovered), global, "the recovered session's effective level must be the global value")

      // ② 线上出口组合（唯一出口 helper `SessionMeta.withEffectiveSafetyModes`）：
      //    盘上遗留 auto-all 的会话在出口必须仍报**全局值**。
      //    变异（出口 overlay 改读 meta.safetyMode / 塞回 R-1=A 撤销形态）⇒ 红。
      val exitModes: Map[String, Option[String]] =
        SessionMeta
          .withEffectiveSafetyModes(loaded, SafetyMode.toString(global))
          .asArray
          .getOrElse(Vector.empty)
          .map(j => j.hcursor.get[String]("id").toOption.getOrElse("") -> j.hcursor.get[String]("safetyMode").toOption)
          .toMap
      assertEquals(
        exitModes.get(indexedId).flatten,
        Some("confirm-edits"),
        s"the exit overlay must not echo the stale disk value (exit=$exitModes)"
      )
      assertEquals(exitModes.get(orphanId).flatten, Some("confirm-edits"))

      // ③ 全量：恢复后的每个会话有效档位 = 全局值（A-10 字面口径）
      val allEffective = loaded.map(s => s.id -> effective(s))
      assert(
        allEffective.forall(_._2 == SafetyMode.ConfirmEdits),
        s"every recovered session must resolve to the global value, got $allEffective"
      )
      // ④ 对照：盘上键**没有被改写**（方案 A「读时忽略」，不是把数据改了）
      assertEquals(indexed.safetyMode, "auto-all", "reads must not rewrite the stale disk value")

    end for

  test("recovery persists the authority value (re-read from disk after an index write)"):
    writeGlobal("confirm-edits")
    val sessionsDir = seedDir()
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
