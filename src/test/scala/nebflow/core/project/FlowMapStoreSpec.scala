package nebflow.core.project

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * FlowMapStore 单测（#28 阶段 0）——store 层契约：
 * - open 首写：`.nebflow/flow-map.json` 由 store 创建（验收①「只有 flow-map.json 被 store 写」）
 * - 读写往返：mutate → snapshot；重新 open 从磁盘恢复
 * - 环检测：A→B→A 拒（DFS 沿 out 边）；Nebula 终止链不误报
 * - TTL sweep：终态到期 → 移归档（结果全文保留）；活动区删除；findNode 归档兜底
 */
class FlowMapStoreSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def freshWorkspace(): String =
    os.temp.dir(prefix = "nb-flowmap-", deleteOnExit = false).toString

  private val now = System.currentTimeMillis()

  private def node(id: String, name: String, status: String = NodeLifecycle.Wiring): NodeDef =
    NodeDef(id = id, name = name, agent = "Backend", status = status, createdAt = now)

  // ── open 首写 ───────────────────────────────────────────

  test("open creates .nebflow/flow-map.json (store-owned first write)") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      s <- store.snapshot
      raw <- IO.blocking(os.read(os.Path(ws) / ".nebflow" / "flow-map.json"))
      parsed <- IO.fromEither(jsonParse(raw).flatMap(_.as[FlowMapState]))
    yield
      assertEquals(s.project, "demo")
      assertEquals(parsed.project, "demo")
      assert(os.exists(os.Path(ws) / ".nebflow" / "flow-map.json"))
      // 无手写文件：open 只落 flow-map.json（archive 空不写）
      val files = os.list(os.Path(ws) / ".nebflow").map(_.last).toList
      assertEquals(files.filterNot(_.startsWith("flow-map-archive")), List("flow-map.json"))
  }

  // ── 读写往返 ───────────────────────────────────────────

  test("mutate → snapshot → reopen restores nodes with result (读写往返)") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          "n1" -> node("n1", "scan").copy(status = NodeLifecycle.Completed, result = Some("scanned ok"), ttlExpireAt = Some(now + 100000)),
          "n2" -> node("n2", "merge", NodeLifecycle.Pending).copy(in = List("n1"))
        ))
      )
      s1 <- store.snapshot
      reopened <- FlowMapStore.open("demo", ws)
      s2 <- reopened.snapshot
    yield
      assertEquals(s1.nodes.size, 2)
      assertEquals(s2.nodes("n1").result, Some("scanned ok"))
      assertEquals(s2.nodes("n1").status, NodeLifecycle.Completed)
      assertEquals(s2.nodes("n2").in, List("n1"))
  }

  // ── 环检测 ─────────────────────────────────────────────

  test("wouldCreateCycle: linear chain is fine, A→B→A rejected") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "a" -> node("a", "A"),
        "b" -> node("b", "B"),
        "c" -> node("c", "C")
      )))
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated("a", s.nodes("a").copy(out = Some("b")))))
      // b.out = c：b 的传递下游（c）不达 a → 不环
      noCycle <- store.wouldCreateCycle("b", "c")
      // a.out 已是 b；若 b.out = a → a 的传递下游（b）达 b 自身 → 环
      cycleAB <- store.wouldCreateCycle("b", "a")
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated("b", s.nodes("b").copy(out = Some("a")))))
      // 已成环后：a.out → b 再测一次仍报环
      again <- store.wouldCreateCycle("b", "a")
      // 新边 a→c 不受影响（c 下游无 a）
      fine <- store.wouldCreateCycle("a", "c")
    yield
      assertEquals(noCycle, false)
      assertEquals(cycleAB, true)
      assertEquals(again, true)
      assertEquals(fine, false)
  }

  test("wouldCreateCycle: Nebula-terminated chain never cycles") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "a" -> node("a", "A").copy(out = Some("Nebula")),
        "b" -> node("b", "B")
      )))
      r1 <- store.wouldCreateCycle("b", "a")
      r2 <- store.wouldCreateCycle("a", "Nebula")
    yield
      // a.out=Nebula 被跳过：b→a 不环
      assertEquals(r1, false)
      assertEquals(r2, false)
  }

  // ── TTL sweep 归档 ─────────────────────────────────────

  test("sweepExpired: terminal + expired → archive with full result, activity cleared; non-expired/running stay") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "done" -> node("done", "done", NodeLifecycle.Completed).copy(result = Some("full result text"), ttlExpireAt = Some(now - 1)),
        "recent" -> node("recent", "recent", NodeLifecycle.Completed).copy(result = Some("still fresh"), ttlExpireAt = Some(now + 999999)),
        "running" -> node("running", "running", NodeLifecycle.Running).copy(ttlExpireAt = Some(now - 1))
      )))
      removed <- store.sweepExpired(now)
      s <- store.snapshot
      arch <- store.archiveSnapshot
      fromArchive <- store.findNode("done")
    yield
      assertEquals(removed, List("done"))
      assertEquals(s.nodes.keySet, Set("recent", "running"))
      assertEquals(arch.nodes("done").result, Some("full result text"))
      assertEquals(fromArchive.map(_.name), Some("done"))
  }

  test("sweepExpired: archive persists across reopen") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "done" -> node("done", "done", NodeLifecycle.Completed).copy(result = Some("persisted"), ttlExpireAt = Some(now - 1))
      )))
      _ <- store.sweepExpired(now)
      reopened <- FlowMapStore.open("demo", ws)
      arch <- reopened.archiveSnapshot
      fromArchive <- reopened.findNode("done")
    yield
      assertEquals(arch.nodes("done").result, Some("persisted"))
      assertEquals(fromArchive.map(_.result), Some(Some("persisted")))
  }

end FlowMapStoreSpec
