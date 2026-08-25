package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorContext, ActorSystem, Behavior}
import nebflow.agent.AgentCommand
import nebflow.shared.BashResilienceConfig

import scala.concurrent.duration.*

/**
 * #391 机制 A（2026-08-25 用户裁定「5 分钟自动转后台」）：前台命令运行超过
 * BashAutoBackgroundMs → 自动转后台——不杀进程、turn 释放（占位消息 + jobId）、
 * 完成时 makeNotifyCallback 异步通知（ExternalEvent + WS + BgTaskRegistry）。
 * 转后台后与显式 run_in_background 同构：background_job_id 查询拿到真实结果。
 *
 * 验收（设计文档 §2.6）：
 * - A-1 转后台占位消息 + 进程不杀 + 完成通知
 * - A-2 完成 → ExternalEvent(completed) + WS backgroundTaskUpdate(completed) + 查询真实结果
 * - A-3 显式 timeout < 阈值 → 显式 timeout 杀（不转后台）
 * - A-4 显式 run_in_background=true → 直接后台（不经 auto-background）
 * - A-5 isRemoteExec=true → 同步返回（不转后台）
 * - A-6 阈值可配（bashConfig 注入生效）
 */
class BashAutoBackgroundSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

  /** 3s 注入阈值——让 5s 命令在可接受的测试时长内触发转后台。 */
  private val fastCfg = BashResilienceConfig(autoBackgroundMs = 3000)

  /** 捕获 ExternalEvent 的 actor——makeNotifyCallback 的 ref ! ExternalEvent 落进 Ref。 */
  private class CaptureBehavior(events: Ref[IO, List[AgentCommand]]) extends Behavior[AgentCommand]:
    def receive(ctx: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
      events.update(_ :+ msg).as(this)

  private def extractJobId(msg: String): String =
    """Job ID: ([0-9a-f]{8})""".r.findFirstMatchIn(msg).map(_.group(1)).getOrElse(fail(s"no job id in: $msg"))

  /** 轮询 background_job_id 直到 completed（消费即移除），返回真实结果。 */
  private def pollCompleted(shell: ShellSession, jobId: String, deadlineMs: Long = 30000): IO[Either[Throwable, ProcessResult]] =
    def loop: IO[Either[Throwable, ProcessResult]] =
      shell.getBackgroundResult(jobId).flatMap {
        case Some(res) => IO.pure(res)
        case None => IO.sleep(300.millis) *> loop
      }
    loop.timeout(deadlineMs.millis)

  private def mkCtx(
    events: Ref[IO, List[AgentCommand]],
    wsEvents: Ref[IO, List[io.circe.Json]],
    actorRef: Option[nebflow.actor.ActorRef[AgentCommand]] = None
  ): ToolContext =
    ToolContext(
      projectRoot = "/tmp",
      sessionId = Some("auto-bg-session"),
      agentActorRef = actorRef,
      wsSend = Some(j => wsEvents.update(_ :+ j)),
      bashConfig = fastCfg
    )

  test("A-1+A-2+A-6: sleep 5 with 3s injected threshold → placeholder + async completion (query real result, WS, ExternalEvent)") {
    for
      system <- IO(ActorSystem("auto-bg-test"))
      events <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(new CaptureBehavior(events), "capture")
      wsEvents <- Ref.of[IO, List[io.circe.Json]](Nil)
      input = JsonObject(
        "command" -> "sleep 5 && echo bg-done".asJson,
        "description" -> "auto-bg".asJson
      )
      start <- IO(System.currentTimeMillis())
      result <- BashTool.call(input, mkCtx(events, wsEvents, Some(ref)))
      elapsed = System.currentTimeMillis() - start
      _ <- IO {
        val msg = result.fold(e => fail(s"expected placeholder, got error: ${e.message}"), identity)
        assert(msg.startsWith("[Command moved to background]"), s"placeholder: $msg")
        assert(msg.contains("Job ID:"), s"job id in placeholder: $msg")
        assert(elapsed >= 2500 && elapsed < 8000, s"should release turn ~3s, took $elapsed ms")
      }
      shell <- ShellSession.forSession("auto-bg-session")
      jobId = extractJobId(result.toOption.get)
      // 转后台后命令继续跑：查询应显示 running（进程不杀）
      _ <- IO.sleep(500.millis)
      health <- shell.getBackgroundJobHealth(jobId)
      _ <- IO(assert(health.exists(_.isAlive), s"process must keep running after auto-background: $health"))
      // 轮询完成——background_job_id 查询拿到真实结果（watcher 链路 + 真实结果传递）
      res <- pollCompleted(shell, jobId)
      _ <- IO {
        assert(res.isRight, s"should complete with real output: $res")
        assert(res.toOption.get.stdout.contains("bg-done"), s"real stdout: $res")
      }
      // WS 收到 running→completed
      wsList <- wsEvents.get
      _ <- IO {
        val statuses = wsList.flatMap(_.hcursor.get[String]("status").toOption)
        assert(statuses.contains("running"), s"ws running event: $statuses")
        assert(statuses.contains("completed"), s"ws completed event: $statuses")
      }
      // agent 收到 ExternalEvent(completed)
      evs <- events.get
      _ <- IO {
        assert(
          evs.exists {
            case AgentCommand.ExternalEvent(source, eventType, _, _, _) =>
              source == "background-task" && eventType == "completed"
            case _ => false
          },
          s"ExternalEvent(completed) received: $evs"
        )
      }
      // BgTaskRegistry 注销
      active <- BgTaskRegistry.activeTasksJson
      _ <- IO(assert(!active.noSpaces.contains(jobId), s"bg task should be unregistered: $active"))
      _ <- system.stopAll
      _ <- ShellSession.destroySession("auto-bg-session")
    yield ()
  }

  test("A-3: explicit timeout < threshold wins — command killed, no auto-background") {
    for
      wsEvents <- Ref.of[IO, List[io.circe.Json]](Nil)
      events <- Ref.of[IO, List[AgentCommand]](Nil)
      input = JsonObject(
        "command" -> "sleep 10".asJson,
        "description" -> "timeout-wins".asJson,
        "timeout" -> 2000.asJson
      )
      result <- BashTool.call(input, mkCtx(events, wsEvents))
    yield
      result match
        case Left(ToolError(msg)) =>
          assert(msg.contains("timed out"), s"should report timeout: $msg")
          assert(!msg.contains("moved to background"), s"no auto-background: $msg")
        case Right(out) => fail(s"expected timeout error, got: $out")
  }

  test("A-4: explicit run_in_background=true bypasses auto-background") {
    for
      wsEvents <- Ref.of[IO, List[io.circe.Json]](Nil)
      events <- Ref.of[IO, List[AgentCommand]](Nil)
      input = JsonObject(
        "command" -> "sleep 1 && echo explicit-bg".asJson,
        "description" -> "explicit-bg".asJson,
        "run_in_background" -> true.asJson
      )
      result <- BashTool.call(input, mkCtx(events, wsEvents))
    yield
      result match
        case Right(out) =>
          assert(out.startsWith("[Background job started]"), s"explicit bg: $out")
          assert(!out.contains("moved to background"), s"no auto-background: $out")
        case Left(e) => fail(s"unexpected error: ${e.message}")
  }

  test("A-5: remote-exec stays synchronous (no auto-background)") {
    for
      wsEvents <- Ref.of[IO, List[io.circe.Json]](Nil)
      events <- Ref.of[IO, List[AgentCommand]](Nil)
      input = JsonObject(
        "command" -> "sleep 2 && echo remote-done".asJson,
        "description" -> "remote-exec".asJson
      )
      result <- BashTool.call(input, mkCtx(events, wsEvents).copy(isRemoteExec = true))
    yield
      result match
        case Right(out) =>
          assert(out.contains("remote-done"), s"real output: $out")
          assert(!out.contains("moved to background"), s"no auto-background: $out")
        case Left(e) => fail(s"unexpected error: ${e.message}")
  }

end BashAutoBackgroundSpec
