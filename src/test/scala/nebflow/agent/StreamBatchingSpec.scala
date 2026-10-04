package nebflow.agent

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import nebflow.shared.StreamChunk

/**
 * StreamBatching 回归（2026-09-07 B-after-A 冒烟悬案修复）：
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
    ref.get.map(_.collect {
      case j if j.hcursor.downField("type").as[String].toOption.contains("textDelta") =>
        j.hcursor.downField("delta").as[String].getOrElse("")
    })

  /**
   * T1（核心反事实）：两 delta 快速到达后流永久 parking——ticker 必须把缓冲
   * 送出网。旧实现在此场景零 WS 帧（缓冲随 parking 永存）。
   */
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

  // ── B2（RC-2，2026-10-04）：帧的块/轮次标识 ──────────────────────────────
  // 反事实：B2 之前，textDelta / thinkingDelta 帧上**没有任何**段/轮次字段，
  // 前端只能从累积字符串长度回退推段边界。以下三个用例把「帧必带 round/block」
  // 与「block 在 round 内严格递增、跨 round 重置」钉成机械可查的事实。

  private def marks(ref: Ref[IO, Vector[Json]]): IO[Vector[(String, Int, Int)]] =
    ref.get.map(_.flatMap { j =>
      val c = j.hcursor
      for
        t <- c.downField("type").as[String].toOption
        r <- c.downField("round").as[Int].toOption
        b <- c.downField("block").as[Int].toOption
      yield (t, r, b)
    })

  /** T5：每个出网 delta 帧都携带 (round, block)。 */
  test("StreamBatching T5 (B2): every delta frame carries a round/block mark") {
    for
      ref <- framesRef
      p = StreamBatching.pipe(
        sender(ref),
        isSubagent = false,
        sessionId = Some("s-b2"),
        isAskMode = false,
        isCompactTurn = false,
        agentPath = "agent-under-test",
        round = 7
      )
      _ <- Stream(
        StreamChunk.ThinkingDelta("th"),
        StreamChunk.TextDelta("tx"),
        StreamChunk.Done(None, None, None, None)
      ).through(p).compile.drain
      ms <- marks(ref)
    yield assert(
      ms.nonEmpty && ms.forall(_._2 == 7) && ms.map(_._3).distinct.size == ms.size,
      s"every delta frame must carry its own (round=7, block); got=$ms"
    )
  }

  /** T6：同一管道内 block 严格递增（跨 flush 保留，不因 take 重置）。 */
  test("StreamBatching T6 (B2): block increases strictly across flushes in one round") {
    for
      ref <- framesRef
      p = StreamBatching.pipe(
        sender(ref),
        isSubagent = false,
        sessionId = Some("s-b2"),
        isAskMode = false,
        isCompactTurn = false,
        agentPath = "agent-under-test",
        round = 3
      )
      // Two MaxBatch-sized bursts ⇒ two forced flushes, plus a trailing delta
      // flushed by Done ⇒ three frames. The 2nd/3rd must continue the block
      // sequence, not restart it (BatchState.marked survives take()).
      burst = Stream((1 to StreamBatching.MaxBatch).map(i => StreamChunk.TextDelta(s"x$i"))*)
      _ <- (burst ++ burst ++ Stream(StreamChunk.TextDelta("tail"), StreamChunk.Done(None, None, None, None)))
        .through(p).compile.drain
      ms <- marks(ref)
      blocks = ms.map(_._3)
    yield assert(
      blocks.size >= 3 && blocks == blocks.sorted && blocks.distinct.size == blocks.size,
      s"block must strictly increase across flushes; got=$blocks"
    )
  }

  /** T7：round 由调用方给（= 本 turn 内第几个 LLM 轮次）——同一管道内恒定，
   *  不同管道（不同 round）各自独立编号。 */
  test("StreamBatching T7 (B2): round is taken from the caller, per-pipeline") {
    for
      r1 <- framesRef
      r2 <- framesRef
      mk = (ref: Ref[IO, Vector[Json]], round: Int) =>
        StreamBatching.pipe(sender(ref), false, Some("s-b2"), false, false, "a", round = round)
      _ <- Stream(StreamChunk.TextDelta("a1"), StreamChunk.Done(None, None, None, None)).through(mk(r1, 0)).compile.drain
      _ <- Stream(StreamChunk.TextDelta("b1"), StreamChunk.Done(None, None, None, None)).through(mk(r2, 4)).compile.drain
      m1 <- marks(r1)
      m2 <- marks(r2)
    yield assert(
      m1.forall(_._2 == 0) && m2.forall(_._2 == 4) && m1.map(_._3) == m2.map(_._3),
      s"each pipeline numbers its own block sequence under the caller's round; got m1=$m1 m2=$m2"
    )
  }
end StreamBatchingSpec
