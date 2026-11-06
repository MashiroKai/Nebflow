package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * 缺陷批 2026-11-05（作者报障逐字：「我们的 fallback 队列里肯定是有可用的模型在
 * 的，但是还是报了所有模型不可用，然后把后台 agent 冻结了」）——**候选资格与判词
 * 形态**的端到端判据。
 *
 * 现场读数（stand.log 2026-11-05 10:18–10:30，六条 delegate 同波冻结）：
 *   · `delegate-freeze ... All providers failed (2..5 attempt(s); families: auth,unknown)`
 *   · `Stream: all candidates exhausted ... candidates: cmdcode/deepseek/deepseek-v4.1-flash`
 *   · `Provider zhipu/glm-5.3-flash marked DOWN`（08:20 之后再无 UP 记录——整天再没
 *     被任何 delegate 试过一次）；`kimi/kimi-for-coding-highspeed soft-avoided 300000ms`
 *   ⇒ 病根不是「换链不换链」（403 之后确实换了下一根），而是**被跳过的候选会静默
 *   消失**：健康面/软回避窗挡掉的候选既不在链上、也不在任何文案里，于是作者看到的
 *   通知只剩「试过的那几根」，且「3 根里只试了 1 根」被说成 `All providers failed`。
 *
 * 本 spec 是作者五条验收的机械断言（隔离实例 = 真 HTTP mock + 独立端口 + Ref 配置，
 * 不碰 6138，起停自清）：
 *   T1 造 4 候选（2 必败 + 2 正常）⇒ 必须成功，前两个 failed、第三个 tried 成功；
 *   T2 造 1 个 auth 403 + 3 正常 ⇒ 必须成功（不许因 auth 终止整链）；
 *   T3 造全部候选都被跳过 ⇒ `all-skipped`/`no-eligible-providers`，**不是** all-providers-failed；
 *   T4 造真全败 ⇒ `all-providers-failed` 且通知带完整候选处置表（每根一行处置）；
 *   T5（今天事故形态）试了一部分 + 跳了一部分 ⇒ `partially-tried` 且通知里
 *      **不许出现** `All providers failed`，被跳的候选带原因列出；
 *   T6 反向：配置可见的候选被人为排除 ⇒ `silentExclusions` 必须检出（render 报警），
 *      且有原因的排除不得误报。
 *
 * 端口：全部 port 0（内核分配），互不冲突；收尾 guarantee 停 server。
 */
class FallbackEligibleProvidersSpec extends CatsEffectSuite:

  override val munitIOTimeout = 180.seconds

  private val anthropicJson =
    """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],""" +
      """"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}"""

  private val notFoundBody =
    """{"type":"error","error":{"type":"not_found_error","message":"model not found"}}"""

  private val auth403Body =
    """{"type":"error","error":{"type":"permission_error","message":"Invalid API key"}}"""

  private def sendBody(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.flush()
    os.close()
    exchange.close()

  /** mode: "ok" | "fail404" | "auth403"（403 ⇒ Auth，且 isQuotaError 判真 ⇒ 配额窗）。 */
  private def startMock(mode: String, hits: AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          mode match
            case "fail404" => sendBody(exchange, 404, notFoundBody)
            case "auth403" => sendBody(exchange, 403, auth403Body)
            case _         => sendBody(exchange, 200, anthropicJson)
      )
      server.start()
      server
    }

  /** 起一组 mock（pid → mode），跑断言体，收尾把 server 全停。 */
  private def withFarm[A](spec: List[(String, String)])(
    f: (Map[String, Int], Map[String, AtomicInteger]) => IO[A]
  ): IO[A] =
    for
      ref <- IO.ref(List.empty[HttpServer])
      started <- spec.traverse { case (pid, mode) =>
        val hits = new AtomicInteger(0)
        startMock(mode, hits).map(s => (pid, s.getAddress.getPort, hits, s))
      }
      _ <- ref.set(started.map(_._4))
      out <- f(
        started.map { case (pid, port, _, _) => pid -> port }.toMap,
        started.map { case (pid, _, hits, _) => pid -> hits }.toMap
      ).guarantee(ref.get.flatMap(ss => IO.blocking(ss.foreach(_.stop(0)))))
    yield out

  private def mkConfig(
    ports: Map[String, Int],
    disabled: Set[String] = Set.empty
  ): NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = ports.map { case (pid, port) =>
      pid -> ProviderConfig(
        baseUrl = s"http://127.0.0.1:$port",
        apiKey = "test",
        protocol = LlmProtocol.Anthropic,
        models = List(ModelConfig("m1")),
        disabled = if disabled.contains(pid) then Some(true) else None
      )
    }))

  private def req(
    sessionId: String,
    chain: Option[AgentModelConfig],
    pinSkips: List[(String, CandidateSkipCause)] = Nil
  ): LlmRequest =
    LlmRequest(
      messages = List(Message(MessageRole.User, Left("hi"))),
      sessionId = sessionId,
      agentId = s"$sessionId-agent",
      agentModel = chain,
      // 派发钉链剔除账本（缺陷批 2026-11-06）：模拟 spawn 侧（AgentSessionExecution
      // 单点）随请求下发的 `PinnedChain.preExcluded`。
      pinSkips = pinSkips
    )

  /** 跑一次非流面调用，连同 registry（读链）/ monitor（造健康面现状）一起回。 */
  private def runOnce(
    cfg: NebflowServiceConfig,
    r: LlmRequest,
    preDown: List[(String, String)] = Nil
  ): IO[(Either[Throwable, LlmResponse], List[String])] =
    for
      cfgRef <- Ref.of[IO, NebflowServiceConfig](cfg)
      overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
      (handle, registry, monitor, release) = triple
      _ <- preDown.traverse_ { case (pid, model) => monitor.markDown(pid, model, "simulated 429") }
      chain <- registry.getCandidatesForAgent(r.agentModel)
      res <- handle.send(r).attempt
      _ <- release
    yield (res, chain.map(c => s"${c.providerId}/${c.model}"))

  // =====================================================================
  // 验收 1：4 候选（2 必败 + 2 正常）⇒ 成功，前两个 failed、第三个 tried 成功
  // =====================================================================
  test("A1: 4 候选（2 必败 + 2 正常）⇒ 换链成功，第 3 根交付；前两根如实记为 failed") {
    val spec = List("p1" -> "fail404", "p2" -> "fail404", "p3" -> "ok", "p4" -> "ok")
    val chain = AgentModelConfig(
      preferred = Some("p1/m1"),
      fallbacks = List("p2/m1", "p3/m1", "p4/m1")
    )
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligible-a1", Some(chain))).map { case (res, chainRefs) =>
        assertEquals(
          chainRefs,
          List("p1/m1", "p2/m1", "p3/m1", "p4/m1"),
          "候选链必须是用户选的四根（不许静默缩水）"
        )
        val resp = res.getOrElse(fail(s"A1 必须成功，实际 = ${res.left.map(e => e.getMessage)}"))
        assertEquals(resp.meta.providerId, "p3", "第 3 根（p3）必须承接本次请求")
        val steps = resp.meta.fallbackChain.getOrElse(Nil)
        val failedRefs = steps.filter(_.reason.isDefined).map(s => s"${s.providerId}/${s.model}")
        assert(
          failedRefs.take(2) == List("p1/m1", "p2/m1"),
          s"前两根必须如实记为 failed（实际 $failedRefs）"
        )
        assert(
          steps.exists(s => s.providerId == "p3" && s.reason.isEmpty),
          s"第 3 根必须是成功那根（实际 $steps）"
        )
        assertEquals(hits("p1").get(), 1, "p1 应恰好被尝试 1 次（404 = Permanent，不重试同一根）")
        assertEquals(hits("p2").get(), 1, "p2 应恰好被尝试 1 次")
        assertEquals(hits("p3").get(), 1, "p3 应恰好被尝试 1 次并成功")
        assertEquals(hits("p4").get(), 0, "p4 不该被用到（前面已经成功）")
      }
    }
  }

  // =====================================================================
  // 验收 2：1 个 auth 403 + 3 正常 ⇒ 成功（auth 不许终止整链）
  // =====================================================================
  test("A2: 1 个 auth 403 + 3 个正常 ⇒ 必须成功（auth 只降级该候选，不终止整链）") {
    val spec = List("k1" -> "auth403", "k2" -> "ok", "k3" -> "ok", "k4" -> "ok")
    val chain = AgentModelConfig(
      preferred = Some("k1/m1"),
      fallbacks = List("k2/m1", "k3/m1", "k4/m1")
    )
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligible-a2", Some(chain))).map { case (res, _) =>
        val resp = res.getOrElse(fail(s"A2 必须成功（403 只降级该候选），实际 = ${res.left.map(_.getMessage)}"))
        assertEquals(resp.meta.providerId, "k2", "k1 auth 失败后必须由 k2 承接")
        val steps = resp.meta.fallbackChain.getOrElse(Nil)
        val k1 = steps.find(_.providerId == "k1").getOrElse(fail(s"k1 必须出现在失败记录里：$steps"))
        assertEquals(k1.reason, Some("Auth"))
        assertEquals(hits("k1").get(), 1, "403 是 Permanent（配额窗换链），不该重试同一根")
        assertEquals(hits("k2").get(), 1, "k2 必须被尝试并成功")
      }
    }
  }

  // =====================================================================
  // 验收 3：全部候选都被跳过 ⇒ all-skipped（不是 all-providers-failed）
  // =====================================================================
  test("A3: 全部候选都被跳过（provider 全 disabled）⇒ all-skipped，且不许说 All providers failed") {
    val spec = List("d1" -> "ok", "d2" -> "ok")
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports, disabled = Set("d1", "d2")), req("eligible-a3", None)).map {
        case (res, chainRefs) =>
          assertEquals(chainRefs, Nil, "全 disabled ⇒ 候选链必须为空")
          val err = res.left.getOrElse(fail("A3 必须失败（一个候选都不可入选）"))
          val msg = err.getMessage
          assert(err.isInstanceOf[FallbackExhaustedError], s"必须抛 FallbackExhaustedError，实际 $err")
          assert(
            msg.contains("all-skipped") || msg.contains("no-eligible-providers"),
            s"必须报 all-skipped / no-eligible-providers，实际:\n$msg"
          )
          assert(
            !msg.contains("All providers failed"),
            s"一个候选都没试时**不许**说 All providers failed（配置问题不是供应商问题）:\n$msg"
          )
          assert(msg.contains("[skipped] d1/m1 — not-configured (provider disabled)"), msg)
          assertEquals(hits("d1").get(), 0, "disabled 的 provider 一根请求都不该收到")
      }
    }
  }

  // =====================================================================
  // 验收 4：真全败 ⇒ all-providers-failed + 完整处置表
  // =====================================================================
  test("A4: 链上每根都被试过且全败 ⇒ all-providers-failed，通知带完整候选处置表") {
    val spec = List("f1" -> "fail404", "f2" -> "fail404", "f3" -> "fail404")
    val chain = AgentModelConfig(preferred = Some("f1/m1"), fallbacks = List("f2/m1", "f3/m1"))
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligible-a4", Some(chain))).map { case (res, chainRefs) =>
        assertEquals(chainRefs, List("f1/m1", "f2/m1", "f3/m1"))
        val err = res.left.getOrElse(fail("A4 必须全败"))
        val msg = err.getMessage
        assert(msg.startsWith("All providers failed"), s"真全败才允许这句话：\n$msg")
        assert(msg.contains("reason: all-providers-failed"), msg)
        List("f1/m1", "f2/m1", "f3/m1").foreach { ref =>
          assert(msg.contains(s"[tried]   $ref — model ModelNotFound"), s"$ref 必须带处置进表：\n$msg")
        }
        assert(msg.contains("chain 3: tried 3, skipped 0"), s"处置表计数必须如实：\n$msg")
        assert(msg.contains("expected from config: 3 ref(s) — none silently excluded"), msg)
        assert(!msg.contains("SILENTLY EXCLUDED"), msg)
        List("f1", "f2", "f3").foreach(p => assertEquals(hits(p).get(), 1, s"$p 必须恰好被尝试 1 次"))
      }
    }
  }

  // =====================================================================
  // T5：今天事故形态（试了一部分 + 跳了一部分）⇒ partially-tried
  // =====================================================================
  test("A5(事故形态): 3 根里只试了 2 根、1 根被健康面跳过 ⇒ partially-tried + 跳过原因进表，禁说全败") {
    val spec = List("m1p" -> "fail404", "m2p" -> "fail404", "m3p" -> "ok", "m4p" -> "ok")
    val chain = AgentModelConfig(
      preferred = Some("m1p/m1"),
      fallbacks = List("m2p/m1", "m3p/m1", "m4p/m1")
    )
    withFarm(spec) { (ports, hits) =>
      runOnce(
        mkConfig(ports),
        req("eligible-a5", Some(chain)),
        // 事故形态复刻：zhipu 类候选早间被 markDown，整天没被任何 delegate 试过
        preDown = List("m3p" -> "m1", "m4p" -> "m1")
      ).map { case (res, chainRefs) =>
        assertEquals(chainRefs.size, 4, "链上仍是四根（健康面过滤发生在请求闸，不是链构建）")
        val err = res.left.getOrElse(fail("A5 必须失败（两根必败 + 两根被跳过）"))
        val msg = err.getMessage
        assert(
          !msg.startsWith("All providers failed"),
          s"只试了 2/4 根时**不许**说 All providers failed（今天报障的误导源头）：\n$msg"
        )
        assert(msg.contains("reason: partially-tried"), msg)
        assert(msg.contains("2 candidate(s) were skipped WITHOUT being tried"), msg)
        assert(msg.contains("[skipped] m3p/m1 — health-down (simulated 429"), msg)
        assert(msg.contains("[skipped] m4p/m1 — health-down (simulated 429"), msg)
        // 假 DOWN 批（2026-11-05）：跳过原因现在必须写明「还差多久才被真实试一次」
        // ——「不许否决」的可读面（标签/原因不变，只是追加了半开试探读数）。
        assert(msg.contains("half-open trial #1 in 60s"), msg)
        assert(msg.contains("[tried]   m1p/m1"), msg)
        assertEquals(hits("m3p").get(), 0, "退避窗内被跳过的候选不该收到请求（不许把不否决做成永远重试）")
      }
    }
  }

  // =====================================================================
  // 验收 5（反向）：静默排除必须被检出
  // =====================================================================
  test("A6: 配置可见的候选被人为排除 ⇒ silentExclusions 检出并报警；有原因的排除不误报") {
    val spec = List("s1" -> "ok", "s2" -> "ok", "s3" -> "ok")
    withFarm(spec) { (ports, _) =>
      for
        cfgRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(ports))
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
        (_, registry, _, release) = triple
        expected <- registry.expectedCandidateRefs()
        _ <- release
      yield
        assertEquals(expected, List("s1/m1", "s2/m1", "s3/m1"), "配置可见全集必须完整（审计基准）")
        // 人为把 s2/s3 从链上摘掉且不给原因 ⇒ 必须检出
        val excluded = CandidateDisposition(
          chain = List("s1/m1"),
          tried = List("s1/m1"),
          skipped = Nil,
          expected = expected
        )
        assertEquals(excluded.silentExclusions, List("s2/m1", "s3/m1"), "静默排除必须被检出")
        val table = excluded.render()
        assert(table.contains("2 SILENTLY EXCLUDED"), table)
        assert(table.contains("this is a bug, not provider failure"), table)
        // 反向：记了原因就不算静默（不许误报合法跳过）
        val explained = excluded.copy(skipped = List(
          "s2/m1" -> CandidateSkipCause.HealthDown("statusCode: 429"),
          "s3/m1" -> CandidateSkipCause.ScheduleWindow("14:00-18:00")
        ))
        assertEquals(explained.silentExclusions, Nil)
        assert(!explained.render().contains("SILENTLY EXCLUDED"), explained.render())
    }
  }

  // =====================================================================
  // A7（派发钉链剔除，缺陷批 2026-11-06）：provider-concurrency 批移交的
  // `UTSC/glm-5.3-flash SILENTLY EXCLUDED（无原因剔除）` 家族——派发时刻因满载
  // 被链首扫描剔出的候选必须带 provider-full 原因进终局账本，不许静默消失。
  // 语义边界（如实钉死）：pin 剔的是**链首优先级**——reserveTier 会把被剔 ref
  // 追加回链尾当最后手段；账本记的是「派发那一刻它被跳过」这个事实（seeded
  // 层 first-wins：同一 ref 即使后来又被健康面挡住，原因仍 = provider-full）。
  // =====================================================================
  test("A7(派发钉链): 满载被剔的候选带 provider-full 原因进终局账本——不再 SILENTLY EXCLUDED") {
    val spec = List("p1" -> "ok", "p2" -> "fail404", "p3" -> "fail404")
    // 钉链物化形态（PinnedChain.toAgentModelConfig）：链首 = p2（p1 在派发时刻
    // 因满载被剔），尾链 = p3；请求时 reserveTier 把 p1 追加回链尾（最后手段）
    val pinnedChain = AgentModelConfig(preferred = Some("p2/m1"), fallbacks = List("p3/m1"))
    withFarm(spec) { (ports, hits) =>
      runOnce(
        mkConfig(ports),
        req(
          "eligible-a7",
          Some(pinnedChain),
          pinSkips = List("p1/m1" -> CandidateSkipCause.ProviderFull(10, 10))
        ),
        // p1/p3 健康面判死 ⇒ 三根里只有 p2 被真实尝试且败（partially-tried 形态）
        preDown = List("p1" -> "m1", "p3" -> "m1")
      ).map { case (res, chainRefs) =>
        assertEquals(
          chainRefs,
          List("p2/m1", "p3/m1", "p1/m1"),
          "链 = 钉链物化形态 + 储备层回补（pin 剔的是优先级，不是资格）"
        )
        val err = res.left.getOrElse(fail("A7 必须失败（p2 必败 + p1/p3 health-down）"))
        val msg = err.getMessage
        assert(
          !msg.startsWith("All providers failed"),
          s"只试了 1/3 根时不许说全败:\n$msg"
        )
        assert(msg.contains("reason: partially-tried"), s"试 1 跳 2 ⇒ partially-tried:\n$msg")
        assert(
          msg.contains("[skipped] p1/m1 — provider-full (10 of 10 actor slots in use at dispatch"),
          s"派发钉链剔除必须带原因进表（UTSC 形态根治判据）；且 seeded 层 first-wins，" +
            s"不被后来的 health-down 改写:\n$msg"
        )
        assert(!msg.contains("[skipped] p1/m1 — health-down"), s"first-wins：p1 的原因必须是 provider-full:\n$msg")
        assert(
          msg.contains("[skipped] p3/m1 — health-down (simulated 429"),
          s"健康面剔除带原因（半开试探倒数随时刻浮动，断言稳定前缀）:\n$msg"
        )
        assert(msg.contains("[tried]   p2/m1"), s"试过的候选进 tried 表:\n$msg")
        assert(msg.contains("chain 3: tried 1, skipped 2"), s"处置表计数必须如实:\n$msg")
        assert(msg.contains("expected from config: 3 ref(s) — none silently excluded"), s"三根全有账，无一静默:\n$msg")
        assert(!msg.contains("SILENTLY EXCLUDED"), s"有原因的剔除不得误报成静默排除:\n$msg")
        assertEquals(hits("p1").get(), 0, "被健康面跳过的 p1 不该收到请求（尽管它在链尾）")
        assertEquals(hits("p3").get(), 0, "被健康面跳过的 p3 不该收到请求")
        assertEquals(hits("p2").get(), 1, "链首 p2 必须被真实尝试（404 Permanent 不重试同一根）")
      }
    }
  }

  // =====================================================================
  // A8（钉链语义边界·正向）：pin 剔的是链首优先级——满载 provider 的候选经
  // reserveTier 回补链尾，前面的根全败时它仍被真实尝试并承接（最后手段）。
  // =====================================================================
  test("A8(钉链回补): 派发时满载被剔的候选回补链尾——前面的根全败时它仍被真实尝试") {
    val spec = List("p1" -> "ok", "p2" -> "fail404", "p3" -> "fail404")
    val pinnedChain = AgentModelConfig(preferred = Some("p2/m1"), fallbacks = List("p3/m1"))
    withFarm(spec) { (ports, hits) =>
      runOnce(
        mkConfig(ports),
        req(
          "eligible-a8",
          Some(pinnedChain),
          pinSkips = List("p1/m1" -> CandidateSkipCause.ProviderFull(10, 10))
        )
      ).map { case (res, _) =>
        val resp = res.getOrElse(fail(s"A8 必须成功（p1 经储备层回补承接），实际 = ${res.left.map(_.getMessage)}"))
        assertEquals(resp.meta.providerId, "p1", "p2/p3 全败后必须由链尾回补的 p1 承接")
        assertEquals(hits("p1").get(), 1, "p1 必须被真实尝试恰 1 次")
        assertEquals(hits("p2").get(), 1, "链首 p2 先试")
        assertEquals(hits("p3").get(), 1, "尾链 p3 次之")
      }
    }
  }
end FallbackEligibleProvidersSpec
