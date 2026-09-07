package nebflow.core.project

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Flow Map 持久化层拆分 + 按需读取回归（2026-09-05 Flow Map 精简批）。
 *
 * 契约（内存/投递仍用全文，落盘拆两半）：
 * - flow-map.json / flow-map-archive/<batchId>.json（裁定④分批落盘）内 result 收敛为 ≤500 字符摘要 + resultFile 指针
 * - 全文持久化到 per-node 文件 `.nebflow/results/<nodeId>.md`
 * - 加载时水合：内存 result 回读文件全文（投递链/重投扫描/详情端点同源，NodeEngine 零改动）
 * - 存量污染自动迁移：无文件的 result 落文件 + JSON 收敛 + 首次迁移 .bak 备份（幂等不覆盖）
 * - NodePayload 默认载荷无 result 键（REST/WS/工具三层同源收敛）
 */
class FlowMapResultFilesSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def freshWorkspace(): os.Path =
    os.temp.dir(prefix = "nb-fm-slim-", deleteOnExit = false)

  private val now = System.currentTimeMillis()

  private def node(id: String, name: String, status: String = NodeLifecycle.Wiring): NodeDef =
    NodeDef(id = id, name = name, agent = "general", status = status, createdAt = now)

  private def longResult: String =
    ("结果段落五个字。" * 120) + "END-MARKER-FULL-TEXT" // > 500 chars, tail 独有标记验证全文

  private def readJson(p: os.Path): io.circe.Json =
    jsonParse(os.read(p)) match
      case Right(j) => j
      case Left(e)  => fail(s"corrupt json at $p: $e")

  // ── 1. 落盘拆分 ─────────────────────────────────────────

  test("persist split: JSON carries summary ≤500 + resultFile pointer; full text in results/<id>.md") {
    val ws = freshWorkspace()
    val full = longResult
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-big" -> node("n-big", "big").copy(status = NodeLifecycle.Completed, result = Some(full), ttlExpireAt = Some(now + 100000)))))
      raw <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      j = readJson(ws / ".nebflow" / "flow-map.json")
      nBig = j.hcursor.downField("nodes").downField("n-big")
      summary <- IO.fromEither(nBig.downField("result").as[String])
      pointer <- IO.fromEither(nBig.downField("resultFile").as[String])
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "results" / "n-big.md"))
      snap <- store.snapshot
    yield
      assertEquals(summary.length, 501, "JSON result must be take(500)+… (≤501 chars)")
      assert(summary.endsWith("…"), "summary must end with ellipsis")
      assertEquals(pointer, "results/n-big.md")
      assertEquals(fileFull, full, "per-node file must carry the FULL text (tail marker intact)")
      // 内存仍是全文（投递链同源）
      assertEquals(snap.nodes("n-big").result, Some(full))
      // 文件在 results/ 目录
      assert(os.exists(ws / ".nebflow" / "results"), "results dir must exist after split")
  }

  test("persist split: short result keeps full text in JSON (summary == full) + file materialized") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-small" -> node("n-small", "small").copy(status = NodeLifecycle.Completed, result = Some("short ok")))))
      j = readJson(ws / ".nebflow" / "flow-map.json")
      nSmall = j.hcursor.downField("nodes").downField("n-small")
      summary <- IO.fromEither(nSmall.downField("result").as[String])
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "results" / "n-small.md"))
    yield
      assertEquals(summary, "short ok")
      assertEquals(fileFull, "short ok")
  }

  // ── 2. 重开水合（重启后全文仍可得——重投扫描/详情端点同源）──

  test("reopen with slim JSON hydrates full result (file authoritative)") {
    val ws = freshWorkspace()
    val full = longResult
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-h2" -> node("n-h2", "hydrate2").copy(status = NodeLifecycle.Completed, result = Some(full)))))
      reopened <- FlowMapStore.open("demo", ws.toString)
      snap <- reopened.snapshot
      fromArchive <- reopened.findNode("n-h2")
    yield
      assertEquals(snap.nodes("n-h2").result, Some(full), "reopened memory must carry FULL text (hydrated from file)")
      assertEquals(fromArchive.flatMap(_.result), Some(full))
  }

  // ── 3. 存量污染自动迁移（幂等 + .bak 备份）────────────────

  test("legacy migration: inline full result → file materialized + JSON slimmed + .bak backup (idempotent)") {
    val ws = freshWorkspace()
    val full = longResult
    val legacyJson = FlowMapState(project = "legacy", updatedAt = now, nodes = Map(
      "n-old" -> node("n-old", "old", NodeLifecycle.Completed).copy(result = Some(full), ttlExpireAt = Some(now + 100000))
    )).asJson.noSpaces
    for
      _ <- IO.blocking {
        os.makeDir.all(ws / ".nebflow")
        os.write.over(ws / ".nebflow" / "flow-map.json", legacyJson)
      }
      store <- FlowMapStore.open("legacy", ws.toString)
      snap <- store.snapshot
      // 触发一次新的 mutate 落盘 → JSON 收敛
      _ <- store.mutate(identity)
      j = readJson(ws / ".nebflow" / "flow-map.json")
      nOld = j.hcursor.downField("nodes").downField("n-old")
      jsonSummary <- IO.fromEither(nOld.downField("result").as[String])
      hasFile <- IO.blocking(os.exists(ws / ".nebflow" / "results" / "n-old.md"))
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "results" / "n-old.md"))
      bakExists <- IO.blocking(os.exists(ws / ".nebflow" / "flow-map.json.bak"))
      bakHasFull <- IO.blocking { val bak = os.read(ws / ".nebflow" / "flow-map.json.bak"); bak.contains(full.take(100)) }
    yield
      assertEquals(snap.nodes("n-old").result, Some(full), "migrated memory must hold full text")
      assertEquals(jsonSummary.length, 501, "converged JSON must carry summary")
      assertEquals(hasFile, true)
      assertEquals(fileFull, full)
      assertEquals(bakExists, true, "first migration must back up the original JSON")
      assertEquals(bakHasFull, true, ".bak must contain the pre-migration (full-text) form")
  }

  test("legacy migration idempotent: second open does not overwrite .bak, files stable") {
    val ws = freshWorkspace()
    val full = longResult
    val legacyJson = FlowMapState(project = "legacy2", updatedAt = now, nodes = Map(
      "n-old2" -> node("n-old2", "old2", NodeLifecycle.Completed).copy(result = Some(full))
    )).asJson.noSpaces
    for
      _ <- IO.blocking {
        os.makeDir.all(ws / ".nebflow")
        os.write.over(ws / ".nebflow" / "flow-map.json", legacyJson)
      }
      _ <- FlowMapStore.open("legacy2", ws.toString)
      bakMtime1 <- IO.blocking(os.mtime(ws / ".nebflow" / "flow-map.json.bak"))
      fileMtime1 <- IO.blocking(os.mtime(ws / ".nebflow" / "results" / "n-old2.md"))
      _ <- FlowMapStore.open("legacy2", ws.toString)
      bakMtime2 <- IO.blocking(os.mtime(ws / ".nebflow" / "flow-map.json.bak"))
      fileMtime2 <- IO.blocking(os.mtime(ws / ".nebflow" / "results" / "n-old2.md"))
    yield
      assertEquals(bakMtime1, bakMtime2, ".bak must never be overwritten by later opens")
      assertEquals(fileMtime1, fileMtime2, "result file must not be rewritten when content identical (idempotent)")
  }

  // ── 4. 归档区同款拆分 ────────────────────────────────────

  test("archive split: sweepCompletedChains → 批文件 slim + full text in file (结果全文保留语义；裁定④分批落盘)") {
    val ws = freshWorkspace()
    val full = longResult
    for
      store <- FlowMapStore.open("demo-arch", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-done" -> node("n-done", "done", NodeLifecycle.Completed).copy(result = Some(full), ttlExpireAt = Some(now - 1)))))
      removed <- store.sweepCompletedChains(now)
      fromArchive <- store.findNode("n-done")
      archJson = readJson(ws / ".nebflow" / "flow-map-archive" / "chain-n-done.json")
      nDone = archJson.hcursor.downField("nodes").downField("n-done")
      jsonSummary <- IO.fromEither(nDone.downField("result").as[String])
      pointer <- IO.fromEither(nDone.downField("resultFile").as[String])
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "results" / "n-done.md"))
    yield
      assertEquals(removed, List("n-done"))
      assertEquals(fromArchive.flatMap(_.result), Some(full), "archived node memory/archive readback = full text")
      assertEquals(jsonSummary.length, 501)
      assertEquals(pointer, "results/n-done.md")
      assertEquals(fileFull, full)
  }

  // ── 5. 默认载荷收敛（REST / WS / 工具三层同源）─────────────

  test("NodePayload slim: no result key; description always; hasResult/taskPreview conditional") {
    val now2 = System.currentTimeMillis()
    val withAll = NodePayload.buildNodeJson(
      node("n1", "N1", NodeLifecycle.Completed).copy(
        description = Some("do the thing"),
        task = Some("the long task that must NOT leak into payload as fallback"),
        result = Some(longResult)), now2)
    val legacy = NodePayload.buildNodeJson(
      node("n2", "N2", NodeLifecycle.Completed).copy(
        task = Some("first line stays\nsecond line hidden"),
        result = Some("tiny")), now2)
    val freshNode = NodePayload.buildNodeJson(
      node("n3", "N3").copy(description = Some("fresh desc"), task = Some("entry task")), now2)
    val keysWithAll = withAll.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    val keysLegacy = legacy.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    val keysFresh = freshNode.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    assertEquals(
      keysWithAll.contains("result"), false, "payload must NOT carry result (summary or full)")
    assert(!legacy.asObject.get.toString.contains("the long task"), "task full text must not leak (taskPreview bounded)")
    assertEquals(withAll.hcursor.get[Boolean]("hasResult").toOption, Some(true))
    assertEquals(withAll.hcursor.get[String]("description").toOption, Some("do the thing"))
    assertEquals(legacy.hcursor.get[Boolean]("hasResult").toOption, Some(true))
    assertEquals(legacy.hcursor.get[Option[String]]("description").toOption, Some(None), "legacy node description = null")
    assertEquals(legacy.hcursor.get[String]("taskPreview").toOption, Some("first line stays"), "legacy fallback = task first line")
    assert(!keysFresh.contains("hasResult"), "no-result node must not carry hasResult (conditional field)")
    assert(!keysFresh.contains("taskPreview"), "description-bearing node must not carry taskPreview")
    // 条件字段不漂移基集合：fresh（无结果）∪ legacy(+taskPreview+hasResult) ∪ withAll(+hasResult)
    val base = keysFresh
    assertEquals(keysLegacy - "taskPreview" - "hasResult", base, "legacy adds exactly taskPreview+hasResult")
    assertEquals(keysWithAll - "hasResult", base, "withAll adds exactly hasResult")
  }

end FlowMapResultFilesSpec
