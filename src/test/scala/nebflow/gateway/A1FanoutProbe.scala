package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

import scala.collection.mutable

/**
 * perf-481 A1/A2 instrument — drives the SHIPPED [[WsHub]] (not a
 * re-implementation) and prints machine-readable readings.
 *
 * WHY A TUNABLE INSTRUMENT AND NOT JUST A UNIT TEST: the A1 regression the
 * verifier caught (one leaked listener per headless turn, zero reclamation) was
 * invisible to the unit net because there was no READ POINT for it. The rework
 * added `connectionCount` / `listenerCount` / `outboundStats` precisely so this
 * class of regression is measurable from outside; this probe IS that measurement,
 * and it runs against the class the server loads. It also reproduces the
 * verifier's equal-payload arm (93 042 B) so a rework reading is comparable to
 * the pre-rework one.
 *
 * It is a probe, not a pass/fail gate: it prints readings and exits 0. The
 * numeric judgement (compare against the A1/A2 thresholds) belongs to the
 * wrap-up/report, not to this instrument.
 *
 * Why it lives in package `nebflow.gateway`: it exercises the overflow counters
 * through their `private[gateway]` note points, so the readout path it measures
 * is the same one `WebSocketRoutes` calls — not a test-only re-implementation.
 *
 * RUN (one command, foreground, self-exiting; JDK 23 pinned):
 *   sbt --batch 'Test/runMain nebflow.gateway.A1FanoutProbe --leaks 0,100,500,2000,8000 --reps 30'
 */
object A1FanoutProbe:

  private var argv: Array[String] = Array.empty

  private def arg(name: String, dflt: String): String =
    val i = argv.indexOf("--" + name)
    if i >= 0 && i + 1 < argv.length then argv(i + 1) else dflt

  private def pct(xs: Seq[Double], p: Double): Double =
    if xs.isEmpty then Double.NaN
    else
      val s = xs.sorted
      val idx = math.min(s.size - 1, math.max(0, math.ceil(p / 100.0 * s.size).toInt - 1))
      s(idx)

  /** Boxed counters, so the JIT cannot fold the payload away. */
  final class Sink:
    val calls = new java.util.concurrent.atomic.AtomicLong(0)
    val bytes = new java.util.concurrent.atomic.AtomicLong(0)
    def onFrame(text: String): Unit =
      calls.incrementAndGet()
      bytes.addAndGet(text.getBytes("UTF-8").length.toLong)

  private def timeIt(reps: Int)(body: => Unit): List[Double] =
    val out = mutable.ListBuffer.empty[Double]
    var i = 0
    while i < reps do
      val t0 = System.nanoTime()
      body
      out += (System.nanoTime() - t0) / 1e6
      i += 1
    out.toList

  private def ms(xs: Seq[Double]): String =
    f"p50=${pct(xs, 50)}%.4f p95=${pct(xs, 95)}%.4f p99=${pct(xs, 99)}%.4f max=${xs.max}%.4f"

  /** The verifier's equal-payload leg (93 042 B): serialization cost is the point. */
  private def bigPayload: Json =
    Json.obj(
      "type" -> "configData".asJson,
      "tools" -> Json.arr(
        (1 to 90).map(i => Json.obj("name" -> s"tool-$i".asJson, "description" -> ("x" * 1000).asJson))*
      )
    )

  def main(argvIn: Array[String]): Unit =
    argv = argvIn
    val leaks = arg("leaks", "0,100,500,2000,8000").split(",").map(_.trim.toInt).toList
    val reps = arg("reps", "30").toInt

    println("== perf-481 A1/A2 probe (shipped nebflow.gateway.WsHub) ==")
    println(s"reps=$reps leaks=${leaks.mkString(",")}")

    // ── 1. unregister symmetry: the A1 read point, mechanically ─────────────
    val symmetry = (for
      h <- IO(new WsHub())
      listenerCalls = new Sink
      connCalls = new Sink
      listenerId <- h.registerListener(j => IO { listenerCalls.onFrame(j.noSpaces) })
      connId <- h.register((t, _) => IO { connCalls.onFrame(t) })
      n1 <- h.listenerCount
      c1 <- h.connectionCount
      _ <- h.broadcast(Json.obj("type" -> "ping".asJson))
      before <- IO(listenerCalls.calls.get())
      _ <- h.unregister(listenerId)
      _ <- h.unregister(connId)
      _ <- h.broadcast(Json.obj("type" -> "ping".asJson))
      after <- IO(listenerCalls.calls.get())
      connAfter <- IO(connCalls.calls.get())
      n2 <- h.listenerCount
      c2 <- h.connectionCount
      // the leak's exact shape: 20 turns, one listener each, unregistered at end
      _ <- (1 to 20).toList.traverse_(_ => h.registerListener(_ => IO.unit).flatMap(h.unregister))
      after20 <- h.listenerCount
    yield (n1, c1, before, after, connAfter, n2, c2, after20)).unsafeRunSync()

    val (n1, c1, before, after, connAfter, n2, c2, after20) = symmetry
    println("[1] unregister symmetry")
    println(s"    listeners_after_register=$n1 connections_after_register=$c1")
    println(s"    listener_calls_before_unregister=$before listener_calls_after_unregister=$after")
    println(s"    conn_calls_after_unregister=$connAfter")
    println(s"    listeners_after_unregister=$n2 connections_after_unregister=$c2")
    println(s"    listeners_after_20_turns=$after20   (must be 0; 20 = the A1 leak)")

    // ── 2. amplification law: one leaked listener is called on EVERY broadcast ──
    println("[2] amplification law (a listener that survives unregister is called per broadcast)")
    for leaked <- leaks do
      val hub = new WsHub()
      val sink = new Sink
      val (calls, times) = (for
        _ <- hub.register((t, _) => IO { sink.onFrame(t) })
        // `leaked` listeners registered and then unregistered. With the FIXED
        // unregister they are gone (calls == 1 per broadcast, the connection).
        // With the OLD one-face unregister they would all persist ⇒ calls == 1+leaked.
        // The probe prints the count either way, so the law is directly readable.
        _ <- (1 to leaked).toList.traverse_ { _ =>
          hub.registerListener(j => IO { sink.onFrame(j.noSpaces) }).flatMap(hub.unregister)
        }
        _ <- IO { sink.calls.set(0); sink.bytes.set(0) }
        times = timeIt(math.max(reps, 10)) {
          hub.broadcast(Json.obj("type" -> "textDelta".asJson, "delta" -> ("y" * 300).asJson)).unsafeRunSync()
        }
        c = sink.calls.get()
      yield (c, times)).unsafeRunSync()
      println(f"    leaked_registered_and_unregistered=$leaked%-6d calls_per_broadcast=$calls%6d  ${ms(times)}")

    // ── 3. fan-out ladder: equal payload, N = 1..16 ─────────────────────────
    println("[3] fan-out ladder (equal payload 93 042 B, serialization must be once)")
    val payloadBytes = bigPayload.asJson.noSpaces.getBytes("UTF-8").length
    println(s"    payload_bytes=$payloadBytes")
    for n <- List(1, 2, 4, 8, 16) do
      val hub = new WsHub()
      val sink = new Sink
      val (calls, frameBytes, conns, times) = (for
        _ <- (1 to n).toList.traverse_(_ => hub.register((t, _) => IO { sink.onFrame(t) }))
        _ <- IO { sink.calls.set(0); sink.bytes.set(0) }
        // warm-up (JIT) then measure
        _ <- (1 to 3).toList.traverse_(_ => hub.broadcast(bigPayload))
        _ <- IO { sink.calls.set(0); sink.bytes.set(0) }
        times = timeIt(reps)(hub.broadcast(bigPayload).unsafeRunSync())
        c = sink.calls.get()
        fb = sink.bytes.get() / math.max(1, c)
        conns <- hub.connectionCount
      yield (c, fb, conns, times)).unsafeRunSync()
      println(f"    n=$n%-3d calls=$calls%4d frame_bytes=$frameBytes%6d conns=$conns  ${ms(times)}")

    // ── 4. A2 read point: outbound overflow counters are alive and instance-local ──
    println("[4] outbound stats read point (A2)")
    val (s1, s2, j) = (for
      h1 <- IO(new WsHub())
      h2 <- IO(new WsHub())
      _ <- IO { h1.noteOverflowDrop(); h1.noteOverflowDrop(); h1.noteOverflowClose() }
      a = h1.outboundStats(1024)
      b = h2.outboundStats(1024)
      jj = h1.outboundStatsJson(1024)
    yield (a, b, jj)).unsafeRunSync()
    println(s"    hub1=$s1 hub2=$s2 json=${j.noSpaces}")

    // ── 5. frame classification policy (fail-closed) ────────────────────────
    println("[5] isStreamFrame policy")
    val cases = List(
      "textDelta" -> true,
      "thinkingDelta" -> true,
      "thinkingSignature" -> true,
      "agentThinking" -> true,
      "toolArgDelta" -> true,
      "askTextDelta" -> true,
      "configUpdated" -> false,
      "configData" -> false,
      "serverConfig" -> false,
      "notRegisteredType" -> false
    )
    cases.foreach { (t, expected) =>
      val got = WsHub.isStreamFrame(Json.obj("type" -> t.asJson))
      val mark = if got == expected then "ok" else "MISMATCH"
      println(f"    $t%-20s stream=$got%-5s expected=$expected%-5s $mark")
    }

    println("== end (exit 0) ==")
    sys.exit(0)
  end main
end A1FanoutProbe
