package nebflow.core.tools

import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import java.nio.charset.{Charset, StandardCharsets}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * 2026-10-25 Windows ShellSession 探针阻塞修复（KAI 全量 test5 挂死根因）。
 *
 * 根因：`probeCharset` 旧实现第一行 `pushback.read(probe)` 是**裸阻塞读**——
 * 等满 4096 字节或管道 EOF 才返回。凡 Windows 上「输出总量 < 4096 ∧ 进程长活」
 * 的会话（sbt 测试里 spawn 的引擎场景全是），stdout/stderr 两条读线程永久埋死
 * 在这一行 → 会话永不完成 → 全量测试 ~10 分钟后整 JVM 焊死。Mac 因 `isWindows`
 * 门跳过探测而四天零暴露（jstack 实锤：两线程同停
 * `PushbackInputStream.read → probeCharset(shell.scala:1230)`）。
 *
 * 修复：available() 驱动 + 25ms 轮询的非阻塞探测——首批字节后等 50ms 静默窗
 * 定案；`isProcessExited` 纳入判据（进程退了立即收尾，覆盖孤儿占管道）；
 * 预算窗 500ms 内零字节 → UTF-8 兜底放行（零超时纪律：窗是「等数据的轮询
 * 上限」，数据一到立即走，不是 wall-clock 判死）。
 *
 * 可测性：`isWindows` 门做成可注入开关（`ShellSession.charsetProbeGateOverride`），
 * `probeCharset` / `readStream` 提为 `private[tools]`——本 spec 在 **Mac 上**
 * 用合成流直接驱动真探测逻辑：永不出字流断言预算窗内放行而非挂死（变异验红：
 * 换回裸 read 即挂死超时），慢速出字断言探测仍正确。
 */
class ShellSessionCharsetProbeSpec extends FunSuite:

  override def munitTimeout: FiniteDuration = FiniteDuration(120, "s")

  /** 探测门覆写每个测试后必须复位，防污染同 JVM 后续测试（见接缝注释）。 */
  override def afterEach(context: munit.AfterEach): Unit =
    ShellSession.charsetProbeGateOverride.set(None)

  private def sid(prefix: String): String =
    s"$prefix-${UUID.randomUUID().toString.take(8)}"

  /** 会话实例仅作 private[tools] 方法的取用句柄（forSession 不 spawn 进程）。 */
  private lazy val session: ShellSession = ShellSession.forSession(sid("probe")).unsafeRunSync()

  // ---------- 合成流（确定性复现，不用真管道） ----------

  /** available 恒 0、read 永久阻塞——「永不出字」流（旧裸 read 的埋死形态）。 */
  private final class NeverYieldsStream extends java.io.InputStream:
    override def available(): Int = 0

    override def read(): Int =
      val lock = new Object
      lock.synchronized(lock.wait()) // 无 notify ⇒ 永久阻塞
      -1

  /** 全部字节在 delay 后一次性可读（available 如实报告），EOF 正常。 */
  private final class DelayedBurstStream(bytes: Array[Byte], delayMs: Long) extends java.io.InputStream:
    private val readyAt = System.currentTimeMillis() + delayMs
    private var served = 0
    private def ready: Boolean = System.currentTimeMillis() >= readyAt
    override def available(): Int = if ready then bytes.length - served else 0

    override def read(): Int =
      if served >= bytes.length then -1
      else
        if !ready then Thread.sleep(math.max(1, readyAt - System.currentTimeMillis()))
        val b = bytes(served) & 0xff
        served += 1
        b

  /** 第一片立即可读，第二片 delay 后到——「切片切半多字节字符」形态。 */
  private final class SplitBurstStream(first: Array[Byte], second: Array[Byte], delayMs: Long)
      extends java.io.InputStream:
    private val readyAt = System.currentTimeMillis() + delayMs
    private var served = 0
    private val total = first.length + second.length
    private def secondReady: Boolean = System.currentTimeMillis() >= readyAt

    override def available(): Int =
      val released = if secondReady then total else first.length
      math.max(0, released - served)

    override def read(): Int =
      if served >= total then -1
      else
        while available() == 0 do Thread.sleep(5)
        val b =
          (if served < first.length then first(served) else second(served - first.length)) & 0xff
        served += 1
        b

  end SplitBurstStream

  /** 在独立线程跑 body，deadline 内不返回即判挂死（daemon，泄漏不阻 JVM 退出）。 */
  private def runBounded(label: String, deadlineMs: Long)(body: => Unit): Long =
    val start = System.currentTimeMillis()
    val thread = new Thread(
      (() => body): Runnable,
      s"charset-probe-spec-$label"
    )
    thread.setDaemon(true)
    thread.start()
    thread.join(deadlineMs)
    if thread.isAlive then fail(s"[$label] ${deadlineMs}ms 内未返回——探测挂死复现（预算窗未放行）")
    System.currentTimeMillis() - start

  private def drain(is: java.io.InputStream): Array[Byte] = is.readAllBytes()

  // ---------- A：预算窗放行（挂死根因的主体断言） ----------

  test("永不出字流：probeCharset 预算窗内放行（UTF-8 兜底），不挂死"):
    // 注意不断言 drain：永不出字流的 read() 设计为永久阻塞（readAllBytes 会
    // 等 EOF 埋死）——回归点是「探测本身在预算窗内返回」，回推完整性由 B/C 组断言
    val elapsed = runBounded("never-yields", 5000) {
      val (_, charset) = session.probeCharset(new NeverYieldsStream, () => false)
      assertEquals(charset, StandardCharsets.UTF_8, "预算窗满零字节 → UTF-8 兜底")
    }
    assert(elapsed < 5000, s"探测须在预算窗(${500}ms)量级放行，实测 ${elapsed}ms")

  test("进程已退出：零输出立即收尾定案，不等预算窗（孤儿占管道类）"):
    // 预算膨胀到 30s：若退出判据失效，本测试 30s 才返回 > 5s deadline ⇒ 变异验红
    val elapsed = runBounded("exited-fast", 5000) {
      val (_, charset) =
        session.probeCharset(new NeverYieldsStream, () => true, probeBudgetMs = 30000L)
      assertEquals(charset, StandardCharsets.UTF_8)
    }
    assert(elapsed < 5000, s"退出判据须短路预算窗，实测 ${elapsed}ms（预算 30s 未到期）")

  // ---------- B：慢速出字仍正确探测（预算窗内数据一到立即走） ----------

  test("慢速出字(150ms) GBK：探测窗内到达仍判 GBK，字节完整回推"):
    val text = "中文GBK输出正确"
    val bytes = text.getBytes(Charset.forName("GBK"))
    val (stream, charset) =
      session.probeCharset(new DelayedBurstStream(bytes, delayMs = 150L), () => false)
    assertEquals(charset, Charset.forName("GBK"), "预算窗(500ms)内到达的数据必须被探测到")
    assertEquals(new String(drain(stream), charset), text, "回推字节须逐字节完整（解码器从头拼整）")

  test("慢速出字(150ms) UTF-8：探测窗内到达仍判 UTF-8"):
    val text = "你好 utf8 输出 — 日本語 🎉"
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    val (stream, charset) =
      session.probeCharset(new DelayedBurstStream(bytes, delayMs = 150L), () => false)
    assertEquals(charset, StandardCharsets.UTF_8)
    assertEquals(new String(drain(stream), StandardCharsets.UTF_8), text)

  test("立即出字 GBK：既有检测语义回归"):
    val text = "中文GBK输出"
    val bytes = text.getBytes(Charset.forName("GBK"))
    val (stream, charset) =
      session.probeCharset(new DelayedBurstStream(bytes, delayMs = 0L), () => false)
    assertEquals(charset, Charset.forName("GBK"))
    assertEquals(new String(drain(stream), charset), text)

  // ---------- C：切半尾字（探测切片把多字节字符切一半不误判 GBK） ----------

  test("切片切半多字节字符：尾部半字回推原始字节，判 UTF-8 不误判 GBK"):
    // 「你」= E4 BD A0：第一片只给 E4 BD（前半），A0 与「好」在 200ms 后到——
    // 50ms 静默窗先于第二片到期，探测必须带着半字定案为 UTF-8
    val first = Array[Byte](0xe4.toByte, 0xbd.toByte)
    val second = (Array[Byte](0xa0.toByte) ++ "好".getBytes(StandardCharsets.UTF_8))
    val (stream, charset) =
      session.probeCharset(new SplitBurstStream(first, second, delayMs = 200L), () => false)
    assertEquals(charset, StandardCharsets.UTF_8, "半字尾是切半不是坏字节，不得误判 GBK")
    val all = drain(stream)
    assertEquals(new String(all, StandardCharsets.UTF_8), "你好", "回推原始字节 + 解码器从头拼整")

  // ---------- D：门接线（readStream 端到端，Mac 上注入开/关探测门） ----------

  test("gate 接线：Some(true) 开探测门，Mac 上 GBK 流解码正确"):
    ShellSession.charsetProbeGateOverride.set(Some(true))
    val text = "中文GBK输出正确"
    val out = session.readStream(
      new java.io.ByteArrayInputStream(text.getBytes(Charset.forName("GBK"))),
      isProcessExited = () => true
    )
    assertEquals(out.trim, text, "gate 开 ⇒ probeCharset 生效 ⇒ GBK 正确解码")

  test("gate 接线：None 关探测门（Mac 真实平台形态），GBK 流按 UTF-8 解码（乱码）"):
    // None 跟随真实平台：Mac 上即跳过探测——GBK 字节按 UTF-8 严格解码出 U+FFFD。
    // Windows 真实门本就开启（None ⇒ 探测生效 ⇒ GBK 正确解码 = D1 语义），
    // 「关探测的乱码形态」在 Windows 无此平台前提 ⇒ 跳过该腿。
    assume(
      !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName),
      "gate-None mojibake shape is the macOS platform premise (Windows probes natively)"
    )
    val text = "中文GBK输出正确"
    val out = session.readStream(
      new java.io.ByteArrayInputStream(text.getBytes(Charset.forName("GBK"))),
      isProcessExited = () => true
    )
    assert(out.trim != text, "gate 关 ⇒ 不探测 ⇒ GBK 按 UTF-8 解码必乱码")
    assert(out.contains('\uFFFD'), "GBK 首对字节 D6D0 非 UTF-8 合法序列 ⇒ 必出替换符")

  test("readStream 端到端挂死形态：永不出字流 + 已退出进程，预算窗+grace 内返回"):
    // jstack 挂死链的等价形态：readStream 入口 probeCharset ← 读线程；
    // 修复后总耗时 ≈ 预算窗(500ms) + exitGrace(250ms)，远小于旧实现的永久埋死
    ShellSession.charsetProbeGateOverride.set(Some(true))
    val elapsed = runBounded("readstream-orphan", 5000) {
      val out = session.readStream(new NeverYieldsStream, isProcessExited = () => true)
      assertEquals(out, "", "零输出 + 进程退出 + grace 窗 ⇒ 空串收尾")
    }
    assert(elapsed < 5000, s"读线程须在预算窗+grace 量级返回，实测 ${elapsed}ms")
end ShellSessionCharsetProbeSpec
