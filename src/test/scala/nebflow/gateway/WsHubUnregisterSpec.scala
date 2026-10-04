package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

/**
 * perf-481 A1/A2 rework — the mechanical guard for the two faces the verifier
 * caught as UNOBSERVABLE, not merely wrong.
 *
 * WHY THIS SPEC EXISTS (and why it is not a duplicate of anything):
 *   · A1's regression was `unregister` detaching only `connsRef`. Every unit
 *     test passed, because no test asserted the one thing that was broken:
 *     "after unregister, this callback must never be invoked again". A leak of
 *     one listener per headless turn is invisible to a net that only checks
 *     registration. So the first test below asserts the CALL COUNT after
 *     unregister, not the registry size.
 *   · A2's failing criterion was 「溢出计数非零 ∧ 队列长度有界」being
 *     UNREADABLE anywhere (the counters had zero consumers). The read point is
 *     now `WsHub.outboundStats` / `outboundStatsJson`; these tests pin its shape
 *     and its instance-locality (a fresh hub reads zero — no cross-instance
 *     static bleed), so the /api/health/conn face cannot silently lose a field.
 *
 * Mutation check (what turns these red): make `unregister` drop only one face
 * again ⇒ test 1 red; make the counters `object`-level statics again ⇒ test 3
 * red (the second hub would see the first hub's counts); drop a field from
 * `outboundStatsJson` ⇒ test 4 red.
 */
class WsHubUnregisterSpec extends CatsEffectSuite:

  private def hub: IO[WsHub] = IO(new WsHub())

  // ── A1: the卸载面对称性判据（注销后不得再被调用）────────────────────────

  test("unregister detaches BOTH faces: an unregistered listener is never called again"):
    for
      h <- hub
      calls <- Ref.of[IO, Int](0)
      connCalls <- Ref.of[IO, Int](0)
      listenerId <- h.registerListener(_ => calls.update(_ + 1))
      connId <- h.register((_, _) => connCalls.update(_ + 1))
      conns1 <- h.connectionCount
      ls1 <- h.listenerCount
      _ <- h.broadcast(Json.obj("type" -> "hello".asJson))
      beforeUnregister <- calls.get
      _ <- h.unregister(listenerId)
      _ <- h.unregister(connId)
      _ <- h.broadcast(Json.obj("type" -> "hello".asJson))
      afterUnregister <- calls.get
      connAfter <- connCalls.get
      conns2 <- h.connectionCount
      ls2 <- h.listenerCount
    yield
      assertEquals(conns1, 1, "连接已注册")
      assertEquals(ls1, 1, "监听者已注册")
      assertEquals(beforeUnregister, 1, "注销前：一次 broadcast 调用一次监听者")
      // 🔴 判据本体：注销后**不再被调用**（A1 回归的机械反证）。
      assertEquals(afterUnregister, 1, "注销后监听者绝不能再被调用（A1 泄漏判据）")
      assertEquals(connAfter, 1, "注销后连接回调同样不再被调用")
      assertEquals(conns2, 0, "连接面归零")
      assertEquals(ls2, 0, "监听者面归零（两面共用 unregister）")

  test("unregister is idempotent and a no-op for an unknown id"):
    for
      h <- hub
      calls <- Ref.of[IO, Int](0)
      id <- h.registerListener(_ => calls.update(_ + 1))
      _ <- h.unregister(id)
      _ <- h.unregister(id)
      _ <- h.unregister("never-registered")
      ls <- h.listenerCount
      _ <- h.broadcast(Json.obj("type" -> "x".asJson))
      n <- calls.get
    yield
      assertEquals(ls, 0, "重复注销 / 未知 id 都是 no-op（既有 504 路径的重复注销语义不变）")
      assertEquals(n, 0, "no-op 期间零调用")

  test("listener count does not grow once per turn (the A1 leak's exact shape)"):
    // TurnEndpoint: one registerListener per headless turn, unregister at turn end.
    // Symmetric unload ⇒ the count must be flat across 20 turns, never 0 → 20.
    for
      h <- hub
      _ <- (1 to 20).toList.traverse_ { _ =>
        h.registerListener(_ => IO.unit).flatMap(h.unregister)
      }
      finalCount <- h.listenerCount
    yield assertEquals(finalCount, 0, "20 个 turn 之后监听者数必须仍是 0（1:1 累加 = A1 漏监听者）")

  test("connections and listeners share one id space, so one unregister clears its own entry only"):
    for
      h <- hub
      a <- h.registerListener(_ => IO.unit)
      b <- h.registerListener(_ => IO.unit)
      _ <- h.unregister(a)
      left <- h.listenerCount
    yield assertEquals(left, 1, "只摘自己的条目，不误伤同空间的其他 id")

  // ── A2: 溢出读点（实例级、形状固定）────────────────────────────────────

  test("outboundStats is instance-local and starts at zero"):
    for
      h1 <- hub
      h2 <- hub
      _ <- IO(h1.noteOverflowDrop())
      _ <- IO(h1.noteOverflowDrop())
      _ <- IO(h1.noteOverflowClose())
      s1 <- IO(h1.outboundStats(1024))
      s2 <- IO(h2.outboundStats(1024))
    yield
      assertEquals((s1.capacity, s1.overflowDrops, s1.overflowCloses), (1024, 2L, 1L), "实例 1 读到自己的计数")
      assertEquals((s2.overflowDrops, s2.overflowCloses), (0L, 0L), "实例 2 读不到实例 1 的计数（非 static）")

  test("outboundStatsJson carries capacity + both counters (the /api/health/conn shape)"):
    // Capacity is passed in (the read point is parameterized by the caller's queue
    // bound, so the health face reports the bound the shipped routes actually use,
    // not a second hardcoded copy).
    for
      h <- hub
      _ <- IO(h.noteOverflowDrop())
      j <- IO(h.outboundStatsJson(1024))
    yield
      val c = j.hcursor
      assertEquals(c.get[Int]("capacity").toOption, Some(1024))
      assertEquals(c.get[Long]("overflowDrops").toOption, Some(1L))
      assertEquals(c.get[Long]("overflowCloses").toOption, Some(0L))

  test("broadcast hands connections pre-serialized text and the frame class (A1 mechanism)"):
    // The mechanism A1 exists for: serialization happens ONCE in the hub, and the
    // connection callback receives the already-serialized string plus the frame
    // class — it must NOT receive a Json it would have to serialize again.
    for
      h <- hub
      seen <- Ref.of[IO, List[(String, Boolean)]](Nil)
      _ <- h.register((text, isStream) => seen.update(_ :+ (text, isStream)))
      _ <- h.broadcast(Json.obj("type" -> "textDelta".asJson, "delta" -> "abc".asJson))
      _ <- h.broadcast(Json.obj("type" -> "configUpdated".asJson))
      got <- seen.get
    yield
      assertEquals(
        got,
        List(
          ("""{"type":"textDelta","delta":"abc"}""", true),
          ("""{"type":"configUpdated"}""", false)
        ),
        "连接回调收到的是已序列化文本 + 帧分类（流式增量 vs 控制帧）"
      )
end WsHubUnregisterSpec
