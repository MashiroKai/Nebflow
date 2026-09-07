package nebflow.core.project

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * FlowMapStore 单测（#28 阶段 0）——store 层契约：
 * - open 首写：`.nebflow/flow-map.json` 由 store 创建（验收①「只有 flow-map.json 被 store 写」）
 * - 读写往返：mutate → snapshot；重新 open 从磁盘恢复
 * - 环检测：A→B→A 拒（DFS 沿 out 边）；Nebula 终止链不误报
 * - 链级即时归档 sweep（裁定④「TTL 分开」批 2026-09-07）：整链全终态 → 整批立即移归档
 *   （分文件落盘）；链未齐（含 blocked 待办）→ 整批保留；findNode 归档兜底
 * - 存量单文件 flow-map-archive.json → 分批文件零丢失迁移（幂等）
 */
class FlowMapStoreSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def freshWorkspace(): String =
    os.temp.dir(prefix = "nb-flowmap-", deleteOnExit = false).toString

  private val now = System.currentTimeMillis()

  private def node(id: String, name: String, status: String = NodeLifecycle.Wiring): NodeDef =
    NodeDef(id = id, name = name, agent = "Backend", status = status, createdAt = now)

  private def nodeAt(id: String, name: String, status: String, createdAt: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "Backend", status = status, createdAt = createdAt)

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
      // 无手写文件：open 只落 flow-map.json（归档为空不写——分文件目录与单文件都不出现）
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

  // ── 链级即时归档 sweep（裁定④）─────────────────────────────

  test("sweepCompletedChains: 整链全终态 → 立即移归档（无 24h 滞留）+ 分文件落盘 + findNode 兜底") {
    val ws = freshWorkspace()
    val batchFile = os.Path(ws) / ".nebflow" / "flow-map-archive" / "chain-n-a1.json"
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        // 同批（createdAt 间隔 60s ≤120s）双节点全终态；ttlExpireAt 在未来——
        // 旧语义下「未到期不移」，新语义整链全终态立即归档
        "n-a1" -> nodeAt("n-a1", "impl", NodeLifecycle.Completed, now - 60000)
          .copy(result = Some("full result text"), completedAt = Some(now - 50000), ttlExpireAt = Some(now + 999999)),
        "n-a2" -> nodeAt("n-a2", "verify", NodeLifecycle.Failed, now)
          .copy(result = Some("verify failed detail"), completedAt = Some(now - 10000), ttlExpireAt = Some(now + 999999))
      )))
      removed <- store.sweepCompletedChains(now).map(_.sorted)
      s <- store.snapshot
      arch <- store.archiveSnapshot
      bt <- store.archiveBatches
      fromArchive <- store.findNode("n-a1")
      fileExists <- IO.blocking(os.exists(batchFile))
      monolithExists <- IO.blocking(os.exists(os.Path(ws) / ".nebflow" / "flow-map-archive.json"))
      batchJson = jsonParse(os.read(batchFile)).toOption.get
      batchNodes <- IO.fromEither(batchJson.hcursor.downField("nodes").keys.toRight(new Exception("no nodes")))
    yield
      assertEquals(removed, List("n-a1", "n-a2"))
      assertEquals(s.nodes.keySet, Set.empty)
      assertEquals(arch.nodes.keySet, Set("n-a1", "n-a2"))
      assertEquals(arch.nodes("n-a1").result, Some("full result text"), "归档内存保留结果全文")
      assertEquals(bt.keySet, Set("chain-n-a1"), "批 id = chain-<批内 createdAt 最早节点>（与前端同源）")
      assertEquals(bt("chain-n-a1").nodeIds, Set("n-a1", "n-a2"))
      assertEquals(fromArchive.map(_.name), Some("impl"))
      assertEquals(fileExists, true, "一批一文件")
      assertEquals(monolithExists, false, "不再写单文件 flow-map-archive.json")
      assertEquals(batchNodes.toList.sorted, List("n-a1", "n-a2"))
  }

  test("sweepCompletedChains: 链未齐（running / blocked 待办）→ 整批保留主图") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        // 批1：一完成一 running → 链未齐，整批保留（含已到期终态成员）
        "n-b1" -> nodeAt("n-b1", "impl", NodeLifecycle.Completed, now - 60000)
          .copy(ttlExpireAt = Some(now - 1)),
        "n-b2" -> nodeAt("n-b2", "verify", NodeLifecycle.Running, now - 30000),
        // 批2：一完成一 blocked（待办非终态）→ 整批保留
        "n-c1" -> nodeAt("n-c1", "design", NodeLifecycle.Completed, now + 3600000)
          .copy(ttlExpireAt = Some(now - 1)),
        "n-c2" -> nodeAt("n-c2", "review", NodeLifecycle.Blocked, now + 3660000)
          .copy(ttlExpireAt = None)
      )))
      removed <- store.sweepCompletedChains(now)
      s <- store.snapshot
      arch <- store.archiveSnapshot
    yield
      assertEquals(removed, List.empty)
      assertEquals(s.nodes.keySet, Set("n-b1", "n-b2", "n-c1", "n-c2"))
      assertEquals(arch.nodes.keySet, Set.empty)
  }

  test("sweepCompletedChains: 分批边界（相邻间隔 >120s 开新批）——只归档全终态批") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        // 批1（旧，全终态）→ 归档
        "n-old1" -> nodeAt("n-old1", "old-impl", NodeLifecycle.Completed, now - 3600000).copy(completedAt = Some(now - 3500000)),
        // 批2（新，间隔 300s > 120s；含 pending）→ 保留
        "n-new1" -> nodeAt("n-new1", "new-impl", NodeLifecycle.Completed, now - 300000).copy(completedAt = Some(now - 200000)),
        "n-new2" -> nodeAt("n-new2", "new-verify", NodeLifecycle.Pending, now - 240000).copy(in = List("n-new1"))
      )))
      removed <- store.sweepCompletedChains(now)
      s <- store.snapshot
      bt <- store.archiveBatches
    yield
      assertEquals(removed, List("n-old1"))
      assertEquals(s.nodes.keySet, Set("n-new1", "n-new2"))
      assertEquals(bt.keySet, Set("chain-n-old1"))
  }

  test("sweepCompletedChains: 归档跨重开（分文件水合 + 结果全文保留）") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "done" -> node("done", "done", NodeLifecycle.Completed).copy(result = Some("persisted"), completedAt = Some(now))
      )))
      _ <- store.sweepCompletedChains(now)
      reopened <- FlowMapStore.open("demo", ws)
      arch <- reopened.archiveSnapshot
      bt <- reopened.archiveBatches
      fromArchive <- reopened.findNode("done")
    yield
      assertEquals(arch.nodes("done").result, Some("persisted"))
      assertEquals(fromArchive.map(_.result), Some(Some("persisted")))
      assertEquals(bt.keySet, Set("chain-done"))
      assertEquals(bt("chain-done").nodeIds, Set("done"))
  }

  test("mutateArchive: 单点更新只重写所属批文件（diff 落盘）") {
    val ws = freshWorkspace()
    val dir = os.Path(ws) / ".nebflow" / "flow-map-archive"
    for
      store <- FlowMapStore.open("demo", ws)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-x1" -> nodeAt("n-x1", "X1", NodeLifecycle.Completed, now - 3600000).copy(completedAt = Some(now - 3500000)),
        "n-y1" -> nodeAt("n-y1", "Y1", NodeLifecycle.Completed, now - 300000).copy(completedAt = Some(now - 200000))
      )))
      _ <- store.sweepCompletedChains(now)
      mtimeY0 <- IO.blocking(os.mtime(dir / "chain-n-y1.json"))
      _ <- IO.blocking(Thread.sleep(20)) // mtime 粒度护栏
      // 归档节点 out 改线（NodeTools 同款单点更新路径）
      _ <- store.mutateArchive(a => a.copy(nodes = a.nodes.updatedWith("n-x1")(_.map(_.copy(out = Some("n-z"))))))
      mtimeY1 <- IO.blocking(os.mtime(dir / "chain-n-y1.json"))
      xJson = jsonParse(os.read(dir / "chain-n-x1.json")).toOption.get
      xOut <- IO.fromEither(xJson.hcursor.downField("nodes").downField("n-x1").get[String]("out"))
    yield
      assertEquals(xOut, "n-z")
      assertEquals(mtimeY1, mtimeY0, "未受影响批文件不重写")
  }

  // ── 存量单文件 → 分批迁移（裁定④，零丢失幂等）────────────────

  test("legacy single-file archive migrates to per-batch files on open (零丢失 + 幂等 + .split-bak)") {
    val ws = freshWorkspace()
    val wsPath = os.Path(ws)
    val legacy = FlowMapArchive(project = "demo", nodes = Map(
      // 同批双节点（间隔 60s）
      "m-a1" -> nodeAt("m-a1", "legacy-impl", NodeLifecycle.Completed, now - 7200000).copy(result = Some("r-a1"), completedAt = Some(now - 7100000)),
      "m-a2" -> nodeAt("m-a2", "legacy-verify", NodeLifecycle.Completed, now - 7140000).copy(result = Some("r-a2"), completedAt = Some(now - 7000000)),
      // 独立批（间隔 300s）
      "m-b1" -> nodeAt("m-b1", "legacy-solo", NodeLifecycle.Cancelled, now - 6840000).copy(result = Some("r-b1"), completedAt = Some(now - 6800000))
    ))
    for
      _ <- IO.blocking {
        os.makeDir.all(wsPath / ".nebflow")
        os.write.over(wsPath / ".nebflow" / "flow-map-archive.json", legacy.asJson.noSpaces)
      }
      store <- FlowMapStore.open("demo", ws)
      arch <- store.archiveSnapshot
      bt <- store.archiveBatches
      fa1 <- store.findNode("m-a1")
      fb1 <- store.findNode("m-b1")
      monolithGone <- IO.blocking(!os.exists(wsPath / ".nebflow" / "flow-map-archive.json"))
      splitBak <- IO.blocking(os.exists(wsPath / ".nebflow" / "flow-map-archive.json.split-bak"))
      batchFiles <- IO.blocking(os.list(wsPath / ".nebflow" / "flow-map-archive").map(_.last).toList.sorted)
      // 幂等：二次 open 不重复迁移、文件稳定
      mtimes1 <- IO.blocking(batchFiles.map(f => os.mtime(wsPath / ".nebflow" / "flow-map-archive" / f)))
      _ <- FlowMapStore.open("demo", ws)
      mtimes2 <- IO.blocking(batchFiles.map(f => os.mtime(wsPath / ".nebflow" / "flow-map-archive" / f)))
    yield
      assertEquals(arch.nodes.keySet, Set("m-a1", "m-a2", "m-b1"), "存量节点零丢失")
      assertEquals(bt.keySet, Set("chain-m-a1", "chain-m-b1"))
      assertEquals(bt("chain-m-a1").nodeIds, Set("m-a1", "m-a2"))
      assertEquals(fa1.flatMap(_.result), Some("r-a1"), "结果全文水合")
      assertEquals(fb1.map(_.status), Some(NodeLifecycle.Cancelled))
      assertEquals(monolithGone, true, "单文件改名让位")
      assertEquals(splitBak, true, "原单文件留存 .split-bak 回滚锚点")
      assertEquals(batchFiles, List("chain-m-a1.json", "chain-m-b1.json"))
      assertEquals(mtimes2, mtimes1, "二次 open 幂等零重写")
  }

end FlowMapStoreSpec
