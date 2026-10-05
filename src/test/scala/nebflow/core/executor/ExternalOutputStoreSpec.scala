package nebflow.core.executor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import scala.collection.immutable.Queue
import scala.concurrent.duration.*

/**
 * external-executors 批（#1225）验收 · ExternalOutputStore（进度缓冲面）。
 *
 * 中心性质（增补任务书 §一：进度=适配流缓冲，REST 输出面仿 BgTaskOutputStore；
 * §3.5：live 来信 mailbox 排队）：
 *   - 幂等 open / 首个终态胜出（finalize 二次调用零覆写）；
 *   - 缓冲面读数与 bg-tasks 输出面同构（offset 游标 / truncated / nextOffset）；
 *   - mailbox：enqueue 累计、drain 全量出队、resetForNewRun 保留；
 *   - resetForNewRun：终态条目才重置（running 拒绝）、cliSessionId 跨轮保留。
 */
class ExternalOutputStoreSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  // 进程级单例表——用随机 id 隔离用例（同 BgTaskOutputStore 系 spec 口径）。
  private def freshId(): String = s"external-test-${java.util.UUID.randomUUID().toString.take(8)}"

  test("open is idempotent; append/read round-trips with cursor semantics") {
    val id = freshId()
    (for
      _ <- ExternalOutputStore.open(id)
      _ <- ExternalOutputStore.open(id) // 重复 open 不撕第二缓冲
      _ <- ExternalOutputStore.append(id, "line-1")
      _ <- ExternalOutputStore.append(id, "line-2")
      first <- ExternalOutputStore.read(id, 0)
      tail  <- ExternalOutputStore.read(id, first.get.nextOffset)
    yield
      val f = first.get
      assertEquals(f.status, "running")
      assertEquals(f.output, "line-1\nline-2")
      assertEquals(f.totalLines, 2L)
      assertEquals(f.truncated, false)
      assert(f.nextOffset > 0)
      assertEquals(tail.get.output, "")
    ).unsafeRunSync()
  }

  test("finalize: first terminal wins (idempotent), exitCode/errorHint retained") {
    val id = freshId()
    (for
      _ <- ExternalOutputStore.open(id)
      _ <- ExternalOutputStore.finalizeTask(id, "blocked", exitCode = Some(1), errorHint = Some("CLI exited 1"))
      _ <- ExternalOutputStore.finalizeTask(id, "completed", exitCode = Some(0)) // 迟到终态不覆写
      read <- ExternalOutputStore.read(id, 0)
    yield
      assertEquals(read.get.status, "blocked")
      assertEquals(read.get.exitCode, Some(1))
      assertEquals(read.get.errorHint, Some("CLI exited 1"))
      assert(read.get.finishedAtMs.isDefined)
    ).unsafeRunSync()
  }

  test("kill: running entry with a process gets killed and reports true; unknown/terminal = false") {
    val id  = freshId()
    val id2 = freshId()
    // 真进程：sleep 一会的 sh（killProcessTree 有真实靶子）。
    val p = new java.lang.ProcessBuilder("sh", "-c", "sleep 30").start()
    (for
      live <- ExternalOutputStore.kill(id) // 无条目 → false
      _ <- ExternalOutputStore.open(id)
      dead <- ExternalOutputStore.kill(id) // 有条目但无进程句柄 → false（不假报成功）
      _ <- ExternalOutputStore.open(id2)
      _ <- ExternalOutputStore.attachProcess(id2, p)
      killed <- ExternalOutputStore.kill(id2)
      exited <- IO.blocking(p.waitFor())
    yield
      assertEquals(live, false)
      assertEquals(dead, false)
      assertEquals(killed, true)
      assert(!p.isAlive) // 进程被终止（SIGTERM 家族）
    ).unsafeRunSync()
  }

  test("mailbox: enqueue grows, drain empties atomically, depth is observable") {
    val id = freshId()
    (for
      d0 <- ExternalOutputStore.enqueue(id, "x") // 无条目 → 队深 0（来信丢弃面在 runner 侧拒收）
      _ <- ExternalOutputStore.open(id)
      d1 <- ExternalOutputStore.enqueue(id, "first")
      d2 <- ExternalOutputStore.enqueue(id, "second")
      depth <- ExternalOutputStore.mailboxDepth(id)
      drained <- ExternalOutputStore.drainMailbox(id)
      drainedAgain <- ExternalOutputStore.drainMailbox(id)
      depthAfter <- ExternalOutputStore.mailboxDepth(id)
    yield
      assertEquals(d0, 0)
      assertEquals(d1, 1)
      assertEquals(d2, 2)
      assertEquals(depth, 2)
      assertEquals(drained, Queue("first", "second"))
      assertEquals(drainedAgain, Queue.empty[String])
      assertEquals(depthAfter, 0)
    ).unsafeRunSync()
  }

  test("resetForNewRun: terminal entry resets with cliSessionId+mailbox kept; running entry refuses") {
    val id = freshId()
    (for
      running <- ExternalOutputStore.resetForNewRun(id) // 尚无条目 → false（open 先行）
      _ <- ExternalOutputStore.open(id)
      refuse <- ExternalOutputStore.resetForNewRun(id) // running 条目拒绝重置
      _ <- ExternalOutputStore.setCliSession(id, "sess-42")
      _ <- ExternalOutputStore.enqueue(id, "late mail")
      _ <- ExternalOutputStore.finalizeTask(id, "completed", exitCode = Some(0))
      _ <- ExternalOutputStore.append(id, "old-run tail") // 终态后补写（旧轮留痕）
      reset <- ExternalOutputStore.resetForNewRun(id)
      after <- ExternalOutputStore.read(id, 0)
      cli <- ExternalOutputStore.cliSessionIdOf(id)
      depth <- ExternalOutputStore.mailboxDepth(id)
    yield
      assertEquals(running, false)
      assertEquals(refuse, false)
      assertEquals(reset, true)
      assertEquals(after.get.status, "running")
      assertEquals(after.get.totalLines, 0L) // fresh 缓冲（旧轮缓冲随重置清空）
      assertEquals(cli, Some("sess-42")) // 续聊锚点跨轮保留
      assertEquals(depth, 1) // mailbox 跨轮保留
    ).unsafeRunSync()
  }

  test("setCliSession: first write wins, empty string is a no-op") {
    val id = freshId()
    (for
      _ <- ExternalOutputStore.open(id)
      _ <- ExternalOutputStore.setCliSession(id, "")
      before <- ExternalOutputStore.cliSessionIdOf(id)
      _ <- ExternalOutputStore.setCliSession(id, "sess-1")
      _ <- ExternalOutputStore.setCliSession(id, "sess-2") // 迟到帧不覆盖
      after <- ExternalOutputStore.cliSessionIdOf(id)
    yield
      assertEquals(before, None)
      assertEquals(after, Some("sess-1"))
    ).unsafeRunSync()
  }
end ExternalOutputStoreSpec
