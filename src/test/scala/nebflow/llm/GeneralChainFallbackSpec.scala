package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * general 预设多腿化的换链判据面（preset→chain 迁移批 2026-10-30，作者令 08:29）。
 *
 * 生产形态：迁移后 general = `zhipu/glm-5.3-flash` 头 + `kimi/kimi-for-coding` /
 * `kimi/kimi-for-coding-highspeed` 后备（cmdcode 已 disabled 摘除，见
 * docs/20261030_cmdcode-provider-removal.md）。本 spec 以**生产同形链**钉两件事：
 *
 *   T1 链头失败走后备（端到端，零外部 API）：头 mock 以 zhipu 真实死法族
 *      （429 + 上游 `code 1308` 5h 配额）应答，后备 kimi mock 交付答案 ⇒
 *      文本必须来自后备、头至少被尝试一次（失败→换链，不是绕过头）；
 *   T2 候选序（spec 级）：同一链经 ProviderRegistry 解析为有序候选
 *      `[zhipu 头, kimi 后备…]`——请求路径按序尝试，序即 failover 语义。
 *
 * 复现台与 QuotaChainSwitchSpec / ScheduleChainSwitchSpec 同台同形（真实
 * `LlmInterface.sendStream` + 本地 Anthropic 协议 mock），端口独立（18621/18623），
 * 收尾自清（guarantee 停 server）。
 */
class GeneralChainFallbackSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val PortHeadQuota = 18621
  private val PortBackupOk = 18623

  private val Zhipu429Quota1308Body =
    """{"type":"error","error":{"type":"rate_limit_error","code":"1308","message":"[1308][已达到 5 小时的使用上限。您的限额将在当前 5 小时窗口结束后重置。]"},"request_id":"mock1308"}"""

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"

  private def textDelta(t: String) =
    s"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$t\"}}\n\n"

  private val okTail =
    Seq(
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\",\"index\":0}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  /** mode: "quota429"（头失败）| "ok"（后备交付，label 标记交付方）。 */
  private def startMock(port: Int, mode: String, label: String, hits: AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          if mode == "quota429" then sendBody(exchange, 429, Zhipu429Quota1308Body)
          else
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

  /** 生产同形 general 链（zhipu 头 + kimi 双后备）。 */
  private val generalChain: AgentModelConfig =
    AgentModelConfig(
      preferred = Some("zhipu/glm-5.3-flash"),
      fallbacks = List("kimi/kimi-for-coding", "kimi/kimi-for-coding-highspeed")
    )

  private def mkConfig: NebflowServiceConfig =
    NebflowServiceConfig(llm =
      ServiceLlmConfig(providers =
        Map(
          "zhipu" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$PortHeadQuota",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("glm-5.3-flash"))
          ),
          "kimi" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$PortBackupOk",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("kimi-for-coding"), ModelConfig("kimi-for-coding-highspeed"))
          )
        )
      )
    )

  private def textOf(chunks: List[StreamChunk]): String =
    chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString

  private def stopAll(servers: List[HttpServer]): IO[Unit] =
    IO.blocking(servers.filter(_ != null).foreach(_.stop(0)))

  // ============================================================
  // T1：链头（zhipu）429-1308 配额死 ⇒ 答案由后备（kimi）交付
  // ============================================================

  test("T1(重写) general 链头配额死 ⇒ 静默等待恢复（有界终局），后备不暗中承接（fallback 废除）") {
    val hitsHead = new AtomicInteger(0)
    val hitsBackup = new AtomicInteger(0)
    var s1: HttpServer = null
    var s2: HttpServer = null
    val program =
      for
        a <- startMock(PortHeadQuota, "quota429", "", hitsHead)
        _ = s1 = a
        b <- startMock(PortBackupOk, "ok", "KIMI-BACKUP", hitsBackup)
        _ = s2 = b
        out <- (for
          cfgRef <- Ref.of[IO, NebflowServiceConfig](mkConfig)
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
          (handle, _, _, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "general-fallback-t1",
            agentId = "general-fallback-t1-agent",
            // generalChain 的 fallbacks 数组已是**死数据**（引擎只读 preferred）——
            // 保留它正证明旧链写的兼容容忍；生效链 = zhipu 单根。
            agentModel = Some(generalChain)
          )
          // fallback 废除批申报：单候选配额死 ⇒ 静默等待恢复（作者令「否则就是静默
          // 等待」）——本测试给 8s 有界上界取终局，不无限等。
          res <- handle
            .sendStream(req)
            .compile
            .toList
            .attempt
            .timeoutTo(
              8.seconds,
              // 哨兵以 Left 捕获（raiseError 会让 IO 失败、下方断言面走不到）
              IO.pure(
                Left(new RuntimeException("all-down gate exceeded test bound")): Either[Throwable, List[StreamChunk]]
              )
            )
          _ <- release
        yield res)
          .guarantee(stopAll(List(s1, s2)))
      yield out

    program.flatMap {
      case Right(chunks) =>
        IO(fail(s"唯一候选配额死 ⇒ 不得有根交付（后备已随 fallback 退役），实际成功 = ${textOf(chunks)}"))
      case Left(e) =>
        IO {
          assert(hitsHead.get() >= 1, s"链头必须被真实尝试过（失败→静默等待，而非绕过头），实际 ${hitsHead.get()} 次")
          assertEquals(hitsBackup.get(), 0, s"未引用的后备必须零命中（fallback 废除：不暗中回补），实际 ${hitsBackup.get()} 次")
          assert(
            e.isInstanceOf[AllProvidersDownTimeout] || e.getMessage.contains("all-down gate"),
            s"终局应为静默等待形态（全灭闸/等待恢复），实际 ${e.getClass.getName}: ${e.getMessage}"
          )
        }
    }
  }

  // ============================================================
  // T2（重写）：spec 级候选序——同一链解析为单根 [zhipu 头]
  // ============================================================

  test("T2(重写) 候选序：general 链解析为单根 [zhipu 头]——后备/储备层不再进链（fallback 废除）") {
    val ref = Ref.unsafe[IO, NebflowServiceConfig](mkConfig)
    val registry =
      new ProviderRegistry(ref, null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]])
    registry.getCandidatesForAgent(Some(generalChain)).map { cs =>
      val refs = cs.map(c => s"${c.providerId}/${c.model}")
      assertEquals(
        refs,
        List("zhipu/glm-5.3-flash"),
        s"链深恒 1 = preferred 单根（fallbacks 数组退役为死数据、储备层移除），实际链 = $refs"
      )
      assertEquals(refs.size, refs.distinct.size, s"候选链不得重复: $refs")
    }
  }
end GeneralChainFallbackSpec
