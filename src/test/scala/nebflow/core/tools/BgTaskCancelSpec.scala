package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 后台任务生命周 P2 单任务取消批（设计稿 20261030 §4.1 · 验收①「三通道各自
 * 取消 N 条中 1 条、其余存活且通知到位」）—— [[BgTaskCancel]] spec。钉面：
 *
 *   - 1/N local：N 条真实在册任务杀指定 1 条——被杀者行出册/任务图/进程树，
 *     其余行存活且进程可验证存活；WS 终态帧 + 留存区 cancelled 到位；
 *   - remote 腿：bg-cancel 传输桩确认 ⇒ 行取走 + 帧 + 留存区，桩入参精确
 *     (deviceName, jobId)（1/N 判据）；传输桩 Left ⇒ 整体 Left、行保留；
 *   - 未知 jobId ⇒ Left（可读）；行先被取走（竞速）⇒ Left 零双注；
 *   - first-terminal-wins 与 BgTaskReaper 的协同锚在 BgTaskReaperSpec。
 *
 * 数据根经 PathUtil.setDataRoot 隔离；全部 bg 任务/会话 guarantee 收尾。
 */
class BgTaskCancelSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

  private def withDataRoot[A](body: IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val tmp = os.temp.dir()
    PathUtil.setDataRoot(tmp / "data")
    body.guarantee(IO { PathUtil.setDataRoot(prev); os.remove.all(tmp) }.attempt.void)

  test("1/N local：N 条在册杀 1 条——其余存活，帧/留存区/通知到位") {
    withDataRoot {
      val sid = "bgcancel-1n"
      for
        shell <- ShellSession.forSession(sid)
        frames <- Ref.of[IO, List[Json]](Nil)
        result <- (
          for
            // 三条真实后台任务（会话内任务图）
            _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some("n1"))
            _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some("n2"))
            _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some("n3"))
            // 三行在册（镜像 BashTool 注册面）
            _ <- BgTaskRegistry.register("n1", sid, "task-one", "local", rootSessionId = sid)
            _ <- BgTaskRegistry.register("n2", sid, "task-two", "local", rootSessionId = sid)
            _ <- BgTaskRegistry.register("n3", sid, "task-three", "local", rootSessionId = sid)
            r <- BgTaskCancel.cancelOne("n2", by = "spec", wsSend = Some(f => frames.update(_ :+ f)), agentRef = None)
            // 断言：Right + 其余存活
            _ <- IO {
              assert(r.isRight, s"cancelOne must succeed: $r")
              assert(r.toOption.get.contains("Cancelled background task n2"))
            }
            rows <- BgTaskRegistry.allActiveTasks
            _ <- IO(assertEquals(rows.map(_.jobId).sorted, List("n1", "n3"), "被杀者出册，其余存活"))
            h1 <- shell.getBackgroundJobHealth("n1")
            h3 <- shell.getBackgroundJobHealth("n3")
            h2 <- shell.getBackgroundJobHealth("n2")
            _ <- IO {
              assert(h1.exists(_.isAlive), "n1 进程必须存活")
              assert(h3.exists(_.isAlive), "n3 进程必须存活")
              assert(h2.isEmpty || !h2.exists(_.isAlive), "n2 必须已被杀/出任务图")
            }
            // 帧：恰一帧、taskId=n2、status=completed（既有取消帧语义）、权威分键
            fs <- frames.get
            _ <- IO {
              assertEquals(fs.size, 1, s"恰一帧：$fs")
              assertEquals(fs.head.hcursor.downField("taskId").as[String].toOption, Some("n2"))
              assertEquals(fs.head.hcursor.downField("status").as[String].toOption, Some("completed"))
              assertEquals(fs.head.hcursor.downField("rootSessionId").as[String].toOption, Some(sid))
            }
            // 留存区：n2 翻 cancelled
            stored <- BgTaskOutputStore.read("n2", 0L)
            _ <- IO(assert(stored.forall(_.status == "cancelled"), s"n2 留存区状态: ${stored.map(_.status)}"))
          yield ()
        ).guarantee(
          shell.cancelBackgroundJob("n1").attempt.void *>
            shell.cancelBackgroundJob("n3").attempt.void *>
            List("n1", "n2", "n3").traverse_(j => BgTaskRegistry.unregister(j).attempt.void) *>
            ShellSession.destroySession(sid).attempt.void
        )
      yield result
    }
  }

  test("remote 腿：bg-cancel 传输确认 ⇒ 行取走 + 帧 + 留存区；桩入参精确 (device, jobId)") {
    withDataRoot {
      for
        frames <- Ref.of[IO, List[Json]](Nil)
        calls <- Ref.of[IO, List[(String, String)]](Nil)
        _ <- BgTaskRegistry.register("r1", "sid-r", "remote task", "remote", rootSessionId = "sid-r", deviceName = "KAI")
        stub = (device: String, jobId: String) =>
          calls.update(_ :+ ((device, jobId))).as(Right("local wait interrupted; peer confirmed: cancelled"))
        r <- BgTaskCancel.cancelOne("r1", by = "spec", wsSend = Some(f => frames.update(_ :+ f)), agentRef = None, remoteCancel = stub)
        _ <- IO(assert(r.isRight, s"remote cancel must land locally: $r"))
        cs <- calls.get
        _ <- IO(assertEquals(cs, List(("KAI", "r1")), "传输桩必须收到精确的 (deviceName, jobId)"))
        rows <- BgTaskRegistry.allActiveTasks
        _ <- IO(assertEquals(rows, Nil, "行取走"))
        fs <- frames.get
        _ <- IO {
          assertEquals(fs.size, 1)
          assertEquals(fs.head.hcursor.downField("taskId").as[String].toOption, Some("r1"))
          assertEquals(fs.head.hcursor.downField("status").as[String].toOption, Some("completed"))
        }
        stored <- BgTaskOutputStore.read("r1", 0L)
        _ <- IO(assert(stored.forall(_.status == "cancelled")))
      yield ()
    }
  }

  test("remote 腿：传输 Left ⇒ 整体 Left 且行保留（未落终态）") {
    withDataRoot {
      for
        _ <- BgTaskRegistry.register("r2", "sid-r2", "remote task", "remote", rootSessionId = "sid-r2", deviceName = "KAI")
        stub = (_: String, _: String) => IO.pure(Left("device offline"))
        r <- BgTaskCancel.cancelOne("r2", by = "spec", wsSend = None, agentRef = None, remoteCancel = stub)
        _ <- IO {
          assert(r.isLeft, "传输失败 ⇒ 未落终态")
          assert(r.left.toOption.get.contains("device offline"))
        }
        rows <- BgTaskRegistry.allActiveTasks
        _ <- IO(assertEquals(rows.map(_.jobId), List("r2"), "行保留——可重试"))
        _ <- BgTaskRegistry.unregister("r2")
      yield ()
    }
  }

  test("未知 jobId ⇒ Left（可读）；竞速已终态 ⇒ Left 零双注") {
    withDataRoot {
      for
        r1 <- BgTaskCancel.cancelOne("nope", by = "spec", wsSend = None, agentRef = None)
        _ <- IO(assert(r1.isLeft))
        _ <- BgTaskRegistry.register("race-1", "sid-x", "d", "local")
        _ <- BgTaskRegistry.unregisterReturning("race-1") // 真完成先取走
        frames <- Ref.of[IO, List[Json]](Nil)
        r2 <- BgTaskCancel.cancelOne("race-1", by = "spec", wsSend = Some(f => frames.update(_ :+ f)), agentRef = None)
        _ <- IO {
          assert(r2.isLeft, "竞速已终态 ⇒ 如实回 Left")
          // 行已不在册 ⇒ find 面如实报「not found or already completed」；
          // 行取走竞速的 Left 分支（concurrently）语义等价：终态不归本调用。
          assert(
            r2.left.toOption.get.contains("not found or already completed") ||
              r2.left.toOption.get.contains("concurrently"),
            r2.left.toOption.get
          )
        }
        fs <- frames.get
        _ <- IO(assertEquals(fs, Nil, "竞速输家不发帧"))
      yield ()
    }
  }

end BgTaskCancelSpec
