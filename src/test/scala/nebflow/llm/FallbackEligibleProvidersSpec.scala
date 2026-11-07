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
 * **fallback 废除批改写（作者令 2026-11-06 20:36「不再支持模型fallback」，逐条申报）**：
 *   - A1 / A2 / A4 **重写**为链深恒 1 = preferred 单候选的耗尽语义：唯一根必败 ⇒
 *     all-providers-failed 处置表如实；核心新断言 = 配置里未被引用的 provider
 *     **一根请求都不许收到**（不暗中回补）；
 *   - A5 **重写**为「唯一候选被健康面跳过」⇒ all-skipped（不许说 All providers
 *     failed）+ 静默排除审计基准收窄的回归面（未引用 provider 不得误报）——原
 *     「partially-tried」多根形态随 fallback 退役；
 *   - A7 **整条删除**：派发钉链剔除（pinSkips / preExcluded）只在链深 > 1 时产生，
 *     链深恒 1 后结构性不可达（满 ⇒ None 无账）；原断言逐字见 git 历史；
 *   - A8 **整条删除**：储备层回补（reserveTier）本身就是 fallback，面已退役；
 *   - A3 / A6 断言原样保留（判据在新语义下依然成立：all-skipped 面 / 纯账本逻辑）；
 *   - 引擎配套：静默排除审计基准从「配置可见全集」收窄为「已解析候选集」
 *     （interface.scala 两处 `expected`）——未引用 provider 是有意不试，不是静默排除。
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

  /** A7/A8 删除后 pinSkips 不再被本 spec 使用（该面链深恒 1 后结构性不可达，申报见头注）。 */
  private def req(sessionId: String, chain: Option[AgentModelConfig]): LlmRequest =
    LlmRequest(
      messages = List(Message(MessageRole.User, Left("hi"))),
      sessionId = sessionId,
      agentId = s"$sessionId-agent",
      agentModel = chain
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
  // 验收 1（重写，fallback 废除批）：链深恒 1——唯一候选必败 ⇒ all-providers-failed；
  // 配置里未被引用的 provider 不得被暗中启用（核心新断言）；静默排除审计按
  // 「已解析候选集」收窄后不得误报未引用 provider（引擎配套面回归）。
  // =====================================================================
  test("A1(重写): 唯一候选必败 ⇒ all-providers-failed 处置表如实；其余配置 provider 零请求（不暗中回补）") {
    val spec = List("p1" -> "fail404", "p2" -> "fail404", "p3" -> "ok", "p4" -> "ok")
    val chain = AgentModelConfig(preferred = Some("p1/m1"), fallbacks = Nil)
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligible-a1", Some(chain))).map { case (res, chainRefs) =>
        assertEquals(
          chainRefs,
          List("p1/m1"),
          "候选链必须恰 = preferred 单根（fallbacks 数组退役，不追加任何候选）"
        )
        val err = res.left.getOrElse(fail(s"A1 必须失败（唯一候选必败且无 fallback 可换），实际 = ${res}")
        )
        val msg = err.getMessage
        assert(msg.startsWith("All providers failed"), s"唯一根被真实试过且全败才允许这句话：\n$msg")
        assert(msg.contains("reason: all-providers-failed"), msg)
        assert(msg.contains("[tried]   p1/m1 — model ModelNotFound"), s"唯一根必须带处置进表：\n$msg")
        // 合并批（2026-11-07）：审计基准 = **配置全集**（main 的 model-eligibility
        // 语义：provider 配置 ≠ 派发授权，未列入链者逐条记 `not-configured`），
        // 不是「已解析候选集」。计数因此如实为 skipped 3（p2/p3/p4 各一行原因），
        // 比静默排除更强——断言改为钉这条更严的口径。
        assert(msg.contains("chain 1: tried 1, skipped 3"), s"处置表计数必须如实：\n$msg")
        List("p2", "p3", "p4").foreach { p =>
          assert(
            msg.contains(s"[skipped] $p/m1 — not-configured (not in selected chain)"),
            s"未授权 provider 必须带原因进表（不得静默排除）：$p\n$msg"
          )
        }
        assert(
          msg.contains("expected from config: 4 ref(s) — none silently excluded"),
          s"审计基准 = 配置可见全集（4 根），零静默排除：\n$msg"
        )
        assert(!msg.contains("SILENTLY EXCLUDED"), msg)
        assertEquals(hits("p1").get(), 1, "p1 恰好被尝试 1 次（404 = Permanent，不重试同一根）")
        List("p2", "p3", "p4").foreach { p =>
          assertEquals(hits(p).get(), 0, s"$p 不在链上 ⇒ 一根请求都不该收到（fallback 废除）")
        }
      }
    }
  }

  test("configured but unlisted provider receives no request") {
    val spec = List("allowed" -> "ok", "configured-only" -> "ok")
    val chain = AgentModelConfig(preferred = Some("allowed/m1"), fallbacks = Nil)
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligibility-no-request", Some(chain))).map { case (res, refs) =>
        assertEquals(refs, List("allowed/m1"))
        assert(res.isRight, s"allowed model should complete: $res")
        assertEquals(hits("allowed").get(), 1)
        assertEquals(hits("configured-only").get(), 0, "provider config is not dispatch authorization")
      }
    }
  }

  // =====================================================================
  // 验收 2（重写，fallback 废除批）：唯一候选 auth 403 ⇒ all-providers-failed。
  // 原「auth 只降级该候选、不终止整链」面随 fallback 退役——没有下一根可换。
  // =====================================================================
  test("A2(重写): 唯一候选 auth 403 ⇒ all-providers-failed（Auth 族如实进表）；其余配置 provider 零请求") {
    val spec = List("k1" -> "auth403", "k2" -> "ok", "k3" -> "ok", "k4" -> "ok")
    val chain = AgentModelConfig(preferred = Some("k1/m1"), fallbacks = Nil)
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligible-a2", Some(chain))).map { case (res, _) =>
        val err = res.left.getOrElse(fail("A2 必须失败（唯一候选 auth 死，无下一根可换）"))
        val msg = err.getMessage
        assert(msg.startsWith("All providers failed"), s"唯一根被真实试过且全败：\n$msg")
        assert(msg.contains("[tried]   k1/m1 — auth Auth"), s"k1 必须以 auth 族进处置表：\n$msg")
        assertEquals(hits("k1").get(), 1, "403 是 Permanent（不重试同一根）")
        List("k2", "k3", "k4").foreach { p =>
          assertEquals(hits(p).get(), 0, s"$p 不得被暗中启用（fallback 废除）")
        }
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
  // 验收 4（重写，fallback 废除批）：单根形态的真全败 ⇒ all-providers-failed + 处置表
  // =====================================================================
  test("A4(重写): 唯一根被真实试过且 404 全败 ⇒ all-providers-failed，处置表如实（chain 1: tried 1, skipped 0）") {
    val spec = List("f1" -> "fail404", "f2" -> "fail404", "f3" -> "fail404")
    val chain = AgentModelConfig(preferred = Some("f1/m1"), fallbacks = Nil)
    withFarm(spec) { (ports, hits) =>
      runOnce(mkConfig(ports), req("eligible-a4", Some(chain))).map { case (res, chainRefs) =>
        assertEquals(chainRefs, List("f1/m1"), "链深恒 1 = preferred 单根")
        val err = res.left.getOrElse(fail("A4 必须全败"))
        val msg = err.getMessage
        assert(msg.startsWith("All providers failed"), s"真全败才允许这句话：\n$msg")
        assert(msg.contains("reason: all-providers-failed"), msg)
        assert(msg.contains("[tried]   f1/m1 — model ModelNotFound"), s"唯一根必须带处置进表：\n$msg")
        // 合并批（2026-11-07）：审计基准 = 配置全集 ⇒ f2/f3 各记一行原因（skipped 2）。
        assert(msg.contains("chain 1: tried 1, skipped 2"), s"处置表计数必须如实：\n$msg")
        List("f2", "f3").foreach { p =>
          assert(
            msg.contains(s"[skipped] $p/m1 — not-configured (not in selected chain)"),
            s"未授权 provider 必须带原因进表：$p\n$msg"
          )
        }
        assert(msg.contains("expected from config: 3 ref(s) — none silently excluded"), msg)
        assert(!msg.contains("SILENTLY EXCLUDED"), msg)
        assertEquals(hits("f1").get(), 1, "f1 必须恰好被尝试 1 次")
        List("f2", "f3").foreach { p =>
          assertEquals(hits(p).get(), 0, s"$p 未被链引用 ⇒ 零请求（fallback 废除，不得回补试根）")
        }
      }
    }
  }

  // =====================================================================
  // 验收 5（重写，fallback 废除批）：唯一候选被健康面跳过 ⇒ all-skipped（不许说
  // 全败）+ 半开试探读数进表；同时 = 静默排除审计基准收窄的回归面（配置里未被
  // 引用的 mx 不得被误报成 SILENTLY EXCLUDED——原「partially-tried」多根形态随
  // fallback 退役，重写申报见头注）。
  // =====================================================================
  test("A5(重写): 唯一候选被健康面跳过 ⇒ all-skipped + 跳过原因进表，禁说全败；未引用 provider 零请求零误报") {
    val spec = List("m1p" -> "ok", "mx" -> "ok")
    val chain = AgentModelConfig(preferred = Some("m1p/m1"), fallbacks = Nil)
    withFarm(spec) { (ports, hits) =>
      runOnce(
        mkConfig(ports),
        req("eligible-a5", Some(chain)),
        // 唯一候选早间被 markDown（事故形态的单根版：整天没被任何会话试过）
        preDown = List("m1p" -> "m1")
      ).map { case (res, chainRefs) =>
        assertEquals(chainRefs, List("m1p/m1"), "链深恒 1 = preferred 单根（健康面过滤发生在请求闸，不是链构建）")
        val err = res.left.getOrElse(fail("A5 必须失败（唯一候选被健康面跳过）"))
        val msg = err.getMessage
        assert(
          !msg.contains("All providers failed"),
          s"一根都没试时**不许**说 All providers failed（报障的误导源头）：\n$msg"
        )
        assert(msg.contains("reason: all-skipped"), msg)
        assert(msg.contains("every candidate was skipped"), msg)
        assert(msg.contains("[skipped] m1p/m1 — health-down (simulated 429"), msg)
        // 假 DOWN 批（2026-11-05）：跳过原因现在必须写明「还差多久才被真实试一次」。
        assert(msg.contains("half-open trial #1 in 60s"), msg)
        // 合并批（2026-11-07）：审计基准 = 配置全集 ⇒ 未引用的 mx 也会以
        // `not-configured (not in selected chain)` 记一行（**带原因**，不是静默）；
        // 「零请求零误报」仍由下面的 hits(mx)==0 钉住。
        assert(
          msg.contains("expected from config: 2 ref(s) — none silently excluded"),
          s"审计基准 = 配置可见全集（2 根），未引用者带原因进表：\n$msg"
        )
        assert(
          msg.contains("[skipped] mx/m1 — not-configured (not in selected chain)"),
          s"未授权 provider 必须带原因进表（不得静默排除）：\n$msg"
        )
        assert(!msg.contains("SILENTLY EXCLUDED"), msg)
        assertEquals(hits("m1p").get(), 0, "退避窗内被跳过的候选不该收到请求（不许把不否决做成永远重试）")
        assertEquals(hits("mx").get(), 0, "mx 未被链引用 ⇒ 零请求（fallback 废除）")
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
  // A7（派发钉链剔除）：pin 路径记录的候选若不在该 agent 的授权链中，
  // 仅保留排除原因，不可通过储备层回补为请求候选。
  // ==============================================================end FallbackEligibleProvidersSpec
