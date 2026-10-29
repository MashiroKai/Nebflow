package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateJudge
import nebflow.core.delegate.DelegateJudge.{TerminalInput, Verdict}
import nebflow.llm.{AdapterResponse, ModelCandidate, ProviderAdapter, ProviderRegistry, SendMessageParams}
import nebflow.shared.{LlmProtocol, Message, MessageRole, PathUtil, ProviderConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SmallJudge 兜底腿**整链遍历**判读（fail-open 根因修复批 2026-10-29 判定机同族刀）
 * ——主分配腿 LlmAllocatorSpec 的 chainCall 判读面在判定机的镜像：
 *
 *  - 链头候选坏（传输错/adapter 缺失）→ WARN 落真因（providerId/model/异常类）
 *    ＋落到第二候选（「主 agent 能用 ⇒ 兜底判定能用」）；
 *  - 全链坏 → None（链耗尽 = 可恢复调用层失败），腿级 WARN 落「candidate chain
 *    exhausted」归因词（与主分配腿 LlmError 口径一致），绝不冒充 fail-open 结构态；
 *  - utilityCandidates：ref 可解 ⇒ 钉首位＋与全局链去重（链内重复候选塌缩为一个，
 *    不重复烧调用）；
 *  - 空回文候选不截链（provider 侧故障与传输错同侧处理）。
 *
 * 全程 stub 腿注入（ScriptedAdapter / 覆写 registry 读面），零外呼；WARN 断言走
 * logback ListAppender（AnthropicEmptyEventWarnSpec 同法——归因词是本批的承重面，
 * 光断言 None 不够，措辞必须可查）。
 */
class DelegateJudgeChainSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-judge-chain-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private val JudgeLogName = "nebflow.llm.delegate-judge"

  private def withWarnAppender[A](f: => IO[A]): IO[(A, List[String])] =
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger(JudgeLogName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    IO.delay {
      appender.start()
      lbLogger.addAppender(appender)
    }.bracket { _ =>
      f.map(a =>
        a -> appender.list.asScala.toList
          .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
          .map(_.getFormattedMessage)
      )
    } { _ => IO.delay(lbLogger.detachAppender(appender)) }

  // ── fixtures（LlmAllocatorSpec 判读面同形） ──────────────────────────────

  private def cand(pid: String, model: String): ModelCandidate =
    ModelCandidate(providerId = pid, provider = ProviderConfig("https://x", "k", LlmProtocol.OpenAI), model = model)

  /** 按模型名记账的脚本化 adapter：failOn 抛 / emptyOn 回空 / 其余按脚本回文。 */
  private class ScriptedAdapter(
    failOn: Set[String],
    emptyOn: Set[String],
    answer: String,
    attempts: TrieMap[String, String]
  ) extends ProviderAdapter[IO]:
    def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
      IO {
        attempts.update(params.model, "hit")
        if failOn.contains(params.model) then throw new RuntimeException(s"transport down for ${params.model}")
        AdapterResponse(reply = if emptyOn.contains(params.model) then "   " else answer, toolCalls = Nil)
      }
    def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, nebflow.shared.StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("stream face not expected in this spec"))

  /** 只认显式 adapter 表的 registry（其余读面按构造参数覆写）。 */
  private class StubRegistry(
    adapters: Map[String, ProviderAdapter[IO]],
    chain: List[ModelCandidate] = Nil,
    forRef: Map[String, Option[ModelCandidate]] = Map.empty,
    restriction: Map[String, Option[String]] = Map.empty
  ) extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
      adapters.get(providerId) match
        case Some(a) => IO.pure(a)
        case None    => IO.raiseError(new RuntimeException(s"unknown provider: $providerId"))
    override def getCandidates(): IO[List[ModelCandidate]] = IO.pure(chain)
    override def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
      IO.pure(forRef.getOrElse(ref, None))
    override def scheduleRestrictionFor(ref: String): IO[Option[String]] =
      IO.pure(restriction.getOrElse(ref, None))

  private val paramsFor: SendMessageParams =
    SendMessageParams(
      messages = List(Message(MessageRole.User, Left("judge"))),
      model = "m-head" // 占位：chainCall 逐候选 copy(model) 覆写，本值不参与断言
    )

  private def judgeInput(id: String): TerminalInput =
    TerminalInput(
      delegateId = id,
      taskBrief = "brief",
      resultText = "result",
      gitDigest = "branch: delegate/delegate-x",
      selfReport = None,
      mergeRound = false
    )

  private val VerdictJson = """{"verdict":"ready-to-merge","reason":"commits on branch"}"""

  // ── chainCall：整链遍历调用缝（判定机同族刀） ─────────────────────────────

  test("chainCall: 头候选传输错 ⇒ WARN 落真因（provider/model/异常类）并落到第二候选") {
    val attempts = TrieMap.empty[String, String]
    val registry = StubRegistry(Map(
      "bad" -> ScriptedAdapter(Set("m-bad"), Set.empty, "", attempts),
      "good" -> ScriptedAdapter(Set.empty, Set.empty, VerdictJson, attempts)
    ))
    val call = DelegateJudgeMachine.chainCall(registry, List(cand("bad", "m-bad"), cand("good", "m-good")))
    withWarnAppender(call(paramsFor)).map { case (reply, warns) =>
      assertEquals(reply, Some(VerdictJson), "the chain answered via the second candidate")
      assertEquals(attempts.keySet, Set("m-bad", "m-good"), "both candidates attempted in order")
      val headWarn = warns.find(_.contains("candidate bad/m-bad errored"))
      assert(headWarn.isDefined, s"head candidate failure must WARN with provider/model: $warns")
      assert(headWarn.get.contains("RuntimeException"), s"the exception class stays attributable: ${headWarn.get}")
      assert(headWarn.get.contains("trying next candidate"), s"the WARN states the traversal continues: ${headWarn.get}")
    }
  }

  test("chainCall: 全链败 ⇒ None（链耗尽语义），每个候选都留有可归因的尝试痕迹") {
    val attempts = TrieMap.empty[String, String]
    val registry = StubRegistry(Map(
      "a" -> ScriptedAdapter(Set("m-a"), Set.empty, "", attempts),
      "b" -> ScriptedAdapter(Set("m-b"), Set.empty, "", attempts)
    ))
    val call = DelegateJudgeMachine.chainCall(registry, List(cand("a", "m-a"), cand("b", "m-b")))
    withWarnAppender(call(paramsFor)).map { case (reply, warns) =>
      assertEquals(reply, None, "exhausted chain = None (the leg classifies it as a recoverable call failure)")
      assertEquals(attempts.keySet, Set("m-a", "m-b"), "every candidate got its attempt (per-candidate WARNs)")
      assert(warns.exists(_.contains("candidate a/m-a errored (RuntimeException)")), s"a/m-a WARN missing: $warns")
      assert(warns.exists(_.contains("candidate b/m-b errored (RuntimeException)")), s"b/m-b WARN missing: $warns")
    }
  }

  test("chainCall: 空回文候选不截链（provider 侧故障同侧处理，下一候选照答）") {
    val attempts = TrieMap.empty[String, String]
    val registry = StubRegistry(Map(
      "mute" -> ScriptedAdapter(Set.empty, Set("m-mute"), "", attempts),
      "good" -> ScriptedAdapter(Set.empty, Set.empty, VerdictJson, attempts)
    ))
    val call = DelegateJudgeMachine.chainCall(registry, List(cand("mute", "m-mute"), cand("good", "m-good")))
    call(paramsFor).map { reply =>
      assertEquals(reply, Some(VerdictJson), "the empty reply did not become the final answer")
      assertEquals(attempts.keySet, Set("m-mute", "m-good"), "the traversal continued past the mute candidate")
    }
  }

  // ── utilityCandidates：钉首位＋去重 ─────────────────────────────────────

  test("utilityCandidates: ref 可解 ⇒ 钉首位＋链内重复候选去重（后备不重复烧调用）") {
    withTempDataRoot { tmp =>
      IO(os.write(tmp / "nebflow.json", """{"judge":{"utilityModel":"p/util-1"}}""")).flatMap { _ =>
        val registry = StubRegistry(
          adapters = Map.empty,
          chain = List(cand("p", "util-1"), cand("q", "m2"), cand("p", "util-1")),
          forRef = Map("p/util-1" -> Some(cand("p", "util-1")))
        )
        DelegateJudgeMachine.utilityCandidates(registry).map { got =>
          assertEquals(
            got.map(c => s"${c.providerId}/${c.model}"),
            List("p/util-1", "q/m2"),
            "the ref candidate is pinned first; chain duplicates of it collapse (one call per candidate)"
          )
        }
      }
    }
  }

  // ── face.judgeTerminal：兜底腿端到端（Jev 未配置 ⇒ SmallJudge 整链） ───────

  test("face.judgeTerminal: 链头候选坏 ⇒ 判词由链上后备答出（兜底腿整链遍历）") {
    withTempDataRoot { _ =>
      val attempts = TrieMap.empty[String, String]
      val registry = StubRegistry(
        Map(
          "bad" -> ScriptedAdapter(Set("m-bad"), Set.empty, "", attempts),
          "good" -> ScriptedAdapter(Set.empty, Set.empty, VerdictJson, attempts)
        ),
        chain = List(cand("bad", "m-bad"), cand("good", "m-good"))
      )
      withWarnAppender(DelegateJudgeMachine.face(registry).judgeTerminal(judgeInput("delegate-chain-1")))
        .map { case (out, warns) =>
          assertEquals(out, Some(Verdict.ReadyToMerge), "the fallback chain answered via the second candidate")
          assertEquals(attempts.keySet, Set("m-bad", "m-good"), "the head was tried first, then the fallback")
          assert(warns.exists(_.contains("candidate bad/m-bad errored")), s"head failure WARN on record: $warns")
        }
    }
  }

  test(
    "face.judgeTerminal: 全链坏 ⇒ None 且腿级 WARN 落「candidate chain exhausted」可恢复归因词（不冒充结构态措辞）"
  ) {
    withTempDataRoot { _ =>
      val attempts = TrieMap.empty[String, String]
      val registry = StubRegistry(
        Map(
          "a" -> ScriptedAdapter(Set("m-a"), Set.empty, "", attempts),
          "b" -> ScriptedAdapter(Set("m-b"), Set.empty, "", attempts)
        ),
        chain = List(cand("a", "m-a"), cand("b", "m-b"))
      )
      withWarnAppender(DelegateJudgeMachine.face(registry).judgeTerminal(judgeInput("delegate-chain-2")))
        .map { case (out, warns) =>
          assertEquals(out, None, "exhausted chain = None (self-report/legacy takes over)")
          assertEquals(attempts.keySet, Set("m-a", "m-b"), "the whole chain was traversed")
          assert(
            warns.exists(_.contains("small-judge verdict call failed (candidate chain exhausted")),
            s"the leg-level attribution word must match the allocator's recoverable-call vocabulary: $warns"
          )
          assert(
            !warns.exists(w => w.contains("verdict leg unavailable") || w.contains("no candidate")),
            s"chain exhaustion must NOT be worded as the structural unavailable state: $warns"
          )
        }
    }
  }

end DelegateJudgeChainSpec
