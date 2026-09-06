package nebflow.core.project

import cats.effect.IO
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * Flow Map 存储瘦身批（2026-09-06）——task 全文出 JSON 契约（result 同款机制平移）。
 *
 * 契约（内存/投递仍用全文，落盘拆两半——A/B 硬契约路径 `tasks/<id>.md` 与存量迁移
 * 脚本 scripts/migrate-flowmap-task-slim.mjs 严格一致）：
 * - 新写入 flow-map.json 节点记录**不含 task 全文**：task 收敛为 ≤500 字符摘要 +
 *   `taskFile` 指针；description 等其余字段逐项保留（勿丢字段）
 * - task 全文持久化到 per-node 文件 `.nebflow/tasks/<nodeId>.md`（仅活动区写）
 * - 加载水合：内存 task 回读文件全文（buildInput/重入/NodeList detail 同源零改动）
 * - 存量污染自动迁移：无文件的 task 落文件 + JSON 收敛 + 首次迁移 .bak 备份（幂等不覆盖）
 * - 归档区 task 直接剥（新归档写入无 task/taskFile 键；无重入价值，不落 task 文件）
 * - 旧格式兼容：读含 task 字段的旧 JSON 不炸，按现状行为供 task
 * - 变异验红锚点：「persist split」用例——把 task 全文写回 JSON 的变异必红（A4）
 */
class FlowMapTaskFilesSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def freshWorkspace(): os.Path =
    os.temp.dir(prefix = "nb-fm-task-", deleteOnExit = false)

  private val now = System.currentTimeMillis()

  private def node(id: String, name: String, status: String = NodeLifecycle.Wiring): NodeDef =
    NodeDef(id = id, name = name, agent = "general", status = status, createdAt = now)

  private def longTask: String =
    ("任务段落五个字。" * 120) + "END-MARKER-TASK-FULL" // > 500 chars, tail 独有标记验证全文

  private def readJson(p: os.Path): io.circe.Json =
    jsonParse(os.read(p)) match
      case Right(j) => j
      case Left(e)  => fail(s"corrupt json at $p: $e")

  private def nodeObj(j: io.circe.Json, id: String): io.circe.Json =
    j.hcursor.downField("nodes").downField(id).focus
      .getOrElse(fail(s"node '$id' missing in json"))

  private def keysOf(n: io.circe.Json): Set[String] =
    n.asObject.map(_.keys.toSet).getOrElse(Set.empty)

  // ── 1. 落盘拆分（A4 断言锚点：新写入不含 task 全文 / 含 description）──

  test("persist split: JSON task = summary ≤500 + taskFile pointer; full text in tasks/<id>.md; description kept") {
    val ws = freshWorkspace()
    val full = longTask
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-big" -> node("n-big", "big").copy(
          description = Some("拆分验收节点"), task = Some(full),
          status = NodeLifecycle.Pending, ttlExpireAt = Some(now + 100000)))))
      raw <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      j = readJson(ws / ".nebflow" / "flow-map.json")
      nBig = nodeObj(j, "n-big")
      jsonTask <- IO.fromEither(nBig.hcursor.downField("task").as[String])
      pointer <- IO.fromEither(nBig.hcursor.downField("taskFile").as[String])
      desc <- IO.fromEither(nBig.hcursor.downField("description").as[String])
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "tasks" / "n-big.md"))
      snap <- store.snapshot
    yield
      assertEquals(jsonTask.length, 501, "JSON task must be take(500)+… (≤501 chars) — task full text must NOT leak into flow-map.json")
      assert(jsonTask.endsWith("…"), "summary must end with ellipsis")
      assert(!raw.contains("END-MARKER-TASK-FULL"), "raw JSON must not contain the full-text tail marker")
      assertEquals(pointer, "tasks/n-big.md")
      assertEquals(desc, "拆分验收节点", "description must be preserved verbatim in the record")
      assertEquals(fileFull, full, "per-node file must carry the FULL text (tail marker intact)")
      // 内存仍是全文（buildInput/重入同源）
      assertEquals(snap.nodes("n-big").task, Some(full))
      assert(os.exists(ws / ".nebflow" / "tasks"), "tasks dir must exist after split")
  }

  test("persist split: short task keeps full text in JSON (summary == full) + file materialized") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-small" -> node("n-small", "small").copy(task = Some("短任务原文")))))
      j = readJson(ws / ".nebflow" / "flow-map.json")
      nSmall = nodeObj(j, "n-small")
      jsonTask <- IO.fromEither(nSmall.hcursor.downField("task").as[String])
      pointer <- IO.fromEither(nSmall.hcursor.downField("taskFile").as[String])
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "tasks" / "n-small.md"))
    yield
      assertEquals(jsonTask, "短任务原文")
      assertEquals(pointer, "tasks/n-small.md")
      assertEquals(fileFull, "短任务原文")
  }

  // ── 2. 重开水合（重启后原文仍可得——buildInput/重入/detail 同源）──

  test("reopen with slim JSON hydrates full task (file authoritative)") {
    val ws = freshWorkspace()
    val full = longTask
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-h2" -> node("n-h2", "hydrate2", NodeLifecycle.Pending).copy(task = Some(full)))))
      reopened <- FlowMapStore.open("demo", ws.toString)
      snap <- reopened.snapshot
      fromArchive <- reopened.findNode("n-h2")
    yield
      assertEquals(snap.nodes("n-h2").task, Some(full), "reopened memory must carry FULL task (hydrated from file)")
      assertEquals(fromArchive.flatMap(_.task), Some(full))
  }

  // ── 3. 存量污染自动迁移（幂等 + .bak 备份 + 旧格式兼容）──

  test("legacy migration: inline full task → file materialized + JSON slimmed + .bak backup (idempotent)") {
    val ws = freshWorkspace()
    val full = longTask
    // 旧格式：节点记录携带 task 全文（= 升级前的存量形态； circe 解码容忍 = 兼容自证）
    val legacyJson = FlowMapState(project = "legacy", updatedAt = now, nodes = Map(
      "n-old" -> node("n-old", "old", NodeLifecycle.Pending).copy(task = Some(full))
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
      nOld = nodeObj(j, "n-old")
      jsonTask <- IO.fromEither(nOld.hcursor.downField("task").as[String])
      hasFile <- IO.blocking(os.exists(ws / ".nebflow" / "tasks" / "n-old.md"))
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "tasks" / "n-old.md"))
      bakExists <- IO.blocking(os.exists(ws / ".nebflow" / "flow-map.json.bak"))
      bakHasFull <- IO.blocking { val bak = os.read(ws / ".nebflow" / "flow-map.json.bak"); bak.contains(full.take(100)) }
    yield
      assertEquals(snap.nodes("n-old").task, Some(full), "migrated memory must hold full task (旧格式按现状行为供 task)")
      assertEquals(jsonTask.length, 501, "converged JSON must carry summary")
      assertEquals(hasFile, true)
      assertEquals(fileFull, full)
      assertEquals(bakExists, true, "first migration must back up the original JSON")
      assertEquals(bakHasFull, true, ".bak must contain the pre-migration (full-text) form")
  }

  test("legacy migration idempotent: second open does not overwrite .bak, task files stable") {
    val ws = freshWorkspace()
    val full = longTask
    val legacyJson = FlowMapState(project = "legacy2", updatedAt = now, nodes = Map(
      "n-old2" -> node("n-old2", "old2", NodeLifecycle.Pending).copy(task = Some(full))
    )).asJson.noSpaces
    for
      _ <- IO.blocking {
        os.makeDir.all(ws / ".nebflow")
        os.write.over(ws / ".nebflow" / "flow-map.json", legacyJson)
      }
      _ <- FlowMapStore.open("legacy2", ws.toString)
      bakMtime1 <- IO.blocking(os.mtime(ws / ".nebflow" / "flow-map.json.bak"))
      fileMtime1 <- IO.blocking(os.mtime(ws / ".nebflow" / "tasks" / "n-old2.md"))
      _ <- FlowMapStore.open("legacy2", ws.toString)
      bakMtime2 <- IO.blocking(os.mtime(ws / ".nebflow" / "flow-map.json.bak"))
      fileMtime2 <- IO.blocking(os.mtime(ws / ".nebflow" / "tasks" / "n-old2.md"))
    yield
      assertEquals(bakMtime1, bakMtime2, ".bak must never be overwritten by later opens")
      assertEquals(fileMtime1, fileMtime2, "task file must not be rewritten when content identical (idempotent)")
  }

  test("old-format compat: task field + unknown taskFile key tolerated, task served as-is") {
    val ws = freshWorkspace()
    // 旧格式 + 不认识的 taskFile 键（外部残留）+ 未知顶层键 → 解码容忍不炸
    val legacyJson = FlowMapState(project = "legacy3", updatedAt = now, nodes = Map(
      "n-old3" -> node("n-old3", "old3", NodeLifecycle.Pending).copy(task = Some("存量短任务"))
    )).asJson.noSpaces.replace("\"task\":\"存量短任务\"", "\"task\":\"存量短任务\",\"taskFile\":\"tasks/nowhere.md\"")
      .replace("\"nodes\":{", "\"legacyMarker\":1,\"nodes\":{")
    for
      _ <- IO.blocking {
        os.makeDir.all(ws / ".nebflow")
        os.write.over(ws / ".nebflow" / "flow-map.json", legacyJson)
      }
      store <- FlowMapStore.open("legacy3", ws.toString)
      snap <- store.snapshot
      fileFull <- IO.blocking(os.read(ws / ".nebflow" / "tasks" / "n-old3.md"))
    yield
      assertEquals(snap.nodes("n-old3").task, Some("存量短任务"), "old-format task must be served (materialized + hydrated)")
      assertEquals(fileFull, "存量短任务")
  }

  // ── 4. 归档区 task 剥除（无重入价值；result 摘要/指针不动）──

  test("archive strip: sweepExpired → archive JSON has no task/taskFile keys; result file semantics untouched") {
    val ws = freshWorkspace()
    val full = longTask
    for
      store <- FlowMapStore.open("demo-arch", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-done" -> node("n-done", "done", NodeLifecycle.Completed).copy(
          task = Some(full), result = Some("归档结果"), ttlExpireAt = Some(now - 1)))))
      removed <- store.sweepExpired(now)
      fromArchive <- store.findNode("n-done") // 同进程内存：task 未丢（读旧格式行为同源）
      archRaw <- IO.blocking(os.read(ws / ".nebflow" / "flow-map-archive.json"))
      archJson = readJson(ws / ".nebflow" / "flow-map-archive.json")
      nDone = nodeObj(archJson, "n-done")
      reopened <- FlowMapStore.open("demo-arch", ws.toString)
      archReopened <- reopened.archiveSnapshot
    yield
      assertEquals(removed, List("n-done"))
      assertEquals(fromArchive.flatMap(_.task), Some(full), "same-process archive readback keeps in-memory task")
      assertEquals(keysOf(nDone).contains("task"), false, "archive JSON must NOT carry task key (直接剥)")
      assertEquals(keysOf(nDone).contains("taskFile"), false, "archive JSON must NOT carry taskFile pointer")
      assert(!archRaw.contains("END-MARKER-TASK-FULL"), "archive raw JSON must not contain task full text")
      assertEquals(nDone.hcursor.get[String]("resultFile").toOption, Some("results/n-done.md"), "result pointer untouched")
      assertEquals(fromArchive.flatMap(_.result), Some("归档结果"))
      // 重开后：归档内存节点 task = None（磁盘已剥）；result 仍水合全文
      assertEquals(archReopened.nodes("n-done").task, None, "reopened archive node has no task (stripped at rest)")
      assertEquals(archReopened.nodes("n-done").result, Some("归档结果"))
  }

  // ── 5. taskFile 指针不进内存模型 + 空任务不落文件 ──────────

  test("no task → no tasks dir; taskFile key never appears in-memory (disk-only)") {
    val ws = freshWorkspace()
    for
      store <- FlowMapStore.open("demo", ws.toString)
      _ <- store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-notask" -> node("n-notask", "notask").copy(description = Some("只有描述")))))
      snap <- store.snapshot
      files <- IO.blocking(os.list(ws / ".nebflow").map(_.last).toList)
    yield
      assertEquals(snap.nodes("n-notask").task, None)
      assertEquals(files.contains("tasks"), false, "no task → tasks dir must not be created")
  }

end FlowMapTaskFilesSpec
