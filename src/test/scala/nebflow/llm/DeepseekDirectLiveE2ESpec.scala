package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 假 DOWN 批（2026-11-05）判据 4 —— **端到端实证**（真实网络，env-gated）：
 * 让候选链在首选（cmdcode 位）不可用时**真的落到 Deepseek 直连并出结果**。
 *
 * 现场形态复刻（逐条对实现场读数）：
 *   1. `Deepseek/deepseek-flash` 在健康表里带着一条化石原因
 *      `Exception when sending request: POST https://api.deepseek.com/anthropic/v1/messages`
 *      （20:04 写入，此后 48 分钟未清）⇒ 修前：每次请求都被 `health-down` 直接跳过，
 *      **一次真实调用都没发生过**；
 *   2. 首选项（cmdcode 位）真的连不上（本 spec 用 `127.0.0.1:9` 保证 ConnectException，
 *      即真·不可用，判据 5 的反例腿）；
 *   3. 断言：真实回复由 Deepseek 给出 + 健康表回 UP + 首选项带着真实失败原因进账本。
 *
 * 默认 **skip**（不打真网、不花钱、CI 离线可跑）：设 `NEBFLOW_LIVE_E2E=1` 才跑。
 * 凭据从 `~/.stand/stand.json` 现读（与引擎同一份配置），本 spec **不打印**任何
 * 密钥——输出只有 provider/model/耗时/失败链。
 *
 * 运行：`NEBFLOW_LIVE_E2E=1 sbt "testOnly nebflow.llm.DeepseekDirectLiveE2ESpec"`
 */
class DeepseekDirectLiveE2ESpec extends CatsEffectSuite:

  override val munitIOTimeout = 240.seconds

  private val deadBaseUrl = "http://127.0.0.1:9/v1/" // discard 端口：连接必被立即拒绝

  private def stamp: String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(java.time.LocalTime.now())

  private def line(s: String): IO[Unit] = IO(println(s"[$stamp] [E2E] $s"))

  /** 现读配置里的 Deepseek provider（anthropic 面）。缺失则 skip。 */
  private def deepseekFromConfig: Option[ProviderConfig] =
    val path = java.nio.file.Paths.get(System.getProperty("user.home"), ".stand", "stand.json")
    if !java.nio.file.Files.exists(path) then None
    else
      Config
        .loadServiceConfig(Some(path.toString))
        .llm
        .providers
        .get("Deepseek")
        .filter(_.apiKey.trim.nonEmpty)

  private def mkConfig(ds: ProviderConfig): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          // 首选：真·不可用（判据 5 的反例腿——它必须被真实试过、带原因被跳过）
          "deadprov" -> ProviderConfig(
            baseUrl = deadBaseUrl,
            apiKey = "unused",
            protocol = LlmProtocol.OpenAI,
            models = List(ModelConfig("m1"))
          ),
          // 第二根：Deepseek 直连（anthropic 兼容面，真凭据真端点）
          "Deepseek" -> ds.copy(models = List(ModelConfig("deepseek-flash")))
        )
      )
    )

  test("cmdcode 不可用 ⇒ 链真的落到 Deepseek 直连并出结果（假 DOWN 不再否决候选）") {
    assume(sys.env.get("NEBFLOW_LIVE_E2E").contains("1"), "set NEBFLOW_LIVE_E2E=1 to run the live end-to-end proof")
    val ds = deepseekFromConfig.getOrElse(
      fail("provider 'Deepseek' is not configured in ~/.stand/stand.json — cannot run the live proof")
    )
    // 半开试探的退避窗压到 0：本 spec 要证明的是「化石 DOWN 挡不住真实调用」，
    // 不是等 60s（退避阶梯本身由 HealthHalfOpenSpec T1/T3 钉住）。
    val prev = Option(System.getProperty("nebflow.health.downRetryBaseMs"))
    System.setProperty("nebflow.health.downRetryBaseMs", "0")
    val fossil =
      "Exception when sending request: POST " + ds.baseUrl.stripSuffix("/") + "/v1/messages"

    (for
      _ <- line(s"provider Deepseek baseUrl=${ds.baseUrl} model=deepseek-flash (key present, never printed)")
      _ <- line(s"preferred = deadprov/m1 (${deadBaseUrl} — connection refused by design)")
      cfgRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(ds))
      overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
      (handle, registry, monitor, release) = triple
      // 现场形态：表里带着 20:04 那条化石原因
      _ <- monitor.markDown("Deepseek", "deepseek-flash", fossil)
      before <- monitor.getStates
      trialsBefore <- monitor.getHalfOpenTrials
      _ <- line(s"health table BEFORE call: ${before.map { case (k, v) => s"$k=$v" }.mkString(" | ")}")
      _ <- line(
        s"half-open ledger BEFORE call: ${trialsBefore.map { case (k, t) => s"$k(at=${t.at}, trials=${t.trials})" }.mkString(" | ")}"
      )
      chain <- registry.getCandidatesForAgentUnfiltered(
        Some(AgentModelConfig(preferred = Some("deadprov/m1"), fallbacks = List("Deepseek/deepseek-flash")))
      )
      _ <- line(s"candidate chain: ${chain.map(c => s"${c.providerId}/${c.model}").mkString(" -> ")}")
      t0 <- IO(System.currentTimeMillis())
      req = LlmRequest(
        messages = List(Message(MessageRole.User, Left("Reply with the single word: pong"))),
        sessionId = "live-e2e-fake-down",
        agentId = "live-e2e-fake-down",
        agentModel =
          Some(AgentModelConfig(preferred = Some("deadprov/m1"), fallbacks = List("Deepseek/deepseek-flash")))
      )
      resp <- handle.send(req)
      totalMs <- IO(System.currentTimeMillis() - t0)
      after <- monitor.getStates
      trialsAfter <- monitor.getHalfOpenTrials
      _ <- line(s"wall clock total = ${totalMs}ms")
      _ <- resp.meta.fallbackChain.toList.flatten.traverse_ { s =>
        line(
          s"attempt: ${s.providerId}/${s.model} reason=${s.reason.getOrElse("OK")} " +
            s"duration=${s.durationMs}ms"
        )
      }
      _ <- line(s"WINNER: ${resp.meta.providerId}/${resp.meta.model} reply=${resp.reply.trim.take(60)}")
      _ <- line(s"health table AFTER call: ${after.map { case (k, v) => s"$k=$v" }.mkString(" | ")}")
      _ <- line(
        s"half-open ledger AFTER call: ${trialsAfter.map { case (k, t) => s"$k(trials=${t.trials})" }.mkString(" | ")}"
      )
      _ <- release
    yield (resp, before, after, trialsBefore, trialsAfter))
      .map { case (resp, before, after, trialsBefore, trialsAfter) =>
        // ── 判据 4：链真的落到 Deepseek 直连并出结果 ────────────────────────────
        assertEquals(resp.meta.providerId, "Deepseek", "the chain must really land on the Deepseek direct leg")
        assertEquals(resp.meta.model, "deepseek-flash", "and on the configured model")
        assert(resp.reply.trim.nonEmpty, "the direct leg must PRODUCE a reply, not merely be attempted")
        // 化石 DOWN 是调用前的事实（本 spec 不是空测）
        assert(
          before.get("Deepseek/deepseek-flash").exists(_.isInstanceOf[HealthState.Down]),
          s"precondition: the fossil DOWN must be in the table before the call, got ${before.get("Deepseek/deepseek-flash")}"
        )
        assertEquals(trialsBefore.get("Deepseek/deepseek-flash").map(_.trials), Some(0))
        // ── 判据 4 另一半：半开放行真的发生过（不是碰巧没人过滤）────────────────────
        //   证据链：① 调用前它在表里是 Down（上面已断言）⇒ 修前它会被 `health-down`
        //   直接跳过、**一次真实调用都不会发生**；② 本轮的 attempt 账本里出现
        //   `Deepseek … reason=OK`；③ 表里随之 Down→Up。三者同时成立只可能来自
        //   「到期半开放行 + 这一次真实调用成功」。
        val dsSteps = resp.meta.fallbackChain.toList.flatten.filter(_.providerId == "Deepseek")
        assertEquals(dsSteps.size, 1, s"exactly one Deepseek attempt must be recorded, got ${resp.meta.fallbackChain}")
        assertEquals(dsSteps.head.reason, None, "that attempt must have SUCCEEDED (no failure reason)")
        // 成功即清零：半开阶梯记账随 markUp 一起消失（下一次 Down 从 base 重新起算）。
        assertEquals(
          trialsAfter.get("Deepseek/deepseek-flash"),
          None,
          "a successful trial must clear the half-open ladder (markUp clears it)"
        )
        // ── 成功即清除：真实调用成功 ⇒ 表里不再有 Down ────────────────────────────
        assert(
          !after.get("Deepseek/deepseek-flash").exists(_.isInstanceOf[HealthState.Down]),
          s"a real successful call must clear the Down state, got ${after.get("Deepseek/deepseek-flash")}"
        )
        // ── 判据 5 反例腿：真不可用的首选被真实试过、带原因 ───────────────────────
        val deadSteps = resp.meta.fallbackChain.toList.flatten.filter(_.providerId == "deadprov")
        assert(
          deadSteps.nonEmpty,
          s"the dead preferred candidate must appear in the attempt ledger: ${resp.meta.fallbackChain}"
        )
        assert(deadSteps.forall(_.reason.isDefined), "the dead candidate's failure reason must be recorded")
      }
      .guarantee(IO {
        prev match
          case Some(v) => System.setProperty("nebflow.health.downRetryBaseMs", v)
          case None => System.clearProperty("nebflow.health.downRetryBaseMs")
      })
  }
end DeepseekDirectLiveE2ESpec
