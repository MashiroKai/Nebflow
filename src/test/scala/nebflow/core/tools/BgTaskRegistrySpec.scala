package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.FunSuite

/**
 * 2026-09-05 后台任务「计数>0 但点进去为空」修复——权威分键钉子。
 *
 * 契约：BgTaskRegistry.activeTasksJson 按 rootSessionId 分组（空则回退 sessionId），
 * 与 backgroundTaskUpdate 实时信封的 rootSessionId 字段同键——前端删除
 * bgTaskRootFor 启发式逆向分键后，快照与实时两条路径的桶键必须一致，
 * 否则快照对账会把实时路径归对桶的任务误删/漏删。
 *
 * 用唯一 jobId + 结尾 unregister，避免污染并行 spec 共享的全局 registry。
 */
class BgTaskRegistrySpec extends FunSuite:

  private def cleanup(jobIds: String*): IO[Unit] =
    jobIds.traverse_(BgTaskRegistry.unregister)

  private def tasksFor(root: String, json: Json): List[Json] =
    json.asObject
      .flatMap(_.apply(root))
      .flatMap(_.asArray)
      .map(_.toList)
      .getOrElse(Nil)

  test("activeTasksJson groups sub-agent task under rootSessionId, not executor sessionId") {
    val (job, exec, root) = ("bgtaskspec-root-1", "delegate-bgtaskspec-1", "main-bgtaskspec-1")
    val io =
      BgTaskRegistry.register(job, exec, "spec task", "local", root) *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            assert(clue(tasksFor(root, json)).exists(_.hcursor.get[String]("taskId").toOption.contains(job)))
            assertEquals(clue(tasksFor(exec, json)), List.empty[Json])
          }
        } *> cleanup(job)
    io.unsafeRunSync()
  }

  test("activeTasksJson falls back to executor sessionId when rootSessionId is empty") {
    val (job, exec) = ("bgtaskspec-fallback-1", "rest-bgtaskspec-1")
    val io =
      BgTaskRegistry.register(job, exec, "spec task no root", "remote") *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            assert(clue(tasksFor(exec, json)).exists(_.hcursor.get[String]("taskId").toOption.contains(job)))
          }
        } *> cleanup(job)
    io.unsafeRunSync()
  }

  test("unregister removes the task from the snapshot (no ghost entries)") {
    val (job, root) = ("bgtaskspec-cleanup-1", "main-bgtaskspec-3")
    val io =
      BgTaskRegistry.register(job, root, "spec task", "local", root) *>
        cleanup(job) *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            assert(!clue(tasksFor(root, json)).exists(_.hcursor.get[String]("taskId").toOption.contains(job)))
          }
        }
    io.unsafeRunSync()
  }

  // ── 2026-09-07 后台任务面板重设计：来源标注（作者指令②）契约钉 ──

  test("originFor derives category+label from registering session id prefix") {
    assertEquals(BgTaskRegistry.originFor("node-ab12cd34", Some("实施-某节点")), ("node", "实施-某节点"))
    assertEquals(BgTaskRegistry.originFor("node-ab12cd34", None), ("node", "node-ab12cd34")) // 缺名兜底会话 id
    assertEquals(BgTaskRegistry.originFor("dispatcher-ef56gh78", Some("dispatcher/nebflow")), ("dispatcher", "dispatcher/nebflow"))
    assertEquals(BgTaskRegistry.originFor("dispatcher-ef56gh78", None), ("dispatcher", "dispatcher"))
    assertEquals(BgTaskRegistry.originFor("main-session-1", Some("Nebula")), ("nebula", "Nebula"))
    assertEquals(BgTaskRegistry.originFor("", None), ("nebula", "Nebula")) // REST 直调空会话
  }

  test("activeTasksJson carries origin/originLabel/kind for the origin chip (snapshot path)") {
    val (job, exec, root) = ("bgtaskspec-origin-1", "node-bgtaskspec-1", "main-bgtaskspec-4")
    val io =
      BgTaskRegistry.register(job, exec, "spec task origin", "remote", root, false, "node", "实施-某节点") *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            val t = clue(tasksFor(root, json)).find(_.hcursor.get[String]("taskId").toOption.contains(job)).get
            assertEquals(t.hcursor.get[String]("origin").toOption, Some("node"))
            assertEquals(t.hcursor.get[String]("originLabel").toOption, Some("实施-某节点"))
            assertEquals(t.hcursor.get[String]("kind").toOption, Some("remote"))
          }
        } *> cleanup(job)
    io.unsafeRunSync()
  }

  test("register defaults keep backward compatibility (origin=nebula, empty label)") {
    val (job, root) = ("bgtaskspec-origin-compat-1", "main-bgtaskspec-5")
    val io =
      BgTaskRegistry.register(job, root, "spec task compat", "local", root) *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            val t = clue(tasksFor(root, json)).find(_.hcursor.get[String]("taskId").toOption.contains(job)).get
            assertEquals(t.hcursor.get[String]("origin").toOption, Some("nebula"))
            assertEquals(t.hcursor.get[String]("originLabel").toOption, Some(""))
            assertEquals(t.hcursor.get[String]("kind").toOption, Some("local"))
          }
        } *> cleanup(job)
    io.unsafeRunSync()
  }
end BgTaskRegistrySpec
