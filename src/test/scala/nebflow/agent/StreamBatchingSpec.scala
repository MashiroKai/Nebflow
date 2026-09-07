package nebflow.agent

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import nebflow.shared.StreamChunk

/** StreamBatching 回归（2026-09-07 B-after-A 冒烟悬案修复）：
  *
  * 旧 streamEmitter 内联合批的 flush 只在「满 50 条 / 距上次 flush ≥50ms /
  * ToolCall 星号族/Done」——无常驻 ticker。流 parking（楔死/半开连接，hard-recovery
  * 的目标场景）且首 delta 距管道构建 <50ms（热连接往返 ~5ms）时缓冲永不出网：
  * 实例日志实证 B 会话 [sse-chunk] 到达而 textDelta WS 帧缺席，round-6/7 冒烟
  * B 场景 wedge 步 20s 超时。修复 = Ref 状态 + awakeEvery ticker 挂
  * .concurrently。本 spec 锁定三个反事实：park 后必出帧（T1）、满批即出
  * （T2）、Done 兜底（T3）。
  */
class StreamBatchingSpec extends CatsEffectSuite:

  private def framesRef: IO[Ref[IO, Vector[Json]]] = IO.ref(Vector.empty)

  /** Collect frames; wsSend appends to the ref (test-local hub). */
  private def sender(ref: Ref[IO, Vector[Json]]): Json => IO[Unit] = j => ref.update(_ :+ j)

  private def pipeFor(ref: Ref[IO, Vector[Json]]): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    StreamBatching.pipe(
      sender(ref),
      isSubagent = false,
      sessionId = Some("s-fix"),
      isAskMode = false,
      isCompactTurn = false,
      agentPath = "agent-under-test"
    )

  /** Parking tail（显式类型避免 ++ 推断失败）。 */
  private val parked: Stream[IO, StreamChunk] = Stream.never[IO]

  private def textDeltas(ref: Ref[IO, Vector[Json]]): IO[Vector[String]] =
    ref.get.map(_.collect { case j if j.hcursor.downField("type").as[String].toOption.contains("textDelta") =>
      j.hcursor.downField("delta").as[String].getOrElse("")
    })

  /** T1（核心反事实）：两 delta 快速到达后流永久 parking——ticker 必须把缓冲
    * 送出网。旧实现在此场景零 WS 帧（缓冲随 parking 永存）。 */
  test("StreamBatching T1: parked stream still flushes buffered deltas via ticker") {
    for
      ref <- framesRef
      p = pipeFor(ref)
      fib <- (Stream(StreamChunk.TextDelta("begin"), StreamChunk.TextDelta(" park")) ++ parked)
        .through(p)
        .compile
        .drain
        .start
      _ <- IO.sleep(400.millis) // > 2×FlushWindowMs(50ms)：ticker 有充分触发窗口
      out <- textDeltas(ref)
      _ <- fib.cancel
    yield assert(out.mkString == "begin park", s"buffered deltas must flush after park; got=$out")
  }

  /** T1b：park 前的无窗期不发帧（合批语义保留——50ms 内到达的 delta 不逐条直发）。 */
  test("StreamBatching T1b: deltas inside the window are batched, not sent per-chunk") {
    for
      ref <- framesRef
      p = pipeFor(ref)
      fib <- (Stream(StreamChunk.TextDelta("a"), StreamChunk.TextDelta("b")) ++ parked)
        .through(p)
        .compile
        .drain
        .start
      _ <- IO.sleep(5.millis) // 窗口内：缓冲中，尚无帧
      early <- textDeltas(ref)
      _ <- fib.cancel
    yield assert(early.isEmpty, s"within-window deltas must stay buffered; got=$early")
  }

  /** T2：满 MaxBatch 条立即 flush（不等窗口）。 */
  test("StreamBatching T2: MaxBatch deltas flush immediately without a ticker tick") {
    for
      ref <- framesRef
      p = pipeFor(ref)
      chunk = Stream((1 to StreamBatching.MaxBatch).map(i => StreamChunk.TextDelta(s"x$i"))*)
      fib <- (chunk ++ parked).through(p).compile.drain.start
      _ <- IO.sleep(20.millis) // < FlushWindowMs：窗口未到，只有满批 flush
      out <- textDeltas(ref)
      _ <- fib.cancel
    yield assert(out.nonEmpty && out.mkString.contains("x1"), s"MaxBatch must flush immediately; got=$out")
  }

  /** T3：Done 兜底 flush 余量（流正常结束零丢失）。 */
  test("StreamBatching T3: Done flushes the remainder (no loss on normal end)") {
    for
      ref <- framesRef
      p = pipeFor(ref)
      _ <- Stream(StreamChunk.TextDelta("tail"), StreamChunk.Done(None, None, None, None)).through(p).compile.drain
      out <- textDeltas(ref)
    yield assert(out.contains("tail"), s"Done must flush the remainder; got=$out")
  }

  /** T4：同一 flush 内 thinking 先于 text（流序保持）。 */
  test("StreamBatching T4: thinking frame precedes text frame within one flush") {
    for
      ref <- framesRef
      p = pipeFor(ref)
      _ <- Stream(
        StreamChunk.ThinkingDelta("th"),
        StreamChunk.TextDelta("tx"),
        StreamChunk.Done(None, None, None, None)
      ).through(p).compile.drain
      types <- ref.get.map(_.map(j => j.hcursor.downField("type").as[String].getOrElse("")))
      ti <- IO(types.indexOf("thinkingDelta"))
      tx <- IO(types.indexOf("textDelta"))
    yield assert(ti >= 0 && tx > ti, s"thinking must precede text; types=$types")
  }
