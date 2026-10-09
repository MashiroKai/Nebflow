package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * ChainStore 持久往返判读（worktree-judge-fifo 批 #1224 增补 §3.5 ⑤A 骨架）：
 * append→read 往返、keyFor 归一（台账号优先 / delegateId 回落 / 非法键拒写）、
 * 坏文件容错（缺失/坏形 = None / 跳过）、listSync 排序。
 */
class ChainStoreSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-store"
  private val originalRoot = PathUtil.dataRoot

  override def beforeEach(context: munit.BeforeEach): Unit =
    PathUtil.setDataRoot(tempRoot)
    os.remove.all(tempRoot / "chains")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  test("append→read round trip: entries persist across independent reads with kind/at/payload intact") {
    ChainStore.appendSync("31", "delegate-spawn", Json.obj("branch" -> "delegate/d-x".asJson, "worktree" -> "".asJson))
    ChainStore.appendSync("31", "terminal-verdict", Json.obj("verdict" -> "ready-to-merge".asJson))
    ChainStore.appendSync("31", "merge-queue", Json.obj("action" -> "enqueued".asJson))
    val rec = ChainStore.readSync("31").getOrElse(fail("record must exist"))
    assertEquals(rec.taskId, "31")
    assertEquals(
      rec.entries.map(_.kind),
      List("delegate-spawn", "terminal-verdict", "merge-queue"),
      "append order preserved (time-ordered chain)"
    )
    assertEquals(rec.entries.head.payload.hcursor.get[String]("branch").toOption, Some("delegate/d-x"))
    assert(rec.entries.forall(_.at > 0), "every entry carries its timestamp")
  }

  test(
    "keyFor: task ledger id wins when valid; delegateId is the fallback; invalid keys are refused at the write boundary"
  ) {
    assertEquals(ChainStore.keyFor(Some("31"), "delegate-nebflow-abc"), "31")
    assertEquals(ChainStore.keyFor(None, "delegate-nebflow-abc"), "delegate-nebflow-abc")
    assertEquals(
      ChainStore.keyFor(Some("../escape"), "d-1"),
      "d-1",
      "a path-traversal ledger id must not become the key"
    )
    assertEquals(ChainStore.keyFor(Some(""), "d-1"), "d-1")
    // 非法键写入 = 静默拒（不落盘、不抛）
    ChainStore.appendSync("../escape", "delegate-spawn", Json.obj())
    assertEquals(ChainStore.readSync("../escape"), None)
  }

  test("missing/corrupt files degrade to None / skipped — never throw") {
    assertEquals(ChainStore.readSync("absent"), None)
    assertEquals(ChainStore.listSync(), Nil)
    os.makeDir.all(PathUtil.dataRoot / "chains")
    os.write.over(PathUtil.dataRoot / "chains" / "bad.json", "{not json")
    ChainStore.appendSync("good", "delegate-spawn", Json.obj("k" -> "v".asJson))
    val listed = ChainStore.listSync().map(_.taskId)
    assertEquals(listed, List("good"), "corrupt files are skipped, healthy ones listed")
  }

end ChainStoreSpec
