package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.{PathUtil, SessionMeta}

import scala.concurrent.duration.*

/**
 * 冷启动会话链止血批（作者报障 2026-10-30「聊完一轮之前历史记录不见了，
 * 怀疑意外开了新会话」）——boot 保位三钉，纯 spec、零实例：
 *
 *   T1 多 Nebula 会话 boot **不剪枝**：`ensureAgentSession` 的 multiple 腿改
 *      SELECT-only（前端每条「新建会话」都带 root agentName，多会话是常态而非
 *      race/bug 残留）——修前每次启动把除最新外全部从索引剔除（消息文件仍在
 *      盘上 = 侧栏「历史消失」）。
 *   T2 active 位**不劫持**：`ensureActiveAgentSession` 只在持久 activeId
 *      失效（空/不在索引）时才 switch——修前每次重启把活动会话改挂到最新
 *      Nebula 会话，前端静默应用 activeId 并整擦聊天 DOM = 「像开了新会话」。
 *   T3 claim **首选 active 的 agentless 会话**：无 Nebula 会话时收养 legacy
 *      agentless 会话，修前 `sessions.find(_.agentName.isEmpty)` 恒取第一条、
 *      无视持久 activeId —— 收养 + 换挂双 bug 叠加把用户甩到别的会话。
 *   T4 activeId 失效（孤儿/空）时**仍回落**到该 agent 会话：保位不等于失能，
 *      无效 activeId 照旧 switch 到 representative（fresh-install/损坏索引
 *      路径的正控）。
 *
 * 数据根隔离：`PathUtil.setDataRoot` 全局态 ⇒ `withFreshHome` 重定向临时目录
 * 并 guarantee 恢复（FreshInstallRootSessionSpec 同款）。
 */
class BootSessionKeepPlaceSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withFreshHome[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-boot-keepplace-")
    IO(PathUtil.setDataRoot(tmp)) *>
      f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def meta(id: String, name: String, updatedAt: Long, agent: Option[String]): SessionMeta =
    SessionMeta(id = id, name = name, createdAt = updatedAt, updatedAt = updatedAt, hasUnread = false, agentName = agent)

  /** Seed `_index.json` directly (bypassing create legs) to reach a precise boot state. */
  private def seedIndex(home: os.Path, activeId: String, sessions: List[SessionMeta]): IO[Unit] =
    IO {
      val dir = home / "sessions"
      os.makeDir.all(dir)
      val payload = Map(
        "activeId" -> activeId.asJson,
        "sessions" -> sessions.asJson,
        "folders"  -> List.empty[SessionMeta].asJson,
      ).asJson
      os.write.over(dir / "_index.json", payload.noSpaces)
    }

  test("T1 boot with multiple Nebula-tagged sessions NEVER prunes them from the index (selection only)") {
    withFreshHome { home =>
      val a = meta("sess-a", "conversation A", updatedAt = 1000L, agent = Some("Nebula"))
      val b = meta("sess-b", "conversation B", updatedAt = 2000L, agent = Some("Nebula"))
      val c = meta("sess-c", "conversation C", updatedAt = 3000L, agent = Some("Nebula"))
      val store = new SessionStore(home / "sessions", home / "tasks")
      for
        _ <- seedIndex(home, activeId = "sess-a", sessions = List(a, b, c))
        _ <- store.load
        _ <- store.ensureActiveAgentSession("Nebula")
        all <- store.listSessions
      yield
        assertEquals(all.map(_.id).toSet, Set("sess-a", "sess-b", "sess-c"),
          "every Nebula-tagged conversation must survive boot (pre-fix: all but newest were pruned)")
    }
  }

  test("T2 boot does NOT hijack a valid active session onto the agent's newest session") {
    withFreshHome { home =>
      // The user was last in conversation A; conversation C is newer (a background
      // write bumped it). Pre-fix, boot switched activeId to C and the frontend
      // wiped the chat — "history disappeared".
      val a = meta("sess-a", "conversation A", updatedAt = 1000L, agent = Some("Nebula"))
      val c = meta("sess-c", "conversation C", updatedAt = 3000L, agent = Some("Nebula"))
      val store = new SessionStore(home / "sessions", home / "tasks")
      for
        _ <- seedIndex(home, activeId = "sess-a", sessions = List(a, c))
        _ <- store.load
        meta <- store.ensureActiveAgentSession("Nebula")
        activeId <- store.getActiveId
      yield
        assertEquals(meta.id, "sess-c", "representative = newest agent session (unchanged selection rule)")
        assertEquals(activeId, "sess-a", "the user's active conversation must keep its place across restarts")
    }
  }

  test("T3 legacy claim prefers the PERSISTED ACTIVE agentless session, not the first in the list") {
    withFreshHome { home =>
      // Two agentless legacy sessions; the persisted active one is y, listed second.
      val x = meta("sess-x", "older default", updatedAt = 500L, agent = None)
      val y = meta("sess-y", "my conversation", updatedAt = 900L, agent = None)
      val store = new SessionStore(home / "sessions", home / "tasks")
      for
        _ <- seedIndex(home, activeId = "sess-y", sessions = List(x, y))
        _ <- store.load
        (claimed, _) <- store.ensureAgentSession("Nebula")
        activeId <- store.getActiveId
        all <- store.listSessions
      yield
        assertEquals(claimed.id, "sess-y", "claim must adopt the ACTIVE agentless session (pre-fix: always the first)")
        assertEquals(claimed.agentName, Some("Nebula"))
        assertEquals(activeId, "sess-y", "adoption preserves the active id — no visible session change")
        assertEquals(all.find(_.id == "sess-x").flatMap(_.agentName), None, "the sibling agentless session is untouched")
    }
  }

  test("T4 an invalid/orphaned activeId still falls back to the agent session (keep-place is not paralysis)") {
    withFreshHome { home =>
      val a = meta("sess-a", "conversation A", updatedAt = 1000L, agent = Some("Nebula"))
      val b = meta("sess-b", "legacy agentless", updatedAt = 2000L, agent = None)
      val store = new SessionStore(home / "sessions", home / "tasks")
      for
        _ <- seedIndex(home, activeId = "sess-a", sessions = List(a, b))
        _ <- store.load
        // Force a genuine invalid-activeId runtime state: delete the active
        // session — deleteSession leaves activeId "" when no same-agent session
        // remains (b is agentless, so it is not picked).
        _ <- store.deleteSession("sess-a")
        _ <- store.ensureActiveAgentSession("Nebula")
        activeId <- store.getActiveId
        all <- store.listSessions
      yield
        assertEquals(all.map(_.id), List("sess-b"))
        assertEquals(all.head.agentName, Some("Nebula"), "the surviving agentless session is claimed for the agent")
        assertEquals(activeId, "sess-b", "with the persisted active session gone, boot re-homes to the agent session")
    }
  }
end BootSessionKeepPlaceSpec
