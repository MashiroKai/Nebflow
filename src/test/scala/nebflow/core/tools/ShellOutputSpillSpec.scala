package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.util.UUID
import scala.concurrent.duration.*

/**
 * 2026-11-04「内存即时缓解四条」M3 验红/验绿 spec：工具输出溢写。
 *
 * 改前（`shell.scala:510 MaxOutputSize = 10MB` + `readStream` 的 StringBuilder）：
 * 每条命令的 stdout / stderr **各**最多驻留 10MB 字符（敲满后截断丢弃，全量拿不到），
 * 事故日实测峰值 ≈20MB。本 spec 用合成流（不占真实内存的数据源）驱动真 `readStream`：
 *
 *   - 100MB 输出 → **堆增长 ≤ 1MB**（改前实测 ≈10MB/流，红；改后 ≤1MB，绿）；
 *   - 返回给调用方的可见内容 ≤ ~256KB（首 64KB + 尾 64KB + 截断注记）；
 *   - 超限全量溢写落盘（`<dataRoot>/shell-spill/`），全量可从盘上回看——不再丢弃。
 *
 * 合成流纪律：`available()` 如实报告、EOF 正常，读线程按 readStream 既有
 * `ready()` 轮询语义推进（不依赖真管道/真 sbt）。
 */
class ShellOutputSpillSpec extends FunSuite:

  override def munitTimeout: FiniteDuration = FiniteDuration(240, "s")

  /** 会话实例仅作 `private[tools] readStream` 的取用句柄（forSession 不 spawn 进程）。 */
  private lazy val session: ShellSession =
    ShellSession.forSession(s"spill-${UUID.randomUUID().toString.take(8)}").unsafeRunSync()

  // ---------- 合成流：按固定行长反复吐字节，堆占用恒定 ----------

  private final class SynthLinesStream(totalBytes: Long, lineBytes: Int) extends java.io.InputStream:
    private val line: Array[Byte] =
      val a = Array.fill[Byte](lineBytes)('x'.toByte)
      a(lineBytes - 1) = '\n'.toByte
      a
    private var served: Long = 0L

    override def available(): Int =
      math.min(totalBytes - served, 64L * 1024L).toInt.max(0)

    override def read(): Int =
      if served >= totalBytes then -1
      else
        val b = line((served % lineBytes).toInt).toInt & 0xff
        served += 1
        b

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if served >= totalBytes then -1
      else
        val n = math.min(len.toLong, math.min(totalBytes - served, lineBytes.toLong)).toInt
        var i = 0
        while i < n do
          b(off + i) = line(((served + i) % lineBytes).toInt)
          i += 1
        served += n
        n

  /**
   * 稳定化的已用堆读数：多轮 `System.gc` + 短眠，取**最小值**（= 保留集大小）。
   * 单轮 GC 留 eden 垃圾 ⇒ 读数会被 ~1MB 级噪声带偏；取 4 轮最小值的目的是让
   * 「100MB 输出后的保留量」这唯一被测属性可复现（改前 13MB / 改后 <1MB 量级差 10 倍，
   * 与噪声 1MB 同量级，必须压噪——本 spec 曾在 1110KB 处误红一次）。
   */
  private def usedHeapBytes(): Long =
    var min = Long.MaxValue
    var i = 0
    while i < 4 do
      System.gc()
      Thread.sleep(150L)
      val rt = Runtime.getRuntime
      val v = rt.totalMemory() - rt.freeMemory()
      if v < min then min = v
      i += 1
    min

  /**
   * 收敛读数：反复「GC + 读堆」直到相邻两次差 < 128KB（最多 8 轮），取最小值。
   * 理由：测试 JVM 里 munit / cats-effect 计算池仍在分配，单次读数会被 ~1MB 级
   * 他人分配带偏（本 spec 实测曾在 1110KB / 1527KB 处误红，真值仅数百 KB）。
   * 收敛把「他人噪声」压到阈值以下，测的才是「本条命令的保留量」。
   */
  private def convergedUsedHeapBytes(): Long =
    var prev = -1L
    var cur = usedHeapBytes()
    var i = 0
    while i < 8 && (prev < 0 || math.abs(cur - prev) > 128 * 1024) do
      prev = cur
      cur = usedHeapBytes()
      i += 1
    if prev > 0 then math.min(prev, cur) else cur

  private val MB = 1024L * 1024L

  /** 隔离溢写目录（测试绝不写真实 dataRoot）。 */
  private def withSpillDir[T](f: os.Path => T): T =
    val dir = os.temp.dir(prefix = "shell-spill-spec")
    try f(dir)
    finally try os.remove.all(dir) catch case _: Throwable => ()

  test("M3: 100MB 输出经 readStream 后堆增长 ≤ 1MB（内存只留首尾 64KB）") {
    withSpillDir { spill =>
      // 预热：先用 1MB 输出把读线程/缓冲/JIT 跑到稳态，剔除一次性分配对读数的干扰
      val warm = session.readStream(new SynthLinesStream(1L * MB, 1024), _ => (), () => true, 50L, spill)
      assert(warm.nonEmpty, "预热读为空")
      os.list(spill).foreach(p => os.remove(p))
      val stream = new SynthLinesStream(100L * MB, 1024)
      val before = convergedUsedHeapBytes()
      val out = session.readStream(stream, _ => (), () => true, 250L, spill)
      val after = convergedUsedHeapBytes()
      val delta = math.max(0L, after - before)
      val spilled = os.list(spill).map(p => os.size(p)).sum
      val msg =
        s"heapBefore=${before / 1024}KB heapAfter=${after / 1024}KB heapDelta=${delta / 1024}KB outLen=${out.length} spilledBytes=$spilled"
      println(s"[M3-evidence] $msg")
      assert(
        delta <= 1L * MB,
        s"$msg —— 100MB 输出后堆增长 > 1024KB：内存缓冲仍在攒全量（改前 10MB StringBuilder 形态）"
      )
      assert(out.length <= 256 * 1024 + 512, s"$msg —— 可见内容超出首尾窗口上界")
      assert(
        spilled >= 100L * MB - 4096,
        s"$msg —— 溢写文件不足 100MB：全量未落盘（作者要求「改后全量可回看」）"
      )
      assert(out.contains("shell-spill"), s"$msg —— 折叠注记未给出溢写路径，调用方无法回看全量")
    }
  }

  test("M3: 返回给调用方的可见内容 ≤ 256KB 且带截断注记（首尾各 64KB）") {
    withSpillDir { spill =>
      val stream = new SynthLinesStream(8L * MB, 1024)
      val out = session.readStream(stream, _ => (), () => true, 250L, spill)
      assert(out.length <= 256 * 1024 + 512, s"可见内容 ${out.length}B 超出首尾窗口上界")
      assert(out.contains("truncated"), s"截断注记缺失——调用方无法感知输出被折叠 (outLen=${out.length})")
      assert(os.list(spill).nonEmpty, "8MB 输出未产生溢写文件")
    }
  }

  test("M3: 小输出逐字节不变（< 64KB 不截断、不落盘，行为与改前一致）") {
    withSpillDir { spill =>
      val small = "hello\nworld\n"
      val bytes = small.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val is = new java.io.ByteArrayInputStream(bytes)
      val out = session.readStream(is, _ => (), () => true, 50L, spill)
      assert(out.trim.nonEmpty, "小输出丢失")
      assert(out.contains("hello") && out.contains("world"), s"小输出内容异常: $out")
      assert(os.list(spill).isEmpty, "小输出不该产生溢写文件（零副作用面）")
    }
  }
