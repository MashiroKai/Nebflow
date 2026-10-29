package nebflow.llm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import nebflow.shared.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 429 反应式自适应并发限流器 spec（作者令 2026-10-29 21:32）——验收面：
 *   - T1/T1b 降档阶梯（429 连续触发 → 90/70/50 → 硬下限，档内 limit 单调不增）；
 *   - T2 回松阶梯（静默窗逐档升、每档消耗一窗；中途 429 立即再降）；
 *   - T3 FIFO 排队公平性（按到达顺序放行、零拒绝）；
 *   - T4 非账户级 429 / 配额体 / 非 RateLimit 不降档；
 *   - T5/T6 总闸关 = 透传零行为 + 配置热读；
 *   - T7 GatedAdapter 端到端（排队而非拒绝、限流后并发 ≤ cap）；
 *   - T8 多 provider（账户键）互不牵连；
 *   - T9 健康面读数；T10 配置半节解码。
 */
class AdaptiveThrottleSpec extends CatsEffectSuite:

  private val BaseUrl = "https://api.zhipu.test/v1"
  private val OtherBaseUrl = "https://api.kimi.test/v1"

  /** 429 账户级实证形态（zhipu 1302，2026-10-29 stand.log 同形）。 */
  private val account429Body =
    """statusCode: 429, response: {"type":"error","error":{"type":"rate_limit_error","code":"1302",""" +
      """"message":"[1302][您的账户已达到速率限制，请您控制请求频率]"}}"""

  /** 429 配额体（zhipu 1308 计划性额度——换链腿，绝不触发降档）。 */
  private val quota429Body =
    """statusCode: 429, response: {"type":"error","error":{"type":"rate_limit_error","code":"1308",""" +
      """"message":"[1308][已达到 5 小时的使用上限。]"}}"""

  /** 429 单请求类（无账户级标记——走既有 retry，不降档）。 */
  private val generic429Body = "statusCode: 429, response: rate limit exceeded for this request"

  private def configRef(
    enabled: Boolean,
    quietWindowMs: Long = 60_000L,
    hardFloor: Int = 2
  ): Ref[IO, NebflowServiceConfig] =
    Ref.unsafe(
      NebflowServiceConfig(
        llm = ServiceLlmConfig(
          providers = Map(
            "zhipu" -> ProviderConfig(BaseUrl, "k", LlmProtocol.OpenAI),
            "kimi" -> ProviderConfig(OtherBaseUrl, "k", LlmProtocol.OpenAI)
          ),
          adaptiveThrottle = Some(
            AdaptiveThrottleConfig(
              enabled = Some(enabled),
              hardFloor = Some(hardFloor),
              quietWindowMs = Some(quietWindowMs)
            )
          )
        )
      )
    )

  private def enable(ref: Ref[IO, NebflowServiceConfig], flag: Boolean): IO[Unit] =
    ref.update(c =>
      c.copy(llm = c.llm.copy(adaptiveThrottle = c.llm.adaptiveThrottle.map(_.copy(enabled = Some(flag))))))

  private def limitOf(t: AdaptiveThrottle, key: String = BaseUrl): IO[Option[Int]] =
    t.snapshot.map(_.get(key).flatMap(_.limit))

  // ════════ T1 降档阶梯（纯状态机）══════════════════════════════════════

  test("T1 状态机：429 连续触发 → 90%/70%/50% → 硬下限；基线取首刀在飞数，档内不回升") {
    val l = AdaptiveThrottleMachine.Ladder(List(90, 70, 50), 2, 60_000L)
    val s0 = AdaptiveThrottleMachine.State(0, 0, 0L)
    val (s1, d1) = AdaptiveThrottleMachine.onAccount429(s0, inFlight = 10, nowMs = 1000L, l)
    assertEquals(d1.map(_.newLimit), Some(9)) // 10×0.9 = 9（首刀基线 = 在飞 10）
    assertEquals(s1.refLimit, 10)
    // 第二刀时在飞已只剩 4：基线不重估（若按 4×0.7 会「越限越松」）——仍 10×0.7
    val (s2, d2) = AdaptiveThrottleMachine.onAccount429(s1, inFlight = 4, nowMs = 2000L, l)
    assertEquals(d2.map(_.newLimit), Some(7))
    val (s3, d3) = AdaptiveThrottleMachine.onAccount429(s2, inFlight = 4, nowMs = 3000L, l)
    assertEquals(d3.map(_.newLimit), Some(5))
    val (s4, d4) = AdaptiveThrottleMachine.onAccount429(s3, inFlight = 4, nowMs = 4000L, l)
    assertEquals(d4.map(_.newLimit), Some(2)) // 硬下限
    assertEquals(s4.rung, 4)
    val (s5, d5) = AdaptiveThrottleMachine.onAccount429(s4, inFlight = 4, nowMs = 5000L, l)
    assertEquals(d5, None) // 终档不再降（交给既有 health/retry 路径）
    assertEquals(s5.rung, 4)
    // 首刀在飞 0（429 波已排空）⇒ 基线 = 硬下限，不出现 0 上限
    val (_, d0) = AdaptiveThrottleMachine.onAccount429(s0, inFlight = 0, nowMs = 1L, l)
    assertEquals(d0.map(_.newLimit), Some(2))
  }

  // ════════ T1b 降档阶梯（运行面：在飞读数含 429 请求本身）══════════════

  test("T1b 运行面：在飞 10 收 429 → cap 9；新请求排队受 cap 约束；再收 429 → 7 → 5 → floor") {
    val t = new AdaptiveThrottle(configRef(enabled = true))
    for
      // 旁路段（不限）acquire 也计数：在飞 10 对首刀 429 可读
      permits <- (1 to 10).toList.traverse(_ => t.acquire(BaseUrl))
      s0 <- t.snapshot.map(_(BaseUrl))
      _ = assertEquals(s0.limit, None) // rung 0 = 不限
      _ = assertEquals(s0.inFlight, 10)
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      s1 <- limitOf(t)
      _ = assertEquals(s1, Some(9)) // 10×0.9
      _ <- permits.traverse_(_.release)
      drained0 <- t.snapshot.map(_(BaseUrl).inFlight)
      _ = assertEquals(drained0, 0)
      // 闸段下新请求：cap 9 ⇒ 前 9 张直过，第 10 张排队（零拒绝——等，不弃）
      batch <- (1 to 9).toList.traverse(_ => t.acquire(BaseUrl))
      tenthFiber <- t.acquire(BaseUrl).start
      _ <- IO.sleep(80.millis)
      queued <- t.snapshot.map(_(BaseUrl).queued)
      _ = assertEquals(queued, 1)
      inFlight9 <- t.snapshot.map(_(BaseUrl).inFlight)
      _ = assertEquals(inFlight9, 9)
      _ <- batch.traverse_(_.release)
      tenthPermit <- tenthFiber.joinWithNever
      _ <- tenthPermit.release
      // 档位阶梯继续（基线保持首刀在飞 10，不随在飞回落重估）
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      s2 <- limitOf(t)
      _ = assertEquals(s2, Some(7)) // 10×0.7
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      s3 <- limitOf(t)
      _ = assertEquals(s3, Some(5))
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      s4 <- t.snapshot.map(_(BaseUrl))
      _ = assertEquals(s4.limit, Some(2))
      _ = assertEquals(s4.rungLabel, "floor(2)")
      endInFlight <- t.snapshot.map(_(BaseUrl).inFlight)
      _ = assertEquals(endInFlight, 0) // 全部许可释放，计数归零
    yield ()
  }

  // ════════ T2 回松阶梯 ══════════════════════════════════════════════════

  test("T2 回松：静默窗逐档升（floor→50→70→90→不限）每档消耗一窗；中途 429 立即再降") {
    val quiet = 60_000L
    val t = new AdaptiveThrottle(configRef(enabled = true, quietWindowMs = quiet))
    val now = 1_000_000L
    for
      // 风暴：在飞 10，四刀压到硬下限（cap 2）
      permits <- (1 to 10).toList.traverse(_ => t.acquire(BaseUrl))
      _ <- (1 to 4).toList.traverse(_ =>
        t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body, now = now))
      floor <- limitOf(t)
      _ = assertEquals(floor, Some(2))
      _ <- permits.traverse_(_.release)
      // 静默不足一窗 ⇒ 不回松
      _ <- t.loosenTick(BaseUrl, now = now + quiet - 1)
      l0 <- limitOf(t)
      _ = assertEquals(l0, Some(2))
      // 满一窗 ⇒ 回松一档；再满一窗再回松（每档消耗一个观察窗）
      _ <- t.loosenTick(BaseUrl, now = now + quiet)
      l1 <- limitOf(t)
      _ = assertEquals(l1, Some(5)) // 50% 档：10×0.5
      _ <- t.loosenTick(BaseUrl, now = now + 2 * quiet)
      l2 <- limitOf(t)
      _ = assertEquals(l2, Some(7)) // 70% 档
      // 回松途中收到新 429 ⇒ 立即重新降档 + 静默钟重置
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body, now = now + 2 * quiet + 1)
      l3 <- limitOf(t)
      _ = assertEquals(l3, Some(5)) // 70% → 50%
      // 长静默（多窗）⇒ 逐档爬满停在不限档
      _ <- t.loosenTick(BaseUrl, now = now + 10 * quiet)
      l4 <- t.snapshot.map(_(BaseUrl))
      _ = assertEquals(l4.limit, None)
      _ = assertEquals(l4.rungLabel, "100%")
    yield ()
  }

  test("T2b 状态机：静默窗钟面消耗语义（quietSince += window，非静默重置为 now）") {
    val l = AdaptiveThrottleMachine.Ladder(List(90, 70, 50), 2, 100L)
    // rung 3（50%），quietSince=0：t=100 回松到 rung2（70%），钟面推进到 100
    val (s1, steps1) = AdaptiveThrottleMachine.loosen(AdaptiveThrottleMachine.State(3, 10, 0L), 100L, l)
    assertEquals(steps1.map(_.label), List("70%"))
    assertEquals(s1.quietSinceMs, 100L)
    // t=150（半窗）不再动；t=200 再回松一档
    val (s2, steps2) = AdaptiveThrottleMachine.loosen(s1, 150L, l)
    assertEquals(steps2, Nil)
    val (s3, steps3) = AdaptiveThrottleMachine.loosen(s2, 200L, l)
    assertEquals(steps3.map(_.label), List("90%"))
    assertEquals(s3.rung, 1)
    // 爬满停在不限档，后续 tick 空转
    val (s4, steps4) = AdaptiveThrottleMachine.loosen(s3, 1000L, l)
    assertEquals(steps4.map(_.label), List("100%"))
    assertEquals(s4.rung, 0)
    val (_, steps5) = AdaptiveThrottleMachine.loosen(s4, 2000L, l)
    assertEquals(steps5, Nil)
  }

  // ════════ T3 FIFO 排队公平性 ═══════════════════════════════════════════

  test("T3 FIFO：满闸下按到达顺序放行，全部请求最终完成（零拒绝）") {
    val t = new AdaptiveThrottle(configRef(enabled = true, hardFloor = 1))
    def labeled(i: Int, orderRef: Ref[IO, List[Int]]): IO[Unit] =
      t.acquire(BaseUrl).flatMap { p =>
        orderRef.update(_ :+ i) *> IO.sleep(100.millis) *> p.release
      }
    for
      // 造 cap=1 的闸段（在飞读数 0 ⇒ 基线 = 硬下限 1）
      _ <- (1 to 4).toList.traverse(_ => t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body))
      cap <- limitOf(t)
      _ = assertEquals(cap, Some(1))
      orderRef <- Ref.of[IO, List[Int]](Nil)
      // 按序入队 5 个请求：起跑间隔 20ms，持有 100ms ⇒ 2..5 在 1 释放前全部排队
      _ <- (1 to 5).toList.traverse { i => labeled(i, orderRef).start.flatMap(f => IO.sleep(20.millis).as(f)) }
      _ <- IO.sleep(900.millis)
      order <- orderRef.get
      _ = assertEquals(order, List(1, 2, 3, 4, 5)) // 严格 FIFO，无插队无丢弃
      drained <- t.snapshot.map(_(BaseUrl).inFlight)
      _ = assertEquals(drained, 0)
    yield ()
  }

  // ════════ T4 非账户级 429 / 配额体 / 非 RateLimit ═══════════════════════

  test("T4 非账户级 429、配额体 429、非 RateLimit 分类 → 一律不降档") {
    val t = new AdaptiveThrottle(configRef(enabled = true))
    for
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, generic429Body)
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, quota429Body)
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.ServerError, account429Body)
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, "RateLimit [1308] 已达到 5 小时的使用上限")
      s <- t.snapshot
      _ = assertEquals(s.get(BaseUrl), None) // 从未入闸段：无账户级 429 即无状态
      h <- t.healthJson
      _ = assertEquals(h, None)
    yield ()
  }

  test("T4b 判定单点：账户级码/文本命中，裸数字不误报") {
    val t = new AdaptiveThrottle(configRef(enabled = true))
    t.params.flatMap { p =>
      val hits1302 = t.isAccountLevel429("""{"error":{"code":"1302","message":"您的账户已达到速率限制"}}""", p)
      val hitsBracket = t.isAccountLevel429("RateLimit [1302] 您的账户已达到速率限制", p)
      val bareDigits = t.isAccountLevel429("request_id=abc1302def rate limited", p) // 裸数字文本不认
      val quotaShape = t.isAccountLevel429(quota429Body, p)
      IO {
        assertEquals(hits1302, true)
        assertEquals(hitsBracket, true)
        assertEquals(bareDigits, false)
        assertEquals(quotaShape, false)
      }
    }
  }

  // ════════ T5/T6 总闸与热读 ═════════════════════════════════════════════

  test("T5 总闸关 = 纯透传零行为（acquire 不排队、429 不建状态、健康面零键）") {
    val t = new AdaptiveThrottle(configRef(enabled = false))
    for
      permits <- (1 to 5).toList.traverse(_ => t.acquire(BaseUrl))
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      s <- t.snapshot
      _ = assertEquals(s.get(BaseUrl), None)
      h <- t.healthJson
      _ = assertEquals(h, None)
      _ <- permits.traverse_(_.release)
      s2 <- t.snapshot
      _ = assertEquals(s2, Map.empty[String, t.GateSnapshot])
    yield ()
  }

  test("T6 热读：enabled 翻转即刻生效（关→开→关，无重启）") {
    val ref = configRef(enabled = false)
    val t = new AdaptiveThrottle(ref)
    val now = System.currentTimeMillis() // 与 loosenTick 的真实钟面同基（注入过去时刻会误触回松）
    for
      p0 <- t.acquire(BaseUrl)
      _ = assertEquals(p0.gen, 0L) // 透传许可
      _ <- enable(ref, flag = true)
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body, now = now)
      gated <- t.acquire(BaseUrl) // 开闸后进入闸段（cap 2，在飞 0 < 2 直接过）
      _ = assert(gated.gen > 0)
      s1 <- limitOf(t)
      _ = assertEquals(s1, Some(2))
      _ <- enable(ref, flag = false)
      p2 <- t.acquire(BaseUrl)
      _ = assertEquals(p2.gen, 0L) // 再关 = 回透传
      _ <- gated.release *> p0.release *> p2.release
    yield ()
  }

  // ════════ T7 GatedAdapter 端到端 ═══════════════════════════════════════

  private class CountingAdapter(delayMs: Long) extends ProviderAdapter[IO]:
    val cur: Ref[IO, Int] = Ref.unsafe(0)
    val maxSeen: Ref[IO, Int] = Ref.unsafe(0)
    val completed: Ref[IO, Int] = Ref.unsafe(0)
    def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
      cur.update(_ + 1) *> cur.get.flatMap(c => maxSeen.update(_ max c)) *>
        IO.sleep(delayMs.millis) *>
        cur.update(_ - 1) *> completed.update(_ + 1).as(AdapterResponse("ok", Nil))
    def sendMessageStream(params: SendMessageParams): Stream[IO, StreamChunk] =
      Stream.exec(sendMessage(params).void)

  test("T7 GatedAdapter：降档后并发 ≤ cap 且全部请求完成（排队而非拒绝）") {
    val t = new AdaptiveThrottle(configRef(enabled = true))
    val raw1 = CountingAdapter(delayMs = 400) // 长请求：429 信号到达时 5 路仍在飞
    val gated1 = new GatedAdapter(raw1, BaseUrl, t)
    val req = SendMessageParams(messages = Nil, model = "glm-5")
    for
      // 降档前（不限档 = 旁路直通）：5 路并发全部同时进 raw adapter
      fibers1 <- (1 to 5).toList.traverse(_ => gated1.sendMessage(req).start)
      _ <- IO.sleep(150.millis) // 400ms 请求 ≫ 窗口：5 路同时在飞
      pre <- raw1.maxSeen.get
      _ = assertEquals(pre, 5)
      // 在飞 5 时降档一刀：cap = 5×0.9 = 4
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      cap <- limitOf(t)
      _ = assertEquals(cap, Some(4))
      _ <- fibers1.traverse_(_.joinWithNever)
      // 降档后再来 8 路：并发不得超过 4，且 8 路全部完成（排队而非拒绝）
      raw2 = CountingAdapter(delayMs = 20)
      gated2 = new GatedAdapter(raw2, BaseUrl, t)
      fibers2 <- (1 to 8).toList.traverse(_ => gated2.sendMessage(req).start)
      _ <- fibers2.traverse_(_.joinWithNever)
      mx <- raw2.maxSeen.get
      done <- raw2.completed.get
      _ = assert(mx <= 4, s"降档后并发 $mx 超过 cap 4")
      _ = assertEquals(done, 8)
      drained <- t.snapshot.map(_(BaseUrl).inFlight)
      _ = assertEquals(drained, 0)
    yield ()
  }

  // ════════ T8 多账户互不牵连 ════════════════════════════════════════════

  test("T8 多 provider（账户键）：A 账户 429 降档不影响 B 账户") {
    val t = new AdaptiveThrottle(configRef(enabled = true))
    for
      pA <- (1 to 6).toList.traverse(_ => t.acquire(BaseUrl))
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      capA <- limitOf(t)
      _ = assertEquals(capA, Some(5)) // 6×0.9 = 5（floor）
      capB <- limitOf(t, OtherBaseUrl)
      _ = assertEquals(capB, None) // B 从未收 429，保持不限
      pB <- (1 to 6).toList.traverse(_ => t.acquire(OtherBaseUrl))
      _ = assertEquals(pB.count(_.gen > 0), 0) // B 仍走旁路直通
      _ <- pA.traverse_(_.release) *> pB.traverse_(_.release)
    yield ()
  }

  // ════════ T9 健康面读数 ════════════════════════════════════════════════

  test("T9 healthJson：有闸才有键；载荷含 limit/rung/inFlight/queued") {
    val t = new AdaptiveThrottle(configRef(enabled = true))
    for
      none <- t.healthJson
      _ = assertEquals(none, None) // 未入闸段
      p <- (1 to 3).toList.traverse(_ => t.acquire(BaseUrl))
      _ <- t.onRateLimitSignal(BaseUrl, FailoverReason.RateLimit, account429Body)
      // cap 2：前 2 张新票直过，第 3 张排队
      extras <- (1 to 3).toList.traverse(_ => t.acquire(BaseUrl).start)
      _ <- IO.sleep(100.millis)
      some <- t.healthJson
      _ = assert(some.isDefined)
      gateCur = some.map(_.hcursor.downField("gates").downField("api.zhipu.test"))
      limit = gateCur.flatMap(_.downField("limit").as[Int].toOption)
      _ = assertEquals(limit, Some(2)) // 3×0.9 = 2（floor）
      queued = gateCur.flatMap(_.downField("queued").as[Int].toOption)
      _ = assertEquals(queued, Some(1))
      rung = gateCur.flatMap(_.downField("rung").as[String].toOption)
      _ = assertEquals(rung, Some("90%"))
      // 逐个 join+release（先放后收下一根——第 3 张靠前面的 release 放行）
      _ <- extras.traverse(f => f.joinWithNever.flatMap(p => p.release))
      _ <- p.traverse_(_.release)
    yield ()
  }

  // ════════ T10 配置解码 ═════════════════════════════════════════════════

  test("T10 配置：llm.adaptiveThrottle 半节解码为缺省而非抛；缺节 = None") {
    import io.circe.parser.*
    val half = parse("""{"llm":{"providers":{},"adaptiveThrottle":{"enabled":true}}}""")
      .flatMap(_.as[NebflowServiceConfig])
    assert(half.isRight)
    assertEquals(half.map(_.llm.adaptiveThrottle), Right(Some(AdaptiveThrottleConfig(enabled = Some(true)))))
    assertEquals(ThrottleParams.from(half.map(_.llm.adaptiveThrottle).toOption.flatten).enabled, true)
    assertEquals(ThrottleParams.from(None).enabled, false) // 缺节 = 关
    assertEquals(ThrottleParams.from(None).factorsPct, List(90, 70, 50))
    assertEquals(ThrottleParams.from(None).hardFloor, 2)
    assertEquals(ThrottleParams.from(None).quietWindowMs, 300_000L)
    val missing = parse("""{"llm":{"providers":{}}}""").flatMap(_.as[NebflowServiceConfig])
    assertEquals(missing.map(_.llm.adaptiveThrottle), Right(None))
  }
end AdaptiveThrottleSpec
