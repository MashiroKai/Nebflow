package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.FunSuite
import nebflow.db.format.SessionEnvelope
import nebflow.db.{Db, DbConfig, ReadResult}
import nebflow.shared.{Message, MessageRole, UiMessage, given}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * db 接线第一阶 C4/C8 —— SessionStore 静默面收编（D-5 三态）+ UI 面 db 影子
 * 双读（D-11 最小回路）机械判据：
 *
 *  1. 坏 ui.json ⇒ `.corrupt-<ts>` 隔离（坏≠无）；db 影子在场 ⇒ 从影子恢复
 *     （渲染史由「永久销毁」改判为「可恢复」——R1 病灶根治）。
 *  2. 坏 `.json`（消息史，旧隔离形 `.corrupted.<ts>` 手搬面）⇒ 归一 Quarantine
 *     helper（R31 三先例归一，本仓第 1 处；命名改 `.corrupt-<ts>`、绝不覆盖）。
 *  3. 坏 `_index.json` ⇒ 隔离 + 孤儿恢复兜底（列表不再静默缩水）。
 *  4. ui 写穿影子：appendUiMessages → debounce flush → db sessions/<sid>/ui.*
 *     有帧（真实写+读回路，验收④）。
 */
class SessionStoreThreeStateSpec extends FunSuite:

  test("durable snapshot serializes behind a dirty flush that already captured older data"):
    val home = os.temp.dir(prefix = "ss-freeze-race")
    val store = SessionStore(home / "sessions", home / "tasks")
    val sid = "race-session"
    val old = List(Message(MessageRole.User, Left("old")))
    val latest = List(Message(MessageRole.User, Left("latest")))
    val io = for
      captured <- cats.effect.Deferred[IO, Unit]
      release <- cats.effect.Deferred[IO, Unit]
      _ <- IO { store.beforeDirtyMessageWrite = _ => captured.complete(()).void *> release.get }
      _ <- store.saveMessagesForSession(sid, old)
      flush <- store.flushPendingMessages.start
      _ <- captured.get
      write <- store.durableSwapMessages(sid, latest).start
      _ <- release.complete(())
      _ <- flush.joinWithNever
      _ <- write.joinWithNever
      restored <- SessionStore(home / "sessions", home / "tasks").loadMessagesForSession(sid)
    yield assertEquals(restored, latest)
    io.unsafeRunSync()

  test("failed durable snapshot is reported and cannot replace the previous disk image"):
    val home = os.temp.dir(prefix = "ss-freeze-write-fail")
    val sessions = home / "sessions"
    val store = SessionStore(sessions, home / "tasks")
    val blocked = sessions / "bad.json"
    os.makeDir.all(blocked)
    os.write(blocked / "keep", "old")
    val result = store.durableSwapMessages("bad", List(Message(MessageRole.User, Left("new")))).attempt.unsafeRunSync()
    assert(result.isLeft)
    assertEquals(os.read(blocked / "keep"), "old")


  test("failed dirty flush keeps the pending snapshot available for a later retry"):
    val home = os.temp.dir(prefix = "ss-flush-retry")
    val store = SessionStore(home / "sessions", home / "tasks")
    val latest = List(Message(MessageRole.User, Left("must survive failed flush")))
    val io = for
      _ <- store.saveMessagesForSession("retry", latest)
      _ <- IO { store.beforeDirtyMessageWrite = _ => IO.raiseError(new RuntimeException("simulated disk failure")) }
      failed <- store.flushPendingMessages.attempt
      preserved <- store.loadMessagesForSession("retry")
      _ <- IO { store.beforeDirtyMessageWrite = _ => IO.unit }
      _ <- store.flushPendingMessages
      restored <- SessionStore(home / "sessions", home / "tasks").loadMessagesForSession("retry")
    yield
      assert(failed.isLeft)
      assertEquals(preserved, latest)
      assertEquals(restored, latest)
    io.unsafeRunSync()

  private def corruptNames(dir: os.Path): List[String] =
    if !os.exists(dir) then Nil else os.list(dir).filter(_.last.contains(".corrupt-")).map(_.last).toList

  test("C4: corrupt ui.json — quarantined (坏≠无), absent-shadow starts empty with visible evidence"):
    val home = os.temp.dir(prefix = "ss-3state")
    // 单条目缓存 ⇒ s-1 被后续会话挤出去（loadUiMessages 走盘读路 = 收编面）。
    val store = SessionStore(home / "sessions", home / "tasks", uiCacheMaxEntries = 1)
    val io = for
      _ <- store.appendUiMessages("s-1", List(
        UiMessage.Ai("hello ui"), UiMessage.Ai("second line")))
      _ <- IO.sleep(1200.millis) // debounce(500ms) flush ⇒ 落盘
      uiFile = home / "sessions" / "s-1.ui.json"
      _ = assert(os.exists(uiFile), "前置：ui.json 在场")
      _ = os.write.over(uiFile, "[{\"role\":\"ai\", BROKEN")
      _ <- store.appendUiMessages("s-2", List(UiMessage.Ai("evictor"))) // 挤出 s-1 缓存
      _ <- IO.sleep(1200.millis)
      got <- store.getUiMessages("s-1", 0, 50)
      _ = assert(corruptNames(home / "sessions").exists(_.startsWith("s-1.ui.json.corrupt-")),
        s"坏 ui 档必须隔离留档，实际=${os.list(home / "sessions").map(_.last).mkString(",")}")
      _ = assertEquals(got._1.size, 0, "无影子 ⇒ 隔离后按缺席（旁档+WARN 可观测，非静默）")
    yield ()
    io.unsafeRunSync()

  test("C4: corrupt _index.json quarantined; orphan recovery rebuilds the list (不静默缩水)"):
    val home = os.temp.dir(prefix = "ss-idx")
    os.makeDir.all(home / "sessions")
    val now = System.currentTimeMillis()
    // 孤儿恢复只认 UUID 名（生产口径）——两枚 UUID 会话档。
    val idA = "11111111-2222-3333-4444-55555555555a"
    val idB = "11111111-2222-3333-4444-55555555555b"
    os.write(home / "sessions" / s"$idA.json", List.empty[Message].asJson.noSpaces)
    os.write(home / "sessions" / s"$idB.json",
      List(Message(MessageRole.User, Left("recovered me"), now - 1000)).asJson.noSpaces)
    os.write(home / "sessions" / "_index.json", "{GARBAGE-NOT-JSON")
    val store = SessionStore(home / "sessions", home / "tasks")
    val io = for
      _ <- store.load
      sessions <- store.listSessions
      _ = assert(corruptNames(home / "sessions").exists(_.startsWith("_index.json.corrupt-")),
        s"坏索引必须隔离，实际=${os.list(home / "sessions").map(_.last).mkString(",")}")
      ids = sessions.map(_.id).toSet
      _ = assert(ids.contains(idA) && ids.contains(idB),
        s"索引坏==无时代孤儿恢复必须兜住两面，实际=$ids")
    yield ()
    io.unsafeRunSync()

  test("C4: corrupt session .json — legacy hand-move unified into Quarantine helper (R31)"):
    val home = os.temp.dir(prefix = "ss-msg")
    os.makeDir.all(home / "sessions")
    val id = "11111111-2222-3333-4444-55555555555c"
    os.write(home / "sessions" / s"$id.json", "[{\"role\":\"user\", BROKEN-NOT-JSON")
    val store = SessionStore(home / "sessions", home / "tasks")
    val io = for
      msgs <- store.loadMessagesForSession(id)
      _ = assertEquals(msgs.size, 0)
      names = os.list(home / "sessions").map(_.last).toList
      _ = assert(names.exists(_.startsWith(s"$id.json.corrupt-")),
        s"旧隔离形 .corrupted.<ts> 已归一为 .corrupt-<ts> helper，实际=$names")
      _ = assert(!names.exists(_.contains(".corrupted.")), "旧命名不再产出（单点归一）")
    yield ()
    io.unsafeRunSync()

  test("C8: ui write-through shadow + corrupt-legacy recovery from db shadow (真实写读回路)"):
    val home = os.temp.dir(prefix = "ss-shadow")
    val dbRoot = Files.createTempDirectory("ss-shadow-db")
    val db = Db.open(DbConfig(dataRoot = dbRoot)).unsafeRunSync()
    try
      val store = SessionStore(home / "sessions", home / "tasks", dbOverride = Some(db))
      val io = for
        _ <- store.appendUiMessages("s-sh", List(
          UiMessage.Ai("shadow line one"), UiMessage.Ai("shadow line two")))
        _ <- IO.sleep(1200.millis) // debounce flush ⇒ 写穿
        shadow <- db.readSession("s-sh", SessionEnvelope.StreamUi)
        _ = shadow match
          case ReadResult.Present(sr) =>
            assert(sr.records.size >= 2, s"影子面应 ≥2 帧，实得 ${sr.records.size}")
          case other => fail(s"影子面应 Present，got $other")
        // 注入坏 ui 档 ⇒ 新实例（同 db 句柄）读面：隔离 + 影子恢复。
        _ = os.write.over(home / "sessions" / "s-sh.ui.json", "[OOPS BROKEN")
        store2 = SessionStore(home / "sessions", home / "tasks", dbOverride = Some(db))
        got <- store2.getUiMessages("s-sh", 0, 50)
        _ = assert(got._1.exists {
            case ai: UiMessage.Ai => ai.text.contains("shadow line one")
            case _ => false
          },
          s"坏 ui 档 ⇒ db 影子恢复（非空），实得 ${got._1.size} 帧")
        _ = assert(corruptNames(home / "sessions").exists(_.startsWith("s-sh.ui.json.corrupt-")),
          "恢复路径同样先隔离（坏≠无）")
      yield ()
      io.unsafeRunSync()
    finally db.close()

end SessionStoreThreeStateSpec
