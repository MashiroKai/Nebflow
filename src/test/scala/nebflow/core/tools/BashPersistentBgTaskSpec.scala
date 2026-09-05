package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import io.circe.Json

import scala.concurrent.duration.*

/**
 * 节点完成闸批（bgtask-completion-gate，作者 2026-09-05 18:29 裁定）——
 * persistent 服务型后台豁免 + 闸 registry API 钉子。
 *
 * - P1 persistent=true：服务型（长驻 server）豁免 B1 idle 杀与 B2 硬超时+
 *   停滞杀——注入小阈值后 sleep 仍存活（对照组 P2 同参数被杀，红绿对照）
 * - P2 非 persistent（默认等待型）：同参数 sleep 在硬超时+停滞窗口后照常
 *   被杀 + TimeoutException（B1/B2 既有语义零变化）
 * - P3 BashTool inputSchema 含 persistent 引导（工具描述同步）
 * - P4 waitingFor：自有任务（sessionId）+ 子代理任务（rootSessionId）命中，
 *   persistent 过滤不命中
 * - P5 markFailed/drainFailures：consume-on-read、跨会话不串
 *
 * 进程清理纪律（2026-09-05）：所有 bg 任务经 guarantee(cancel/destroy) 收尾，
 * sleep 进程绝不泄漏到测试外。
 */
class BashPersistentBgTaskSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

  /** 轮询 background_job_id 直到完成（消费即移除）。 */
  private def pollCompleted(shell: ShellSession, jobId: String, deadlineMs: Long): IO[Either[Throwable, ProcessResult]] =
    def loop: IO[Either[Throwable, ProcessResult]] =
      shell.getBackgroundResult(jobId).flatMap {
        case Some(res) => IO.pure(res)
        case None => IO.sleep(300.millis) *> loop
      }
    loop.timeout(deadlineMs.millis)

  test("P1: persistent background sleep survives injected hard timeout + stall window (server exemption)") {
    for
      shell <- ShellSession.forSession("bg-pers-1")
      // 异常路径销毁守卫（残留治理）：persistent sleep 不会被看护杀，测试尾必须显式清
      alive <- (for
        _ <- shell.executeBackground(
          "sleep 3600",
          jobIdOverride = Some("p1"),
          hardTimeoutMs = 3000,
          stuckWindowSec = 2,
          healthCheckIntervalSec = 1,
          persistent = true
        )
        // 等 > hardTimeout(3s) + stuckWindow(2s)：非 persistent 同参数已被杀
        _ <- IO.sleep(6500.millis)
        health <- shell.getBackgroundJobHealth("p1")
      yield health.exists(_.isAlive))
        .guarantee(
          shell.cancelBackgroundJob("p1").attempt.void *>
            ShellSession.destroySession("bg-pers-1").attempt.void
        )
      _ <- IO(assert(alive, "persistent (service-type) job must survive idle/hard-timeout/stall guards"))
    yield ()
  }

  test("P2: non-persistent (waiting-type) same params still killed by guard — existing semantics unchanged") {
    for
      shell <- ShellSession.forSession("bg-pers-2")
      res <- (shell.executeBackground(
          "sleep 3600",
          jobIdOverride = Some("p2"),
          hardTimeoutMs = 3000,
          stuckWindowSec = 2,
          healthCheckIntervalSec = 1
        ) *> pollCompleted(shell, "p2", 20000))
        .guarantee(ShellSession.destroySession("bg-pers-2").attempt.void)
      _ <- IO(res match
        case Left(e: scala.concurrent.TimeoutException) => ()
        case Left(e) => fail(s"expected TimeoutException, got: ${e.getClass.getSimpleName}")
        case Right(r) => fail(s"waiting-type job must be guard-killed, got: ${r.stdout.take(80)}"))
    yield ()
  }

  test("P3: BashTool inputSchema carries persistent guidance") {
    val props = BashTool.inputSchema("properties").flatMap(_.asObject).map(_.toMap)
    assert(props.exists(_.contains("persistent")), "persistent property must exist in the Bash input schema")
    val desc = props.flatMap(_.get("persistent")).flatMap(_.hcursor.get[String]("description").toOption)
    assert(desc.exists(_.toLowerCase.contains("service")), "persistent description must explain service-type semantics")
  }

  test("P4: waitingFor matches own + root-scoped tasks, filters persistent") {
    val (own, child, pers) = ("p4-own", "p4-child", "p4-pers")
    val sid = "node-p4session"
    val io =
      BgTaskRegistry.register(own, sid, "own task", "local", "nebula-root") *>
        BgTaskRegistry.register(child, "delegate-p4x", "child task", "remote", sid) *>
        BgTaskRegistry.register(pers, sid, "server task", "local", "nebula-root", persistent = true) *>
        BgTaskRegistry.waitingFor(sid).flatMap { waiting =>
          IO {
            val ids = waiting.map(_.jobId)
            assert(ids.contains(own), s"own task must be waited on: $ids")
            assert(ids.contains(child), s"root-scoped sub-agent task must be waited on: $ids")
            assert(!ids.contains(pers), s"persistent task must be filtered: $ids")
          }
        } *> BgTaskRegistry.unregister(own) *> BgTaskRegistry.unregister(child) *> BgTaskRegistry.unregister(pers)
    io.unsafeRunSync()
  }

  test("P5: markFailed/drainFailures consume-on-read, session-scoped, persists until drained") {
    val (jid, sid, other) = ("p5-job", "node-p5session", "node-p5other")
    val io =
      BgTaskRegistry.markFailed(jid, sid, "nebula-root", "task p5", "killed by stall guard (auto-stopped)") *>
        BgTaskRegistry.drainFailures(other).map { miss =>
          assertEquals(miss, Nil, "other session must not see the failure")
        } *>
        BgTaskRegistry.drainFailures(sid).map { hit =>
          assertEquals(hit.map(_.jobId), List(jid), "own session drains the failure")
          assert(hit.headOption.exists(_.cause.contains("stall guard")))
        } *>
        BgTaskRegistry.drainFailures(sid).map { again =>
          assertEquals(again, Nil, "consume-on-read: second drain is empty")
        }
    io.unsafeRunSync()
  }
end BashPersistentBgTaskSpec
