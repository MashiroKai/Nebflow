package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.{Message, MessageRole, PathUtil}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * #38 Layer C — deleteSession 删除闭环（2026-09-01）。
 *
 * 回归护栏：deleteSession 必须删除 tool-results 落盘目录 + 清 turn-state
 * （否则重启后恢复/防抖 persist 复活会话）。
 *
 * 团队功能删除批（team-feature-deletion-20261104）阶段 3：原两条
 * `TeamSessionRegistry` 用例（unregisterAgent+unregisterActor 无残留 /
 * 未知 sid 幂等）随团队会话登记表本体删除 —— 散表 sessionMap/actorMap 已不存在，
 * 其「team 成员 actor 持 ~10MB state.messages 原样写回」的护栏对象也随之消失
 * （会话清理现在只剩统一 agentRegistry 一条权威路径）。删因：**团队会话登记表
 * 已退役**（不是「跑不过」）。
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
