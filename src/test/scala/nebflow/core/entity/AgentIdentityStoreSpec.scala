package nebflow.core.entity

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * AgentIdentityStore spec（identity-sync 批 2026-10-23）：展示面写读路径 + LWW
 * 收敛 + Soul 自我认知段的单测（验收③：同步写读路径覆盖）。
 */
class AgentIdentityStoreSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-agent-identity-store"
  PathUtil.setDataRoot(tempRoot)

  private def reset(): IO[Unit] = IO.delay {
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
  }

  private def agentJson(agent: String): Json =
    io.circe.parser.parse(os.read(tempRoot / "agents" / agent / "agent.json")).toOption.get

  // ── 写面：displayName（根名补底 + LWW 戳）────────────────────────────────────

  test("writeDisplayName stamps identityUpdatedAt and preserves existing keys"):
    reset() *>
      IO.blocking {
        val dir = tempRoot / "agents" / "writer"
        os.makeDir.all(dir)
        os.write(dir / "agent.json", Json.obj("name" -> "writer".asJson, "model" -> Json.obj("preset" -> "balanced".asJson)).noSpaces)
      } *>
      AgentIdentityStore.writeDisplayName("writer", "小雨").map { _ =>
        val j = agentJson("writer")
        assertEquals(j.hcursor.downField("displayName").as[String], Right("小雨"))
        assert(j.hcursor.downField("model").as[io.circe.Json].isRight) // 既有键保住
        assert(j.hcursor.downField("identityUpdatedAt").as[Long].isRight) // LWW 戳在场
      }

  test("writeDisplayName backfills the root agent's name key when absent"):
    reset() *>
      AgentIdentityStore.writeDisplayName("Nebula", "诗人").map { _ =>
        val j = agentJson("Nebula")
        assertEquals(j.hcursor.downField("name").as[String], Right("Nebula")) // 补底键
        assertEquals(j.hcursor.downField("displayName").as[String], Right("诗人"))
      }

  test("writeAvatar / clearAvatar write+stamp; readRec reads all three faces"):
    for
      _ <- reset()
      _ <- AgentIdentityStore.writeDisplayName("Nebula", "诗人")
      _ <- AgentIdentityStore.writeAvatar("Nebula", Array[Byte](1, 2, 3))
      rec1 = AgentIdentityStore.readRec("Nebula")
      _ = assertEquals(rec1.displayName, Some("诗人"))
      _ = assertEquals(rec1.avatarSha256, Some(AgentIdentityStore.sha256Hex(Array[Byte](1, 2, 3))))
      _ = assert(rec1.updatedAt > 0)
      _ <- AgentIdentityStore.clearAvatar("Nebula")
      rec2 = AgentIdentityStore.readRec("Nebula")
      _ = assertEquals(rec2.avatarSha256, None)
      _ = assertEquals(rec2.displayName, Some("诗人")) // 清头像不动名字
      _ <- IO(assert(os.exists(tempRoot / "agents" / "Nebula" / "avatar") == false))
    yield
      ()

  // ── 收敛面：LWW applyRemote ──────────────────────────────────────────────────

  test("applyRemote: strictly-older stamp is skipped, equal/newer applies"):
    for
      _ <- reset()
      _ <- AgentIdentityStore.writeDisplayName("Nebula", "旧名", stampMs = 1000L)
      stale = AgentIdentityStore.AgentIdentityRec("Nebula", Some("更旧"), Some("aa"), 999L)
      skipped <- AgentIdentityStore.applyRemote(stale, None)
      _ = assertEquals(skipped, false)
      _ = assertEquals(AgentIdentityStore.readRec("Nebula").displayName, Some("旧名")) // 未被动过
      newer = AgentIdentityStore.AgentIdentityRec("Nebula", Some("新名"), Some("bb"), 1000L)
      applied <- AgentIdentityStore.applyRemote(newer, Some(Array[Byte](9)))
      _ = assertEquals(applied, true)
      rec = AgentIdentityStore.readRec("Nebula")
      _ = assertEquals(rec.displayName, Some("新名"))
      _ = assertEquals(rec.avatarSha256, Some(AgentIdentityStore.sha256Hex(Array[Byte](9))))
      _ = assertEquals(rec.updatedAt, 1000L) // 远端戳落盘（不取本地时钟）
    yield
      ()

  test("applyRemote: displayName=None removes the key (total-state convergence)"):
    for
      _ <- reset()
      _ <- AgentIdentityStore.writeDisplayName("writer", "旧名", stampMs = 1L)
      unnamed = AgentIdentityStore.AgentIdentityRec("writer", None, None, 2L)
      applied <- AgentIdentityStore.applyRemote(unnamed, None)
      _ = assertEquals(applied, true)
      j = agentJson("writer")
      _ = assertEquals(j.hcursor.downField("displayName").as[Option[String]], Right(None)) // 键被摘除
    yield
      ()

  // ── 提示词自我认知段 ─────────────────────────────────────────────────────────

  test("selfSectionText: empty for default identity, both lines for named+avatar"):
    val empty = AgentIdentityStore.selfSectionText("Nebula", None, None)
    assertEquals(empty, "")
    val mechanism = AgentIdentityStore.selfSectionText("Nebula", Some("Nebula"), None) // 机制名 = 未起名
    assertEquals(mechanism, "")
    val both = AgentIdentityStore.selfSectionText("Nebula", Some("小雨"), Some("abc123"))
    assert(both.contains("Display name: \"小雨\""))
    assert(both.contains("abc123".take(8)))
    val custom = AgentIdentityStore.selfSectionText("helper", Some("helper"), Some("abc123"))
    assert(!custom.contains("Display name")) // 名 = 机制名 ⇒ 名字行不渲染
    assert(custom.contains("Avatar"))

  // ── Soul.md 自我认知段（「落实到智能体的记忆」腿）────────────────────────────

  test("touchSoulIdentity: skipped without Soul.md, replaces in place with template Soul"):
    for
      _ <- reset()
      absent <- AgentIdentityStore.touchSoulIdentity("Nebula", Some("小雨"), avatarSet = true)
      _ = assertEquals(absent, false) // 无 Soul.md ⇒ 跳过（不凭空造）
      _ <- IO.blocking {
        os.write(
            tempRoot / "Soul.md",
            """# Soul.md
              |> 未设置 · 诞生于 2026-10-04 的 Onboarding 问卷
              |
              |## 自我认知
              |- 名字：未设置（随时可以再来起一个）
              |- 我属于：你（我不属于任何一个项目，只属于你）
              |
              |## 成长约定
              |- 每次被纠正，更新这一页，而不是只记住这一次。
              |""".stripMargin
        )
      }
      applied <- AgentIdentityStore.touchSoulIdentity("Nebula", Some("小雨"), avatarSet = true)
      _ = assertEquals(applied, true)
      soul = os.read(tempRoot / "Soul.md")
      _ = assert(soul.contains("- 名字：小雨"))
      _ = assert(soul.contains("- 头像：已设置（面板会话头展示）"))
      _ = assert(soul.contains("- 我属于：你（我不属于任何一个项目，只属于你）")) // 段内他行不动
      _ = assert(soul.contains("## 成长约定")) // 段外内容不动
      // 幂等：同值再跑不再落盘。
      again <- AgentIdentityStore.touchSoulIdentity("Nebula", Some("小雨"), avatarSet = true)
      _ = assertEquals(again, false)
    yield
      ()

  test("touchSoulIdentity: appends a minimal section when the section is absent"):
    for
      _ <- reset()
      _ <- IO.blocking(os.write(tempRoot / "Soul.md", "# Soul.md\n> 手写的灵魂。\n"))
      applied <- AgentIdentityStore.touchSoulIdentity("Nebula", None, avatarSet = false)
      _ = assertEquals(applied, true)
      soul = os.read(tempRoot / "Soul.md")
      _ = assert(soul.startsWith("# Soul.md")) // 原文在前
      _ = assert(soul.contains(s"${AgentIdentityStore.SoulSectionTitle}\n- 名字：Nebula\n- 头像：未设置"))
    yield
      ()

  test("touchSoulIdentity ignores non-root agents"):
    reset() *>
      IO.blocking(os.write(tempRoot / "Soul.md", "x")) *>
      AgentIdentityStore.touchSoulIdentity("someOtherAgent", Some("n"), avatarSet = true).map(b => assertEquals(b, false))
