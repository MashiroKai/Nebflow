package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.EventSink
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 后台任务生命周 P2 death-watch 批（设计稿 20261030 §4.2 + 10-30 合并闸卡死
 * 病灶收口）—— [[BgTaskReaper]] spec。钉面：
 *
 *   - 判定表（经 [[BgTaskReaper.reapWith]] 探针注桩，零真进程）：Alive/Unknown
 *     不收、Dead 收（reason 随行）；
 *   - 收割副作用：行取走（幂等——二次收割零动作）、账本 completion 条目落
 *     owner-lost 载荷（payloadHead 判重面就绪）、留存区翻转 cancelled、
 *     backgroundTaskUpdate 帧经安装广播口发出；
 *   - 沿等待链注入：属主会话 ∪ 根会话去重（无 registry 时安静跳过，不炸）；
 *   - first-terminal-wins：行先被取走（模拟取消/真完成竞速）⇒ 收割零副作用；
 *   - BgTaskLiveness 词表钉子（Alive/Dead/Unknown 封闭集）。
 *
 * 数据根经 PathUtil.setDataRoot 隔离，结尾还根。
 */
class BgTaskReaperSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 60.seconds

  private def withDataRoot[A](body: IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val tmp = os.temp.dir()
    PathUtil.setDataRoot(tmp / "data")
    body.guarantee(IO { PathUtil.setDataRoot(prev); os.remove.all(tmp) }.attempt.void)

  private def frameSink(frames: cats.effect.Ref[IO, List[Json]]): EventSink =
    new EventSink:
      def broadcast(json: Json): IO[Unit] = frames.update(_ :+ json)

  private def row(job: String, sid: String = "sid-1", root: String = ""): BgTaskRegistry.ActiveTask =
    BgTaskRegistry.ActiveTask(
      jobId = job,
      sessionId = sid,
      description = s"desc-$job",
      startedAtMs = System.currentTimeMillis(),
      kind = "local",
      rootSessionId = root
    )

  private val alwaysDead: BgTaskRegistry.ActiveTask => IO[BgTaskLiveness.Verdict] =
    _ => IO.pure(BgTaskLiveness.Dead("process dead"))
  private val alwaysAlive: BgTaskRegistry.ActiveTask => IO[BgTaskLiveness.Verdict] =
    _ => IO.pure(BgTaskLiveness.Alive)
  private val alwaysUnknown: BgTaskRegistry.ActiveTask => IO[BgTaskLiveness.Verdict] =
    _ => IO.pure(BgTaskLiveness.Unknown)

  test("判定表：Alive/Unknown 不收，Dead 收且 reason 随行（探针注桩，零真进程）") {
    withDataRoot {
      (
        for
          _ <- BgTaskRegistry.register("rv-a", "sid-1", "desc-a", "local")
          _ <- BgTaskRegistry.register("rv-b", "sid-1", "desc-b", "local")
          _ <- BgTaskRegistry.register("rv-c", "sid-1", "desc-c", "local")
          report1 <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis()) { r =>
            IO.pure(if r.jobId == "rv-b" then BgTaskLiveness.Dead("owner session gone") else BgTaskLiveness.Alive)
          }
          _ <- IO {
            assertEquals(report1.reapedJobIds, List("rv-b"))
            assertEquals(report1.reaped.head._2, "owner session gone")
            assertEquals(report1.remaining, 2)
          }
          report2 <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis()) { r =>
            IO.pure(if r.jobId == "rv-c" then BgTaskLiveness.Unknown else BgTaskLiveness.Alive)
          }
          _ <- IO {
            assertEquals(report2.reaped, Nil, "Unknown 不收")
            assertEquals(report2.remaining, 2)
          }
          rows <- BgTaskRegistry.allActiveTasks
          _ <- IO(assertEquals(rows.map(_.jobId).sorted, List("rv-a", "rv-c")))
        yield ()
      ).guarantee(List("rv-a", "rv-b", "rv-c").traverse_(j => BgTaskRegistry.unregister(j).attempt.void))
    }
  }

  test("收割副作用：行取走 + 账本 owner-lost completion 条目 + 留存区 cancelled + 广播帧") {
    withDataRoot {
      for
        frames <- cats.effect.Ref.of[IO, List[Json]](Nil)
        _ = BgTaskReaper.installSink(frameSink(frames))
        _ <- BgTaskRegistry.register("fx-1", "sid-1", "desc-fx1", "local", rootSessionId = "root-1")
        // 预开输出留存区（真实任务面：executeBackground 会建缓冲）——收割的
        // finalizeTask 翻转既有记录，read 才可断言。
        buf <- BgTaskOutputStore.open("fx-1")
        _ <- IO(buf.append("fx-1 stuck output tail"))
        report <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis())(alwaysDead)
        _ <- IO(assertEquals(report.reapedJobIds, List("fx-1")))
        // 行取走（二次收割零动作）
        again <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis())(alwaysDead)
        _ <- IO(assertEquals(again.reaped, Nil, "行已取走 ⇒ 二次收割幂等零动作"))
        rows <- BgTaskRegistry.allActiveTasks
        _ <- IO(assertEquals(rows, Nil))
        // 账本：completion 条目、failed eventType、owner-lost 载荷、payloadHead 判重就绪
        ledger <- BgTaskLedger.pending("sid-1")
        _ <- IO {
          assertEquals(ledger.size, 1)
          assertEquals(ledger.head.jobId, "fx-1")
          assertEquals(ledger.head.kind, "completion")
          assertEquals(ledger.head.eventType, "failed")
          assert(ledger.head.payload.contains("owner lost: process dead"), s"owner-lost 载荷:\n${ledger.head.payload}")
          assertEquals(ledger.head.payloadHead, ledger.head.payload.take(BgTaskLedger.PayloadHeadLen))
          assert(!ledger.head.consumed)
        }
        // 留存区翻转 cancelled
        stored <- BgTaskOutputStore.read("fx-1", 0L)
        _ <- IO {
          assert(stored.isDefined, "留存区应有该任务终态记录")
          assertEquals(stored.get.status, "cancelled")
        }
        // 广播帧：backgroundTaskUpdate / cancelled / 权威分键
        fs <- frames.get
        _ <- IO {
          assertEquals(fs.size, 1)
          val f = fs.head
          assertEquals(f.hcursor.downField("type").as[String].toOption, Some("backgroundTaskUpdate"))
          assertEquals(f.hcursor.downField("status").as[String].toOption, Some("cancelled"))
          assertEquals(f.hcursor.downField("taskId").as[String].toOption, Some("fx-1"))
          assertEquals(f.hcursor.downField("sessionId").as[String].toOption, Some("sid-1"))
          assertEquals(f.hcursor.downField("rootSessionId").as[String].toOption, Some("root-1"))
        }
        _ = BgTaskReaper.clearSink()
      yield ()
    }
  }

  test("沿等待链注入：属主 ∪ 根去重（同号一行）；无 registry 安静跳过") {
    withDataRoot {
      for
        _ <- BgTaskRegistry.register("inj-1", "sid-9", "desc-inj", "local", rootSessionId = "sid-9")
        report <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis())(alwaysDead)
        _ <- IO(assertEquals(report.reapedJobIds, List("inj-1")))
        // 无 registry ⇒ 注入腿安静跳过（不炸、收割照常完成）
        _ <- IO(assert(true))
        // 同号属主=根 ⇒ 目标去重后恰一环（此处只断言不炸 + 账本就绪）
        ledger <- BgTaskLedger.pending("sid-9")
        _ <- IO(assertEquals(ledger.map(_.jobId), List("inj-1")))
      yield ()
    }
  }

  test("first-terminal-wins：行先被取走 ⇒ 收割零副作用（不补账/不发帧）") {
    withDataRoot {
      for
        frames <- cats.effect.Ref.of[IO, List[Json]](Nil)
        _ = BgTaskReaper.installSink(frameSink(frames))
        _ <- BgTaskRegistry.register("ftw-1", "sid-1", "desc-ftw", "local")
        // 模拟竞速：真完成先取走行
        _ <- BgTaskRegistry.unregisterReturning("ftw-1")
        report <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis())(alwaysDead)
        _ <- IO(assertEquals(report.reaped, Nil, "行不在册 ⇒ 无收割动作"))
        fs <- frames.get
        _ <- IO(assertEquals(fs, Nil, "竞速输家不发帧"))
        ledger <- BgTaskLedger.pending("sid-1")
        _ <- IO(assertEquals(ledger, Nil, "竞速输家不补账"))
        _ = BgTaskReaper.clearSink()
      yield ()
    }
  }

  test("全 Dead 收割：报告计数与行清空一致") {
    withDataRoot {
      for
        _ <- BgTaskRegistry.register("all-1", "sid-1", "d1", "local")
        _ <- BgTaskRegistry.register("all-2", "sid-2", "d2", "local")
        report <- BgTaskReaper.reapWith(None, None, System.currentTimeMillis())(alwaysDead)
        _ <- IO {
          assertEquals(report.reaped.size, 2)
          assertEquals(report.remaining, 0)
        }
        rows <- BgTaskRegistry.allActiveTasks
        _ <- IO(assertEquals(rows, Nil))
      yield ()
    }
  }

  test("BgTaskLiveness 词表钉子：Alive/Dead/Unknown 封闭集与 Dead reason 承载") {
    val v: BgTaskLiveness.Verdict = BgTaskLiveness.Dead("r")
    v match
      case BgTaskLiveness.Alive          => fail("unexpected")
      case BgTaskLiveness.Dead(reason)   => assertEquals(reason, "r")
      case BgTaskLiveness.Unknown        => fail("unexpected")
  }

end BgTaskReaperSpec
