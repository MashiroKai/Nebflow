package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.{Defaults, PathUtil}

import scala.concurrent.duration.*

/**
 * death-watch 误报修复批（作者令 2026-10-31 14:54「修」）——根因调查
 * docs/20261031_owner-lost.md §4 修法清单六点逐条造例复现：
 *
 *  ① `process dead` + deferred 已落 ⇒ 判 Alive（5ae83e33 型 758ms 窗口假证豁免——
 *    本例真起进程瞬间崩（exit 3），等 deferred 落地后探针必须 Alive）；
 *  ② 幼龄行（行龄 < 10s）豁免判死，`owner session gone` 是唯一不豁免分支；
 *  ③ 引擎关停抑制旗：旗起期间 reapDead 不判死只告警（639c9d15 型关机窗误报）；
 *  ④ 任务图注册/移除结构化日志携实例 id + 原因（logback ListAppender 实证）；
 *  ⑤ 判死前输增长二次确认：近 5s 输出有增长 ⇒ 跳过一拍（887c8244 型活任务
 *    不误埋）；确认窗置 0 的对照例：同样状态照常收割（零永久免疫）；
 *  ⑥ 句柄未落豁免加 5min 期限：adopt 任务句柄永不再落形态，逾龄 ⇒
 *    `Dead("handle never landed")` 降级普通判死路径（46a86d3f 型僵尸免疫收敛）。
 *
 * 数据根经 PathUtil.setDataRoot 隔离；registry/reaper 旗为全局单例，用例自清。
 */
class BgDeathWatchFalsePositiveFixSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private def withDataRoot[A](body: IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val tmp = os.temp.dir()
    PathUtil.setDataRoot(tmp / "data")
    body.guarantee(IO { PathUtil.setDataRoot(prev); os.remove.all(tmp) }.attempt.void)

  private def newSid(tag: String): String =
    s"dw-$tag-${java.util.UUID.randomUUID().toString.take(8)}"

  /** 轮询直至满足（进程句柄/deferred 落座有毫秒~秒级窗口）。 */
  private def pollUntil(cond: IO[Boolean], timeoutMs: Long = 15000L): IO[Unit] =
    def loop(deadline: Long): IO[Unit] =
      cond.flatMap { ok =>
        if ok then IO.unit
        else
          IO.realTime.map(_.toMillis).flatMap { now =>
            if now > deadline then IO.raiseError(new java.util.concurrent.TimeoutException("pollUntil 超时"))
            else IO.sleep(20.millis) *> loop(deadline)
          }
      }
    IO.realTime.map(_.toMillis).flatMap(t => loop(t + timeoutMs))

  private def row(job: String, sid: String, ageMs: Long): BgTaskRegistry.ActiveTask =
    BgTaskRegistry.ActiveTask(
      jobId = job,
      sessionId = sid,
      description = s"desc-$job",
      startedAtMs = System.currentTimeMillis() - ageMs,
      kind = "local"
    )

  // ── ① done 者永不判死 ────────────────────────────────────────────────

  test("① 进程死 + deferred 已落 ⇒ probe 判 Alive（5ae83e33 型 758ms 窗口豁免）") {
    withDataRoot {
      val sid = newSid("done")
      (for
        s1  <- ShellSession.forSession(sid)
        job <- s1.executeBackground("exit 3", description = Some("dw-①-instant-crash"))
        // 等 deferred 落地（进程早已死）——正是旧代码会判 Dead("process dead") 的状态：
        _ <- pollUntil(
          s1.probeJobLiveness(job).map(_.exists(p => p.done && !p.alive)),
          timeoutMs = 15000L
        )
        raw <- s1.probeJobLiveness(job)
        _ <- IO {
          assert(raw.exists(_.done), "precondition: deferred 已落")
          assert(raw.exists(p => !p.alive), "precondition: 进程可验证已死（旧判据的 Dead 依据）")
        }
        // 老龄行（绕过幼龄豁免 ②，单钉 ①）：done ⇒ 永不判死。
        v <- BgTaskLiveness.probe(row(job, sid, ageMs = 60000L))
        _ <- IO(assertEquals(v, BgTaskLiveness.Alive, "done 者永不判死——758ms 窗口假证豁免"))
      yield ()).guarantee(ShellSession.destroySession(sid).attempt.void)
    }
  }

  // ── ② 幼龄行豁免 ────────────────────────────────────────────────────

  test("② 行龄 <10s 豁免判死（not owned 不收）；逾窗同态判死；owner gone 不豁免") {
    withDataRoot {
      val sid = newSid("young")
      (for
        s1 <- ShellSession.forSession(sid)
        now = System.currentTimeMillis()
        // 任务图无此 job（「not owned by session」的旧判据形态）：
        young = BgTaskRegistry.ActiveTask("ghost-job", sid, "d", now, kind = "local")
        vYoung <- BgTaskLiveness.probe(young, now)
        _ <- IO(assertEquals(vYoung, BgTaskLiveness.Alive, "幼龄行（<10s）豁免判死"))
        old = young.copy(startedAtMs = now - 11000L)
        vOld <- BgTaskLiveness.probe(old, now)
        _ <- IO(assertEquals(vOld, BgTaskLiveness.Dead("not owned by session"), "逾窗 ⇒ 正常判死"))
        // owner session gone 是唯一不豁免分支（会话不在册 = 结构性死证）：
        gone = BgTaskRegistry.ActiveTask("ghost-job", "dw-no-such-session", "d", now, kind = "local")
        vGone <- BgTaskLiveness.probe(gone, now)
        _ <- IO(assertEquals(vGone, BgTaskLiveness.Dead("owner session gone"), "owner gone 不适用幼龄豁免"))
      yield ()).guarantee(ShellSession.destroySession(sid).attempt.void)
    }
  }

  // ── ③ 关停抑制旗 ────────────────────────────────────────────────────

  test("③ 关停抑制旗：旗起 reapDead 不判死只告警；旗清恢复收割（639c9d15 型）") {
    withDataRoot {
      (for
        _ <- BgTaskRegistry.register("sd-1", "dw-no-such-session", "desc-sd1", "local")
        _ <- IO(BgTaskReaper.markEngineShutdown())
        report1 <- BgTaskReaper.reapDead(None, None)
        _ <- IO {
          assertEquals(report1.reaped, Nil, "旗起期间不判死")
          assertEquals(report1.remaining, 1)
        }
        rowStill <- BgTaskRegistry.find("sd-1")
        _ <- IO(assert(rowStill.isDefined, "旗起期间行原样在册（只告警不动作）"))
        _ <- IO(BgTaskReaper.clearEngineShutdownForSpec())
        report2 <- BgTaskReaper.reapDead(None, None)
        _ <- IO(assertEquals(report2.reapedJobIds, List("sd-1"), "旗清恢复收割"))
      yield ()).guarantee(
        IO(BgTaskReaper.clearEngineShutdownForSpec()) *>
          BgTaskRegistry.unregister("sd-1").attempt.void
      )
    }
  }

  // ── ⑤ 输增长二次确认 ────────────────────────────────────────────────

  test("⑤ 判死前输增长二次确认：近 5s 有输出 ⇒ 跳过一拍；无增长/无缓冲 ⇒ 照常收割") {
    withDataRoot {
      val sid = newSid("growth")
      (for
        s1 <- ShellSession.forSession(sid)
        now = System.currentTimeMillis()
        // 两老龄行同拍注册：任务图均无（「not owned」形态）。
        _ <- BgTaskRegistry.register("grow-1", sid, "desc-grow", "local", startedAtMs = now - 60000L)
        _ <- BgTaskRegistry.register("nogrow-1", sid, "desc-nogrow", "local", startedAtMs = now - 60000L)
        // grow-1 近 5s 有输出增长（模拟正在吐编译日志的活任务）；nogrow-1 无缓冲。
        buf <- BgTaskOutputStore.open("grow-1")
        _ <- IO(buf.append("[info] compiling 698 Scala sources ..."))
        report <- BgTaskReaper.reapDead(None, None)
        _ <- IO {
          assert(!report.reapedJobIds.contains("grow-1"), s"输出增长 ⇒ 本轮豁免: $report")
          assertEquals(report.reapedJobIds, List("nogrow-1"), "无增长对照行照常收割")
        }
        rowGrow <- BgTaskRegistry.find("grow-1")
        _ <- IO(assert(rowGrow.isDefined, "被豁免行原样在册"))
        // 对照②：确认窗置 0（prop 翻转）——同样的「输出刚增长」形态照常收割
        // （二次确认零永久免疫：输出停增长后下一拍即回到正常判死路径）。
        _ <- IO(System.setProperty("nebflow.bg.deathWatch.outputGrowthConfirmMs", "0"))
        now2 = System.currentTimeMillis()
        _ <- BgTaskRegistry.register("grow-2", sid, "desc-grow2", "local", startedAtMs = now2 - 60000L)
        buf2 <- BgTaskOutputStore.open("grow-2")
        _ <- IO(buf2.append("[info] compiling ..."))
        report2 <- BgTaskReaper.reapDead(None, None)
        _ <- IO(
          assert(
            report2.reapedJobIds.contains("grow-2"),
            s"确认窗=0 ⇒ 增长形态不再豁免（零永久免疫）: $report2"
          )
        )
      yield ()).guarantee(
        IO(System.clearProperty("nebflow.bg.deathWatch.outputGrowthConfirmMs")) *>
          ShellSession.destroySession(sid).attempt.void *>
          List("grow-1", "nogrow-1", "grow-2").traverse_(j => BgTaskRegistry.unregister(j).attempt.void)
      )
    }
  }

  // ── ⑥ 句柄未落豁免加期限 ────────────────────────────────────────────

  test("⑥ 句柄未落豁免 5min 期限：adopt 任务句柄永不再落，逾龄 ⇒ handle never landed") {
    withDataRoot {
      val sid = newSid("handle")
      (for
        s1 <- ShellSession.forSession(sid)
        // adopt 一个 fiber 永不完成的收编任务，JobHealth.processRef 恒 null——
        // 「重启恢复/收编句柄永不再落」的确定性形态（不经毫秒级 spawn 窗口竞速）。
        health = new JobHealth()
        fiber <- IO.never[Either[Throwable, ProcessResult]].start
        _ <- s1.adoptAsBackgroundJob("hnd-1", fiber, health, "sleep 999", description = Some("dw-⑥-adopted"))
        probe0 <- s1.probeJobLiveness("hnd-1")
        _ <- IO {
          assert(probe0.isDefined, "收编任务已入任务图")
          assert(probe0.exists(p => !p.handlePresent && !p.alive), "precondition: 句柄未落（僵尸免疫旧形态）")
        }
        now = System.currentTimeMillis()
        // 豁免期内（行龄 1s < 5min）⇒ Alive（旧 A-4 语义不回退）。
        vGrace <- BgTaskLiveness.probe(
          BgTaskRegistry.ActiveTask("hnd-1", sid, "d", startedAtMs = now - 1000L, kind = "local"),
          now
        )
        _ <- IO(assertEquals(vGrace, BgTaskLiveness.Alive, "豁免期内句柄未落 ⇒ Alive"))
        // 逾龄（行龄 6min > 5min）⇒ 降级普通判死路径（堵永久 Alive 反向缺陷）。
        vExpired <- BgTaskLiveness.probe(
          BgTaskRegistry.ActiveTask("hnd-1", sid, "d", startedAtMs = now - 6L * 60L * 1000L, kind = "local"),
          now
        )
        _ <- IO(assertEquals(vExpired, BgTaskLiveness.Dead("handle never landed"), "逾龄 ⇒ 降级判死"))
        _ <- fiber.cancel
      yield ()).guarantee(ShellSession.destroySession(sid).attempt.void)
    }
  }

  // ── ④ 任务图结构化观测腿 ────────────────────────────────────────────

  test("④ 任务图注册/移除结构化日志：实例 id + 原因落盘（logback ListAppender 实证）") {
    withDataRoot {
      val sid = newSid("graphlog")
      val lcLogger = org.slf4j.LoggerFactory.getLogger("nebflow.shell").asInstanceOf[
        ch.qos.logback.classic.Logger
      ]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      appender.start()
      lcLogger.addAppender(appender)
      (for
        s1  <- ShellSession.forSession(sid)
        job <- s1.executeBackground("echo dw-④", description = Some("dw-④-log-probe"))
        _ <- pollUntil(s1.getBackgroundResult(job).map(_.isDefined), timeoutMs = 15000L)
        events = appender.list.toArray.toList.map(_.asInstanceOf[ch.qos.logback.classic.spi.ILoggingEvent])
        registers = events.filter(e => e.getFormattedMessage.contains("[bg-job-graph] register"))
        removes = events.filter(e => e.getFormattedMessage.contains("[bg-job-graph] remove"))
        _ <- IO {
          assert(registers.exists(e => e.getFormattedMessage.contains(s"jobId=$job")), s"注册行携 jobId: $registers")
          assert(
            registers.exists(e => e.getFormattedMessage.contains(s"instance=${s1.instanceId}")),
            s"注册行携会话实例 id（#1220 定谳腿）: $registers"
          )
          assert(
            registers.exists(e => e.getFormattedMessage.contains("cause=executeBackground")),
            s"注册行携原因: $registers"
          )
          assert(
            removes.exists(e =>
              e.getFormattedMessage.contains(s"jobId=$job") &&
                e.getFormattedMessage.contains("cause=getBackgroundResult")
            ),
            s"移除行携 jobId + 原因（正常完成消费）: $removes"
          )
        }
      yield ()).guarantee(
        IO {
          lcLogger.detachAppender(appender)
          appender.stop()
        } *> ShellSession.destroySession(sid).attempt.void
      )
    }
  }

  // ── Defaults 参数面钉子 ─────────────────────────────────────────────

  test("Defaults：death-watch 豁免窗/期限/确认窗默认档与 prop 翻转缝") {
    IO {
      assertEquals(Defaults.BgDeathWatchYoungRowExemptMs, 10000L)
      assertEquals(Defaults.BgDeathWatchHandleGraceMs, 5L * 60L * 1000L)
      assertEquals(Defaults.BgDeathWatchOutputGrowthConfirmMs, 5000L)
      val prev = System.setProperty("nebflow.bg.deathWatch.youngRowExemptMs", "250")
      try assertEquals(Defaults.BgDeathWatchYoungRowExemptMs, 250L, "prop 现读翻转")
      finally System.clearProperty("nebflow.bg.deathWatch.youngRowExemptMs")
    }
  }

end BgDeathWatchFalsePositiveFixSpec
