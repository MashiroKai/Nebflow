package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * #1193（death-watch 归属缺陷批 · 2026-10-31）—— 后台任务归属随**会话**生命周
 * 期：回合结束（idle 等结果）不转移不死亡；会话真死（终态）才收殓。
 *
 * == 实证背景（液态玻璃批，作者目睹，一小时内连埋两个 914 项全量回归）==
 *
 *  job 83907ab5 跑到 816/914：delegate 回合 10:03 结束 idle 等结果 → ShellSession
 *  30min TTL（只看 Bash 调用活动）超时 → 10:45 下一回合首个 Bash 触发
 *  `doGetOrCreate` stale 分支 `replace`：`old.kill()` 杀掉仍在跑的回归进程树 +
 *  清空任务图，registry 行残留 → death-watch 下拍探针「会话活着但任务不在其
 *  任务图」⇒ Dead("not owned by session") ⇒ 收割记账，作者看到「owner lost」。
 *
 * == 本 spec 钉面 ==
 *
 *  ① 全链回归：起后台任务 → 回合 idle（TTL 回拨模拟）→ 下回合 forSession
 *    **会话实体原样续命**（同实例、任务图保留、进程仍活、death-watch 探针
 *    Alive、收割零动作）；
 *  ② 陈旧但**无**在飞工作 ⇒ 仍走既有 replace（旧回收语义不回归）；
 *  ③ 会话真终态（reclaimSession）⇒ 照常在飞清理（收口语义不回退）；
 *  ④ 显式取消（BashTool cancel 路径）⇒ 取走 registry 行 + 留存区翻转——
 *    death-watch 不再二次收割出「owner lost」假证（f25b9f1f 病灶），且
 *    回执文案契约逐字不变。
 *
 * 数据根经 PathUtil.setDataRoot 隔离；registry 为全局单例，用例自清行。
 */
class BgOwnershipSessionLifecycleSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private def withDataRoot[A](body: IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val tmp = os.temp.dir()
    PathUtil.setDataRoot(tmp / "data")
    body.guarantee(IO { PathUtil.setDataRoot(prev); os.remove.all(tmp) }.attempt.void)

  private def newSid(tag: String): String =
    s"own-$tag-${java.util.UUID.randomUUID().toString.take(8)}"

  /** 轮询直至满足（断言活性读数前必用——进程句柄落座有毫秒~秒级窗口）。 */
  private def pollUntil(cond: IO[Boolean], timeoutMs: Long = 15000L): IO[Unit] =
    def loop(deadline: Long): IO[Unit] =
      cond.flatMap { ok =>
        if ok then IO.unit
        else
          IO.realTime.map(_.toMillis).flatMap { now =>
            if now > deadline then IO.raiseError(new java.util.concurrent.TimeoutException("pollUntil 超时"))
            else IO.sleep(100.millis) *> loop(deadline)
          }
      }
    IO.realTime.map(_.toMillis).flatMap(t => loop(t + timeoutMs))

  test("① 全链：delegate 起后台任务 → 回合 idle 超 TTL → 下回合 forSession 会话续命，death-watch 不误埋") {
    withDataRoot {
      val sid  = newSid("renew")
      val job  = s"job-${java.util.UUID.randomUUID().toString.take(8)}"
      val prog = "sleep 45"
      (for
        s1 <- ShellSession.forSession(sid)
        _  <- s1.executeBackground(prog, description = Some("own-1193-long-regression"), jobIdOverride = Some(job))
        _  <- BgTaskRegistry.register(job, sid, "own-1193-long-regression", "local")
        _  <- pollUntil(s1.getBackgroundJobHealth(job).map(_.exists(_.isAlive)))
        // 回合 idle：零 Bash 调用，TTL 超时（回拨模拟 31 分钟）。
        _ <- s1.backdateLastAccessedForSpec(31.minutes.toMillis)
        stale <- s1.isStale
        live  <- s1.hasLiveBackgroundWork
        _ <- IO {
          assertEquals(stale, true, "回拨后必须判陈旧")
          assertEquals(live, true, "在飞任务 ⇒ 有在飞工作")
        }
        // 下回合第一个 Bash（forSession）= 事故触发点：必须**续约复用**而非 replace。
        s2 <- ShellSession.forSession(sid)
        s2Stale <- s2.isStale
        _ <- IO {
          assert(s2 eq s1, "有在飞后台工作 ⇒ 会话实体原样续命（同实例），不得 replace")
          assertEquals(s2Stale, false, "续命即 touch，陈旧判定解除")
        }
        // 任务图保留 + 进程仍活（「回合结束不转移不死亡」的行为面）。
        probe <- BgTaskLiveness.probe(
          BgTaskRegistry.ActiveTask(jobId = job, sessionId = sid, description = "d", startedAtMs = 0L, kind = "local")
        )
        _ <- IO(assertEquals(probe, BgTaskLiveness.Alive, "death-watch 探针必须判活"))
        health <- s2.getBackgroundJobHealth(job)
        _ <- IO(assert(health.exists(_.isAlive), s"进程必须仍存活: $health"))
        // death-watch 全表扫描：本行一行不收。
        report <- BgTaskReaper.reapDead(None, None)
        _ <- IO {
          assert(!report.reapedJobIds.contains(job), s"不得误埋: $report")
          assertEquals(report.remaining >= 1, true)
        }
        row <- BgTaskRegistry.find(job)
        _   <- IO(assert(row.isDefined, "registry 行必须原样在册"))
      yield ()).guarantee(
        ShellSession.destroySession(sid).attempt.void *>
          BgTaskRegistry.unregister(job).attempt.void
      )
    }
  }

  test("② 陈旧且**无**在飞工作 ⇒ 仍 replace（空会话回收语义不回归）") {
    withDataRoot {
      val sid = newSid("replace")
      (for
        s1  <- ShellSession.forSession(sid)
        job <- s1.executeBackground("echo own-1193-quick", description = Some("quick"))
        // 等任务终落并被消费（getBackgroundResult 完成为取走行）。
        _ <- pollUntil(
          s1.getBackgroundResult(job).map(_.isDefined),
          timeoutMs = 20000L
        )
        _ <- s1.backdateLastAccessedForSpec(31.minutes.toMillis)
        stale1 <- s1.isStale
        live1  <- s1.hasLiveBackgroundWork
        _ <- IO {
          assertEquals(stale1, true)
          assertEquals(live1, false, "终落并消费后 ⇒ 无在飞工作")
        }
        s2 <- ShellSession.forSession(sid)
        s2Stale <- s2.isStale
        _ <- IO {
          assert(!(s2 eq s1), "无在飞工作 ⇒ 陈旧会话照旧 replace（新实例）")
          assertEquals(s2Stale, false)
        }
        gone <- s2.getBackgroundResult(job)
        _    <- IO(assertEquals(gone, None, "新会话任务图为空"))
      yield ()).guarantee(ShellSession.destroySession(sid).attempt.void)
    }
  }

  test("③ 会话真终态 ⇒ reclaimSession 照常在飞清理（收口语义不回退）") {
    withDataRoot {
      val sid = newSid("reclaim")
      val job = s"job-${java.util.UUID.randomUUID().toString.take(8)}"
      (for
        s1 <- ShellSession.forSession(sid)
        _  <- s1.executeBackground("sleep 45", description = Some("own-1193-reclaim"), jobIdOverride = Some(job))
        _  <- BgTaskRegistry.register(job, sid, "own-1193-reclaim", "local")
        _  <- pollUntil(s1.getBackgroundJobHealth(job).map(_.exists(_.isAlive)))
        _  <- BgTaskRegistry.reclaimSession(Some(sid), _ => IO.unit, rootSessionId = "")
        peeked <- ShellSession.peekSession(sid)
        row    <- BgTaskRegistry.find(job)
        _ <- IO {
          assertEquals(peeked, None, "真终态收殓：会话出册且已死")
          assertEquals(row, None, "真终态收殓：在飞行一并注销")
        }
      yield ()).guarantee(
        ShellSession.destroySession(sid).attempt.void *>
          BgTaskRegistry.unregister(job).attempt.void
      )
    }
  }

  test("④ BashTool 显式取消 ⇒ 取走 registry 行（death-watch 零二次收割），回执文案契约不变") {
    withDataRoot {
      val sid = newSid("cancel")
      val ctx = ToolContext(projectRoot = "/tmp", sessionId = Some(sid))
      val startedRegex = "Job ID: (\\S+)".r
      def call(input: JsonObject): IO[Either[ToolError, String]] =
        BashTool.call(input, ctx)
      (for
        started <- call(
          JsonObject(
            "command" -> "sleep 45".asJson,
            "description" -> "own-1193-cancel-target".asJson,
            "run_in_background" -> true.asJson
          )
        )
        out <- IO.fromEither(started.left.map(e => new RuntimeException(e.message)))
        job = startedRegex.findFirstMatchIn(out).get.group(1)
        rowBefore <- BgTaskRegistry.find(job)
        _ <- IO(assert(rowBefore.isDefined, "后台任务在册（emitBgTaskStarted 已注册）"))
        // 模型显式取消（#1193 实证形态：cancel_background_job=true）。
        cancelled <- call(
          JsonObject(
            "background_job_id" -> job.asJson,
            "cancel_background_job" -> true.asJson,
            "description" -> "cancel".asJson
          )
        )
        text <- IO.fromEither(cancelled.left.map(e => new RuntimeException(e.message)))
        _ <- IO(
          assertEquals(text, s"[Background job cancelled] Job ID: $job", "回执文案契约逐字不变")
        )
        // 收口断言：行被取走 → death-watch 无物可收、账本零 owner-lost 假证。
        rowAfter <- BgTaskRegistry.find(job)
        _ <- IO(assertEquals(rowAfter, None, "#1193：显式取消必须取走 registry 行"))
        report <- BgTaskReaper.reapDead(None, None)
        _ <- IO(assert(!report.reapedJobIds.contains(job), "行已取走 ⇒ death-watch 不得二次收割"))
        ledger <- BgTaskLedger.pending(sid)
        _ <- IO(
          assert(!ledger.exists(_.jobId == job), "账本不得有该任务的 owner-lost completion 假证")
        )
        // 留存区翻转 cancelled（输出查看契约）。
        stored <- BgTaskOutputStore.read(job, 0L)
        _ <- IO(
          assert(stored.forall(_.status == "cancelled"), s"留存区应翻 cancelled: $stored")
        )
      yield ()).guarantee(ShellSession.destroySession(sid).attempt.void)
    }
  }
