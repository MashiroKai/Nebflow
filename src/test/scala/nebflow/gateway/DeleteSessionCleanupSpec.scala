package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.core.flow.{TeamSessionRegistry, TurnStateStore}
import nebflow.shared.{Message, MessageRole}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** #38 Layer C — deleteSession 删除闭环（2026-09-01）。
  *
  * 回归护栏：deleteSession 必须删除 tool-results 落盘目录 + 清 turn-state
  * （否则重启后恢复/防抖 persist 复活会话）；TeamSessionRegistry 的
  * sessionMap/actorMap 映射删除后无残留（否则 team 成员 actor 存活持
  * ~10MB state.messages 原样写回——qa-backend「删不掉」根因）。
  */
class DeleteSessionCleanupSpec extends CatsEffectSuite:

  private val tmp: Path = Files.createTempDirectory("delete-session-cleanup-test")

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(os.Path(tmp))

  override def afterAll(): Unit =
    if Files.exists(tmp) then
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val store = SessionStore(os.Path(tmp) / "sessions", os.Path(tmp) / "tasks")
    store.load.unsafeRunSync()
    test(store).unsafeRunSync()

  private def writeFile(path: Path, content: String): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes("UTF-8"))

  test("deleteSession removes tool-results dir, turn-state and uploads (full disk cleanup)"):
    withStore { store =>
      val sid = "sess-c1"
      val sessionJson = os.Path(tmp) / "sessions" / s"$sid.json"
      val toolResultFile = os.Path(tmp) / "tool-results" / sid / "call-1.txt"
      val turnState = os.Path(tmp) / "sessions" / sid / "turn-state.json"
      val upload = os.Path(tmp) / "uploads" / sid / "a.png"
      writeFile(sessionJson.toNIO, """{"id":"sess-c1"}""")
      writeFile(toolResultFile.toNIO, "x" * 100)
      writeFile(turnState.toNIO, """{"inProgress":true}""")
      writeFile(upload.toNIO, "img")
      for
        _ <- store.createSession("Sess C1").attempt // may fail if meta exists; ignore
        _ <- store.deleteSession(sid)
      yield
        assert(!Files.exists(sessionJson.toNIO), "session json must be deleted")
        assert(!Files.exists(toolResultFile.toNIO), "tool-results file must be deleted")
        assert(!os.exists(os.Path(tmp) / "tool-results" / sid), "tool-results dir must be deleted")
        assert(!Files.exists(turnState.toNIO), "turn-state must be cleared")
        assert(!Files.exists(upload.toNIO), "uploads must be deleted")
    }

  test("deleteSession of a session without tool-results is a no-op success"):
    withStore { store =>
      val sid = "sess-c2"
      writeFile((os.Path(tmp) / "sessions" / s"$sid.json").toNIO, """{"id":"sess-c2"}""")
      for _ <- store.deleteSession(sid)
      yield assert(!Files.exists((os.Path(tmp) / "sessions" / s"$sid.json").toNIO))
    }

  test("TeamSessionRegistry: unregisterAgent + unregisterActor leaves no mapping residue"):
    // 模拟 stopTeamSessionActors 的两步清理（WebSocketRoutes 接线）：
    // unregisterActor（actorMap）+ unregisterAgent（sessionMap）
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("team-a", "backend", "sess-c3")
      _ <- TeamSessionRegistry.registerActor("sess-c3", null) // null ref: 只验证映射删除
      pairBefore <- TeamSessionRegistry.instanceAndAgentOfSession("sess-c3")
      _ = assertEquals(pairBefore, Some(("team-a", "backend")))
      _ <- TeamSessionRegistry.unregisterActor("sess-c3")
      _ <- TeamSessionRegistry.unregisterAgent("team-a", "backend", "sess-c3")
      pairAfter <- TeamSessionRegistry.instanceAndAgentOfSession("sess-c3")
      actorAfter <- TeamSessionRegistry.getRunningActor("sess-c3")
      sids <- TeamSessionRegistry.sessionIdsOf("team-a")
    yield
      assertEquals(pairAfter, None, "sessionMap must have no residue")
      assertEquals(actorAfter, None, "actorMap must have no residue")
      assertEquals(sids, Nil, "instance must have no sessions left")

  test("TeamSessionRegistry: unregisterActor on unknown sid is harmless (idempotent)"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.unregisterActor("ghost-sid")
      _ <- TeamSessionRegistry.unregisterAgent("team-x", "agent-x", "ghost-sid")
    yield assert(true)

  test("deleteSession does not resurrect after repeated calls (idempotent)"):
    withStore { store =>
      val sid = "sess-c4"
      writeFile((os.Path(tmp) / "sessions" / s"$sid.json").toNIO, """{"id":"sess-c4"}""")
      for
        _ <- store.deleteSession(sid)
        _ <- store.deleteSession(sid) // second call must not recreate anything
        listing <- IO.blocking {
          Files.list((os.Path(tmp) / "sessions").toNIO).iterator().asScala.toList
        }
      yield assert(!listing.exists(_.getFileName.toString == s"$sid.json"))
    }

end DeleteSessionCleanupSpec
