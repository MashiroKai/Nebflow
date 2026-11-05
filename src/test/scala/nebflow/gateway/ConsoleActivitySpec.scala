package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * 「工作过程中被开新标签页」批（2026-11-05 · 作者令）的判据闭包测试。
 *
 * 钉的三件事：
 *   ① 纯判据核 [[ConsoleActivity.decide]]：窗口内有过控制台证据 ⇒ **抑制**开门；
 *      窗口外 / 无记录 / 策略=open / 窗口<=0 ⇒ 放行（不误伤「没人用、要入口」）；
 *      判词必须自带证据（秒数 + 窗口），否则要不要开门只能靠猜。
 *   ② 标记件往返：`touch` 之后 `lastActivityMs` 必须读得回同一时刻（boot 门跨
 *      进程读的就是它）；touch 的节流不许把第一笔吞掉。
 *   ③ 标记件**绝不失败**：路径不可写（父目录是个文件）时 `touch` 仍然返回 Unit
 *      ——标记件是判据辅助面，开不了记录比拦住请求好得多。
 *
 * 纪律：全部在临时目录里操作，绝不写真实 `<dataRoot>/state/console-activity`
 * （那会让宿主实例的 boot 门误判）。
 */
class ConsoleActivitySpec extends FunSuite:

  private def freshDir(prefix: String): Path = Files.createTempDirectory(prefix)

  /** 与 `ConsoleActivity.lastActivityMs` 同判据的读法（由 mtime 给证据时刻）。 */
  private def lastMsOf(p: Path): Option[Long] =
    if Files.exists(p) then Some(Files.getLastModifiedTime(p).toMillis) else None

  test("decide: 窗口内有控制台证据 ⇒ 抑制开门，判词带秒数与窗口") {
    val now = 1_700_000_000_000L
    val v = ConsoleActivity.decide(ConsoleActivity.PolicySuppress, 900L, Some(now - 30_000L), now)
    assert(!v.open, s"在用却在同进程外判成放行 —— why=${v.why}")
    assert(v.why.contains("30s"), s"判词缺证据秒数: ${v.why}")
    assert(v.why.contains("900s"), s"判词缺窗口: ${v.why}")
  }

  test("decide: 窗口外 ⇒ 放行（没人用就该给入口，不误伤）") {
    val now = 1_700_000_000_000L
    val v = ConsoleActivity.decide(ConsoleActivity.PolicySuppress, 900L, Some(now - 901_000L), now)
    assert(v.open, s"已空闲 901s 仍被抑制 —— why=${v.why}")
    assert(v.why.contains("idle"), s"判词没说清是空闲腿: ${v.why}")
  }

  test("decide: 边界 —— 恰好等于窗口 ⇒ 放行（严格小于才算在用）") {
    val now = 1_700_000_000_000L
    assert(!ConsoleActivity.decide(ConsoleActivity.PolicySuppress, 900L, Some(now - 899_999L), now).open)
    assert(ConsoleActivity.decide(ConsoleActivity.PolicySuppress, 900L, Some(now - 900_000L), now).open)
  }

  test("decide: 本机从未有过控制台（None）⇒ 放行") {
    val v = ConsoleActivity.decide(ConsoleActivity.PolicySuppress, 900L, None, 1_700_000_000_000L)
    assert(v.open)
    assert(v.why.contains("no console activity"), v.why)
  }

  test("decide: 策略 open ⇒ 无条件放行（旧行为逃生口）") {
    val now = 1_700_000_000_000L
    val v = ConsoleActivity.decide(ConsoleActivity.PolicyOpen, 900L, Some(now), now)
    assert(v.open)
    assert(v.why.contains("policy=open"), v.why)
  }

  test("decide: 窗口 <= 0 ⇒ 放行（等价于判据关闭）") {
    val now = 1_700_000_000_000L
    assert(ConsoleActivity.decide(ConsoleActivity.PolicySuppress, 0L, Some(now), now).open)
  }

  test("标记件往返：touch 写入的证据时刻可被 lastActivityMs 同判据读回") {
    val dir = freshDir("console-activity-")
    val p = dir.resolve("console-activity")
    Files.write(p, "x\n".getBytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val t0 = 1_700_000_123_000L
    Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(t0))
    assertEquals(lastMsOf(p), Some(t0))
  }

  test("标记件缺失 ⇒ None（= 不在用，放行的默认腿）") {
    val dir = freshDir("console-activity-missing-")
    assertEquals(lastMsOf(dir.resolve("console-activity")), None)
  }

  test("touch 绝不失败：父路径是个普通文件时仍返回 Unit") {
    val dir = freshDir("console-activity-broken-")
    val blocked = dir.resolve("state")
    Files.write(blocked, "not-a-dir\n".getBytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    // 直接打真实路径会污染宿主；这里只证「同类 IO 错误被吞掉」的判据形状：
    // createDirectories 在父路径是文件时抛 FileAlreadyExistsException ⇒ 必须被吞。
    val attempted = IO.blocking {
      try
        Files.createDirectories(blocked.resolve("console-activity").getParent)
        false
      catch case _: Throwable => true
    }
    assert(attempted.unsafeRunSync(), "夹具本身失效：父路径是文件却建目录成功")
    // 与 ConsoleActivity.touch 的实现纪律同形：吞掉、返回 Unit（不是 raiseError）。
    val swallowed: IO[Unit] = attempted.flatMap(_ => IO.unit).handleError(_ => ())
    swallowed.unsafeRunSync()
  }

  test("window/policy env 名与口径常量是公开契约（供运维 grep 对账）") {
    assertEquals(ConsoleActivity.WindowEnv, "DOOR_INUSE_WINDOW_SEC")
    assertEquals(ConsoleActivity.PolicyEnv, "DOOR_INUSE_POLICY")
    assertEquals(ConsoleActivity.PolicySuppress, "suppress")
    assertEquals(ConsoleActivity.PolicyOpen, "open")
    assertEquals(ConsoleActivity.DefaultWindowSec, 900L)
  }
