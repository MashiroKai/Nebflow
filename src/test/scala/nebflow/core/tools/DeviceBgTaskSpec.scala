package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

import scala.concurrent.duration.*

/**
 * 多设备后台任务可见批（作者令 2026-10-25「这里有个问题就是多设备的后台任务
 * 我们也要可见」）——设备通道后台任务（remoteable 六件带 `device` + 显式
 * `run_in_background`）进统一账本（BgTaskRegistry）的契约钉。
 *
 * 五条需求的钉位分工（本 spec 钉本批拥有的面；同点消费面归各自批的 spec）：
 *   - ①注册/⑤前端 …… 本 spec：`deviceOrigin` 单点 + activeTasksJson 快照路径
 *     设备 origin 断言（前端 chip 渲染由 tests/bg-device-origin-chip.spec.mjs 钉）。
 *   - ②完成通知负载（exit chip/耗时段/尾段/截断标注）…… #823 批
 *     （BgNotifyPayloadSpec）：设备腿与本地腿同一组装单点
 *     （RemoteExecutor.notifyRemoteBgResult 同点消费），本 spec 不重复钉。
 *   - ③持久账本（先落账再投递/idle 补投/bg-wait cap 孤儿登记）…… #824 批
 *     （BgTaskLedgerSpec/BgLedgerRedeliverySpec/BgWaitCapLedgerSpec）：设备腿
 *     同点消费 recordCompletion，本 spec 不重复钉。
 *   - ④persistent 豁免 …… 本 spec：等待集三消费面（waitingFor/waitingTasks/
 *     waitingBySession）过滤断言 + HTTP 等待豁免判据（bgHttpWaitTimeout）。
 *
 * 用唯一 jobId + 结尾 unregister（BgTaskRegistrySpec 同款纪律，防污染并行 spec
 * 共享的全局 registry）。
 */
class DeviceBgTaskSpec extends FunSuite:

  private def cleanup(jobIds: String*): IO[Unit] =
    jobIds.traverse_(BgTaskRegistry.unregister)

  private def tasksFor(root: String, json: Json): List[Json] =
    json.asObject
      .flatMap(_.apply(root))
      .flatMap(_.asArray)
      .map(_.toList)
      .getOrElse(Nil)

  // ── ① 设备 origin 类别：单点构造 + 快照路径 ─────────────────────────────

  test("deviceOrigin is the single construction point: category=device, label=device name verbatim") {
    assertEquals(BgTaskRegistry.deviceOrigin("KAI"), ("device", "KAI"))
    // 大小写保留（前端 chip 直接可辨；设备名匹配另有 resolvePeer 的 equalsIgnoreCase 层）。
    assertEquals(BgTaskRegistry.deviceOrigin("DESKTOP-IJBBEOT"), ("device", "DESKTOP-IJBBEOT"))
    // 与会话来源轴（originFor）互不相扰：设备任务的 chip 不再借用「谁开启」类别。
    assert(clue(BgTaskRegistry.originFor("main-1", Some("Nebula"))) != BgTaskRegistry.deviceOrigin("KAI"))
  }

  test("activeTasksJson carries the device origin on the snapshot path (refresh recovery)") {
    val (job, exec, root) = ("devicebgspec-origin-1", "delegate-devicebgspec-1", "main-devicebgspec-1")
    val io =
      BgTaskRegistry.register(
        job,
        exec,
        "[KAI] sbt full test",
        "remote",
        root,
        persistent = false,
        origin = "device",
        originLabel = "KAI"
      ) *>
        BgTaskRegistry.activeTasksJson.flatMap { json =>
          IO {
            val t = clue(tasksFor(root, json))
              .find(_.hcursor.get[String]("taskId").toOption.contains(job))
              .get
            assertEquals(t.hcursor.get[String]("origin").toOption, Some("device"))
            assertEquals(t.hcursor.get[String]("originLabel").toOption, Some("KAI"))
            assertEquals(t.hcursor.get[String]("kind").toOption, Some("remote"))
          }
        } *> cleanup(job)
    io.unsafeRunSync()
  }

  // ── ④ persistent（服务型）设备任务：等待集豁免（三消费面同判）────────────

  test("persistent device task is exempt from the wait set (node gate / hot-restart quiesce / bg-wait)") {
    val sid = s"delegate-devicebgspec-p-${System.nanoTime().toString.take(8)}"
    val (jSrv, jWait) = ("devicebgspec-wait-srv", "devicebgspec-wait-normal")
    val io =
      // 同会话两个设备任务：persistent dev server + 等待型构建。
      BgTaskRegistry.register(
        jSrv,
        sid,
        "[KAI] npm run dev",
        "remote",
        sid,
        persistent = true,
        origin = "device",
        originLabel = "KAI"
      ) *>
        BgTaskRegistry.register(
          jWait,
          sid,
          "[KAI] sbt full test",
          "remote",
          sid,
          persistent = false,
          origin = "device",
          originLabel = "KAI"
        ) *>
        BgTaskRegistry.waitingFor(sid).flatMap { waiting =>
          IO {
            // 节点完成闸 / bg-wait 屏障判据：只等等待型，服务型不阻塞会话终结。
            assertEquals(clue(waiting.map(_.jobId)), List(jWait))
          }
        } *>
        BgTaskRegistry.waitingTasks.flatMap { quiesce =>
          IO(assert(!clue(quiesce.map(_.jobId)).contains(jSrv), "热重启静默闸同样豁免 persistent"))
        } *>
        BgTaskRegistry.waitingBySession.flatMap { m =>
          IO {
            // #752 空闲细分数据源：空闲「在等什么」不含服务型。
            assert(!clue(m.getOrElse(sid, Nil).map(_.jobId)).contains(jSrv))
          }
        } *> cleanup(jSrv, jWait)
    io.unsafeRunSync()
  }

  test("persistent flag round-trips through the registry row (ActiveTask.persistent readable)") {
    val sid = s"delegate-devicebgspec-r-${System.nanoTime().toString.take(8)}"
    val job = "devicebgspec-roundtrip"
    val io =
      BgTaskRegistry.register(
        job,
        sid,
        "[KAI] npm run dev",
        "remote",
        sid,
        persistent = true,
        origin = "device",
        originLabel = "KAI"
      ) *>
        BgTaskRegistry.tasksOf(sid).flatMap { rows =>
          IO {
            val row = clue(rows.find(_.jobId == job)).get
            assert(row.persistent, "persistent=true 必须落在登记行（三消费面的判据源）")
            assertEquals(row.origin, "device")
            assertEquals(row.originLabel, "KAI")
          }
        } *> cleanup(job)
    io.unsafeRunSync()
  }

  // ── ④ persistent 豁免：HTTP 等待判据（远程腿唯一的超时杀面）────────────

  test("bgHttpWaitTimeout: waiting-type keeps the 1h network guard, persistent gets the long档") {
    // 等待型 = 现状零漂移（3600s 网络兜底）。
    assertEquals(RemoteExecutor.bgHttpWaitTimeout(persistent = false), RemoteExecutor.BgTimeout)
    assertEquals(RemoteExecutor.BgTimeout, 3600.seconds)
    // persistent = 豁免长档：严格长于等待型（服务型长驻不被 1h 网络兜底杀）。
    val persistentWait = RemoteExecutor.bgHttpWaitTimeout(persistent = true)
    assertEquals(persistentWait, RemoteExecutor.PersistentBgHttpTimeout)
    assert(clue(persistentWait > RemoteExecutor.BgTimeout), "persistent 档必须严格长于 1h 网络兜底")
  }

  // ── 本侧生命周期旗标：解析与摘除（不过线）────────────────────────────────

  test("parsePersistent: default false (waiting-type); explicit true honored") {
    val base = JsonObject.empty
    assert(!RemoteExecutor.parsePersistent(base), "缺省 = 等待型（与本地 BashTool 同判）")
    assert(RemoteExecutor.parsePersistent(base.add("persistent", true.asJson)))
    assert(!RemoteExecutor.parsePersistent(base.add("persistent", false.asJson)))
  }

  test("stripLocalBgFlags removes run_in_background AND persistent, keeps the rest (flags never cross the wire)") {
    val params = JsonObject.empty
      .add("command", "npm run dev".asJson)
      .add("description", "dev server".asJson)
      .add("run_in_background", true.asJson)
      .add("persistent", true.asJson)
    val stripped = RemoteExecutor.stripLocalBgFlags(params)
    assert(clue(stripped("run_in_background")).isEmpty, "run_in_background 不过线")
    assert(clue(stripped("persistent")).isEmpty, "persistent 不过线（本侧登记/豁免记账）")
    assertEquals(stripped("command").flatMap(_.asString), Some("npm run dev"))
    assertEquals(stripped("description").flatMap(_.asString), Some("dev server"))
  }
end DeviceBgTaskSpec
