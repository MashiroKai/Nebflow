package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * 腿 b 的端到端判据面（作者令 2026-09-21 19:16）——**配额类触发换链，而非阻塞式等待**。
 *
 * 注入面 = 真实 Anthropic 协议 mock：配额类用**现读原文**错误体应答
 *   · 403 + `permission_error` / `5-hour usage limit`（kimi 2026-09-21 16:47 实证）
 *   · 429 + 上游 `code 1308`（zhipu 17:54 实证）
 * 链路 = `LlmInterface.sendStream`（真实候选链解析 → `filterCandidates` → 换链）。
 *
 * 改前（基线码）读数（红腿）：
 *   · T1：429 落 Transient 分支 ⇒ 睡 `OverloadBackoffMinMs`(60s) 重试同一 provider，
 *     之后才换链 ⇒ 单请求被一根额度死的 provider 阻塞 ~60s。
 *   · T2：链上 2 根配额死 ⇒ `tryCandidate(Nil)` → `attemptWithHealthCheck` 的
 *     `(Nil, down)` 分支 ⇒ `probeNow` + `waitForAnyUp` 等满预算(120s) ⇒
 *     `AllProvidersDownTimeout`（全部候选耗尽，且储备层第三根从未被咨询）≈ 180s。
 * 改后读数（绿腿）：配额类退出本轮候选（软回避窗，配额窗 ≫ 瞬时窗）⇒ 立即推进到
 * 下一根可用 provider（含新纳入的储备层）⇒ 毫秒级换链成功。
 *
 * 端口独立（18573/18575 / 18577/18579/18581），收尾自清（guarantee 停 server）。
 */
class QuotaChainSwitchSpec extends CatsEffectSuite:

  // ── B2 隔离（回归修复 2026-11-05：T2 红 = T1 配额避让窗写穿进真实库后水合回填）──
  // createLlm 内部构造 ProviderHealthMonitor(registry)（db=None）⇒ 走
  // `db.orElse(DbHost.peek(PathUtil.dataRoot))` 兜底——测试 JVM 里他套件的生产
  // 接线已把真实根 memoize 进 DbHost ⇒ peek 命中 ⇒ T1 的 qa/m1 避让窗写穿进真实
  // guard/provider-avoid.json，T2 新 monitor 水合回填 ⇒ qa 预避让被跳过（:262 红）。
  // createLlm 无 db 注入缝且本轮零生产改动 ⇒ 套件级换根（agent 域 prevRoot 既有
  // 惯例）：全程把 PathUtil.dataRoot 钉在临时根，peek 解析到无人开过的临时根 ⇒
  // None ⇒ 纯内存（= B2 前本套件形态），真实库零触达。
  private var savedDataRoot: Option[os.Path] = None

  override def beforeAll(): Unit =
    savedDataRoot = Some(PathUtil.dataRoot)
    PathUtil.setDataRoot(os.Path(Files.createTempDirectory("quota-chain-spec"), os.pwd))

  override def afterAll(): Unit = savedDataRoot.foreach(PathUtil.setDataRoot)

  // 红腿需吃满退避(60s) + 全灭闸预算(120s)；绿腿毫秒级完成（此为上限，非常态耗时）
  override val munitIOTimeout = 300.seconds

  private val PortQuota429a = 18573
  private val PortOk1 = 18575
  private val PortQuota403 = 18577
  private val PortQuota429b = 18579
  private val PortOk2 = 18581

  private val Kimi403QuotaBody =
    """{"error":{"type":"permission_error","message":"You've reached your 5-hour usage limit. Your quota will reset when the current 5-hour window ends."}}"""

  private val Zhipu429Quota1308Body =
    """{"type":"error","error":{"type":"rate_limit_error","code":"1308","message":"[1308][已达到 5 小时的使用上限。您的限额将在 2026-09-21 19:46:31 重置。]"},"request_id":"20260921175458ae12"}"""

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"

  private def textDelta(t: String) =
    s"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$t\"}}\n\n"

  private val okTail =
    Seq(
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  /** mode: "quota403" | "quota429" | "ok"（ok 用 label 标记交付方）。 */
  private def startMock(
    port: Int,
    mode: String,
    label: String,
    hits: AtomicInteger
  ): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          mode match
            case "quota403" => sendBody(exchange, 403, Kimi403QuotaBody)
            case "quota429" => sendBody(exchange, 429, Zhipu429Quota1308Body)
            case _ =>
              val body = (messageStart + textDelta(label) + okTail).getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
              exchange.sendResponseHeaders(200, 0)
              val os = exchange.getResponseBody
              os.write(body)
              os.flush()
              os.close()
              exchange.close()
      )
      server.start()
      server
    }

  private def sendBody(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.flush()
    os.close()
    exchange.close()

  private def mkConfig(spec: (String, Int, List[String])*): NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = spec.map { case (pid, port, models) =>
      pid -> ProviderConfig(
        baseUrl = s"http://127.0.0.1:$port",
        apiKey = "test",
        protocol = LlmProtocol.Anthropic,
        models = models.map(m => ModelConfig(m))
      )
    }.toMap))

  private def candidate(pid: String, model: String) =
    ModelCandidate(pid, ProviderConfig("http://localhost", "k", LlmProtocol.OpenAI), model)

  private def textOf(chunks: List[StreamChunk]): String =
    chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString

  private def remainingAvoidMs(avoids: Map[String, Long], k: String): Long =
    avoids.get(k).map(_ - System.currentTimeMillis()).getOrElse(0L)

  // ============================================================
  // T1（重写，fallback 废除批 2026-11-06 20:36）：配额类 429(1308) ⇒ 单候选软回避
  // + 静默等待（有界终局）；同 provider 不重试、后备不暗中承接。原「立即换链到 qb」
  // 的多根面随 fallback 退役（原断言逐字见 git 历史）。
  // ============================================================

  test("T1(重写): 429 + code 1308（配额类）⇒ 单候选软回避窗（≫瞬时窗）+ 静默等待终局；同 provider 不重试，后备零命中") {
    val hitsQuota = new AtomicInteger(0)
    val hitsOk = new AtomicInteger(0)
    var s1: HttpServer = null
    var s2: HttpServer = null
    val cfg = mkConfig(
      ("qa", PortQuota429a, List("m1")),
      ("qb", PortOk1, List("m1"))
    )
    val program =
      for
        a <- startMock(PortQuota429a, "quota429", "unused", hitsQuota)
        _ = s1 = a
        b <- startMock(PortOk1, "ok", "BB", hitsOk)
        _ = s2 = b
        out <- (for
          cfgRef <- Ref.of[IO, NebflowServiceConfig](cfg)
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
          (handle, _, monitor, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "provchain-quota-t1",
            agentId = "provchain-quota-t1-agent",
            agentModel = Some(AgentModelConfig(preferred = Some("qa/m1"), fallbacks = Nil)) // 链深恒 1
          )
          t0 <- IO.monotonic
          // fallback 废除批申报：单候选配额死 ⇒ 静默等待恢复面——给 8s 有界上界取终局。
          res <- handle.sendStream(req).compile.toList.attempt
            .timeoutTo(
              8.seconds,
              // 哨兵以 Left 捕获（raiseError 会让 IO 失败、下方断言面走不到）
              IO.pure(
                Left(new RuntimeException("all-down gate exceeded test bound")): Either[Throwable, List[StreamChunk]]
              )
            )
          t1 <- IO.monotonic
          states <- monitor.getStates
          avoids <- monitor.getAvoidUntil
          split <- monitor.filterCandidates(List(candidate("qa", "m1")))
          _ <- release
        yield (t1.toMillis - t0.toMillis, res, states, avoids, split))
          .guarantee(IO.blocking { if s1 != null then s1.stop(0); if s2 != null then s2.stop(0) })
      yield out

    program.map { case (elapsedMs, res, states, avoids, (upQa, downQa)) =>
      res match
        case Right(chunks) =>
          fail(s"唯一候选配额死 ⇒ 不得有根交付（后备已随 fallback 退役），实际成功 = ${textOf(chunks)}")
        case Left(_) => () // 终局形态 = 静默等待（全灭闸/测试上界）——下方软回避面钉死
      // 防阻塞面原样钉死：不睡 60s overload 退避（配额类 ⇒ 软回避窗立即生效）
      assert(
        elapsedMs < 9_000L,
        s"配额类不得阻塞在 60s 同 provider 退避（软回避立即生效 + 有界等待）；实际 elapsed=${elapsedMs}ms"
      )
      assertEquals(hitsQuota.get(), 1, s"配额死的 qa 恰好被尝试 1 次（配额类不同 provider 重试，实际 ${hitsQuota.get()} 次）")
      assertEquals(hitsOk.get(), 0, s"未引用的 qb 必须零命中（fallback 废除：不暗中回补，实际 ${hitsOk.get()} 次）")
      assert(
        !states.contains("qa/m1"),
        s"配额类不进健康状态（不 markDown ⇒ 不进探测集）；实际 ${states.get("qa/m1")}"
      )
      val avoidMs = remainingAvoidMs(avoids, "qa/m1")
      assert(avoidMs > 0L, "配额死的 qa 必须被记录软回避窗")
      // 分层判据：配额窗必须显著长于瞬时窗（TimeoutAvoidWindowMs=45s），否则等于没分层
      assert(
        avoidMs > Defaults.TimeoutAvoidWindowMs * 3,
        s"配额窗必须 ≫ 瞬时软回避窗（${Defaults.TimeoutAvoidWindowMs}ms）；实际剩余 ${avoidMs}ms"
      )
      assertEquals(upQa, List.empty[ModelCandidate], "软回避窗内 qa 不在候选链上")
      assertEquals(downQa, List.empty[ModelCandidate], "配额死的 qa 不进探测集（不烧探测请求）")
    }
  }

  // ============================================================
  // T2（重写，fallback 废除批 2026-11-06 20:36）：403 驱逐面原样钉死 + 单候选链
  // 有界静默等待；未引用的 qb/qc 不再承接（原「换链到储备层第三根」面退役）。
  // ============================================================

  test("T2(重写): 403 配额形态 ⇒ qa 软回避（不 markDown）+ 单候选链有界静默等待；qb/qc 零命中（fallback 废除）") {
    val hitsQuota403 = new AtomicInteger(0)
    val hitsQuota429 = new AtomicInteger(0)
    val hitsOk = new AtomicInteger(0)
    var s1: HttpServer = null
    var s2: HttpServer = null
    var s3: HttpServer = null
    val cfg = mkConfig(
      ("qa", PortQuota403, List("m1")),
      ("qb", PortQuota429b, List("m1")),
      ("qc", PortOk2, List("m1"))
    )
    // 链深恒 1：qb/qc 不再被链引用（fallbacks 数组退役为死数据）
    val agentChain = AgentModelConfig(preferred = Some("qa/m1"), fallbacks = Nil)
    val program =
      for
        a <- startMock(PortQuota403, "quota403", "unused", hitsQuota403)
        _ = s1 = a
        b <- startMock(PortQuota429b, "quota429", "unused", hitsQuota429)
        _ = s2 = b
        c <- startMock(PortOk2, "ok", "CC", hitsOk)
        _ = s3 = c
        out <- (for
          cfgRef <- Ref.of[IO, NebflowServiceConfig](cfg)
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
          (handle, registry, monitor, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "provchain-quota-t2",
            agentId = "provchain-quota-t2-agent",
            agentModel = Some(agentChain)
          )
          t0 <- IO.monotonic
          // fallback 废除批申报：单候选 403 驱逐后链空 ⇒ 静默等待（全灭闸）——给 8s
          // 有界上界取终局（原 T2 阻塞的 120s 全灭闸预算不再由测试承担）。
          res <- handle.sendStream(req).compile.toList.attempt
            .timeoutTo(
              8.seconds,
              // 哨兵以 Left 捕获（raiseError 会让 IO 失败、下方断言面走不到）
              IO.pure(
                Left(new RuntimeException("all-down gate exceeded test bound")): Either[Throwable, List[StreamChunk]]
              )
            )
          t1 <- IO.monotonic
          chain <- registry.getCandidatesForAgent(Some(agentChain))
          states <- monitor.getStates
          avoids <- monitor.getAvoidUntil
          _ <- release
        yield (t1.toMillis - t0.toMillis, res, chain.map(x => s"${x.providerId}/${x.model}"), states, avoids))
          .guarantee(IO.blocking {
            if s1 != null then s1.stop(0)
            if s2 != null then s2.stop(0)
            if s3 != null then s3.stop(0)
          })
      yield out

    program.map { case (elapsedMs, res, chain, states, avoids) =>
      res match
        case Right(chunks) =>
          fail(s"唯一候选配额死 ⇒ 不得有根交付（储备层已退役），实际成功 = ${textOf(chunks)}")
        case Left(_) => () // 终局形态 = 静默等待（全灭闸/测试上界）——下方驱逐面钉死
      // 防阻塞面原样钉死：403-配额类立即软回避（不重试同一根），有界等待不吞测试
      assert(
        elapsedMs < 9_000L && hitsQuota403.get() == 1,
        s"配额类 403 必须立即软回避（恰 1 次命中）且不长时间占测试；实际 elapsed=${elapsedMs}ms hits=${hitsQuota403.get()}"
      )
      // 腿 a 判据反转（fallback 废除）：链深恒 1 = preferred 单根（原「链深 ≥3 且含
      // 储备层」的多根面退役）
      assertEquals(chain, List("qa/m1"), s"候选链必须恰 = preferred 单根，实际链 = $chain")
      // 配额类面原样钉死（mock quota403 = 403 载配额体 ⇒ 引擎判配额类）：不 markDown
      //（不进探测集），软回避窗 ≫ 瞬时窗。
      assert(
        !states.contains("qa/m1"),
        s"配额类 403 不进健康状态（不 markDown ⇒ 不进探测集）；实际 ${states.get("qa/m1")}"
      )
      assert(
        remainingAvoidMs(avoids, "qa/m1") > Defaults.TimeoutAvoidWindowMs * 3,
        s"qa/m1 的配额窗必须 ≫ 瞬时窗（${Defaults.TimeoutAvoidWindowMs}ms）；实际 ${remainingAvoidMs(avoids, "qa/m1")}ms"
      )
      // fallback 废除：未引用的 qb/qc 全程零参与
      assertEquals(hitsQuota429.get(), 0, "qb 未被链引用 ⇒ 零命中（fallback 废除）")
      assertEquals(hitsOk.get(), 0, "qc 未被链引用 ⇒ 零命中（fallback 废除）")
      assert(!avoids.contains("qb/m1") && !avoids.contains("qc/m1"), "未引用 provider 不得被软回避")
      assert(!states.contains("qb/m1") && !states.contains("qc/m1"), "未引用 provider 不得进健康状态")
    }
  }
end QuotaChainSwitchSpec
