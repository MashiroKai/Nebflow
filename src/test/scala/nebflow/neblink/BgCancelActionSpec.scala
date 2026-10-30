package nebflow.neblink

import cats.effect.{Fiber, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.tools.{RemoteExecCancelRegistry, ShellSession}

import scala.concurrent.duration.*

/**
 * 后台任务生命周 P2 单任务取消批 · 远端腿**对端面**—— [[BgCancelAction]] +
 * [[RemoteExecCancelRegistry]] spec（设计稿 §7-P2 验收②「远端取消 round-trip：
 * 确认帧 + 对端进程树真死」的接收端判定件）。
 *
 * 钉面：
 *   - 1/N：两个真实登记进程（ShellSession remote-exec 形态，execKey 过生产
 *     同款缝）杀其一——被杀者真死（进程退出 + 登记表出册），另一个可验证存活；
 *   - 确认帧：cancelled=true + aliveBeforeCancel=true + jobId 回显；
 *   - 幂等：同 jobId 二次取消 ⇒ Left 可读原因（不在册即唯一事实态）；
 *   - 未登记/已注销 jobId ⇒ Left（可读原因，host 侧据此如实回执）；
 *   - 缺 jobId 参数 ⇒ Left。
 *
 * 竞速纪律：spawn 后先 waitUntil 双登记就绪（登记表在 runProcess 内异步落笔），
 * 断言失败路径不裸奔；guarantee 走被测同一条路（登记表取走式杀 peer-2 + join），
 * 进程绝不泄漏到测试外。
 */
class BgCancelActionSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test("对端 1/N：两个登记进程杀其一——被杀真死、另一存活、确认帧齐备") {
    val sid = "bgcancel-action"
    def spawn(shell: ShellSession, key: String): IO[Fiber[IO, Throwable, nebflow.core.tools.ProcessResult]] =
      // remote-exec 同款缝：execKey 经 ShellSession.execute 登记进 RemoteExecCancelRegistry
      shell.execute("sleep 3600", 365.days, execKey = Some(key)).start
    val program =
      for
        shell <- ShellSession.forSession(sid)
        f1 <- spawn(shell, "peer-1")
        f2 <- spawn(shell, "peer-2")
        _ <- (
          for
            // 登记表就绪（runProcess 内异步落笔——先等双登记再断言）
            _ <- waitUntil(10.seconds)(
              (RemoteExecCancelRegistry.isLive("peer-1"), RemoteExecCancelRegistry.isLive("peer-2")).mapN(_ && _)
            )
            // 杀 peer-1
            r <- BgCancelAction.handle(JsonObject("jobId" -> "peer-1".asJson))
            _ <- IO {
              assert(r.isRight, s"cancel must confirm: $r")
              val j = r.toOption.get
              assertEquals(j.hcursor.downField("jobId").as[String].toOption, Some("peer-1"))
              assertEquals(j.hcursor.downField("cancelled").as[Boolean].toOption, Some(true))
              assertEquals(j.hcursor.downField("aliveBeforeCancel").as[Boolean].toOption, Some(true))
            }
            // 1/N 断言：peer-1 出册且进程真死；peer-2 存活
            live1b <- RemoteExecCancelRegistry.isLive("peer-1")
            live2b <- RemoteExecCancelRegistry.isLive("peer-2")
            _ <- IO {
              assert(!live1b, "peer-1 必须已出册")
              assert(live2b, "peer-2 必须存活（1/N 精确杀伤）")
            }
            // 幂等：二次取消 peer-1 ⇒ Left 可读原因（不在册即唯一事实态）
            r2 <- BgCancelAction.handle(JsonObject("jobId" -> "peer-1".asJson))
            _ <- IO {
              assert(r2.isLeft)
              assert(r2.left.toOption.get.contains("peer-1"))
            }
            // 被杀 fiber 收尾（杀树后 execute 自然返回）
            _ <- f1.join.void.attempt
          yield ()
        ).guarantee(
          // 收尾走**被测同一条路**（登记表取走式杀树）——fiber cancel  interrupt
          // 前台 execute 的 waitFor/read 线程在本 harness 不收敛（实测挂死），
          // 生产取消面也本来就不靠 fiber cancel（registry 杀树 ⇒ 自然返回）。
          BgCancelAction.handle(JsonObject("jobId" -> "peer-2".asJson)).attempt.void *>
            f1.join.void.attempt *> f2.join.void.attempt *>
            RemoteExecCancelRegistry.clear().attempt.void *>
            ShellSession.destroySession(sid).attempt.void
        )
      yield ()
    program
  }

  test("未登记 jobId ⇒ Left（可读原因）；缺 jobId ⇒ Left") {
    val program =
      for
        r1 <- BgCancelAction.handle(JsonObject("jobId" -> "never-registered".asJson))
        _ <- IO {
          assert(r1.isLeft)
          assert(r1.left.toOption.get.contains("never-registered"))
        }
        r2 <- BgCancelAction.handle(JsonObject.empty)
        _ <- IO {
          assert(r2.isLeft)
          assert(r2.left.toOption.get.contains("jobId"))
        }
      yield ()
    program.guarantee(RemoteExecCancelRegistry.clear().attempt.void)
  }

end BgCancelActionSpec
