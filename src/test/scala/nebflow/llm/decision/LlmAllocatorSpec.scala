package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevFallbackKind, PluginAllocator}
import nebflow.llm.{ModelCandidate, SendMessageParams}
import nebflow.shared.{LlmProtocol, PathUtil, ProviderConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 插件分配族判读（jev-retire 批 2026-11-16 起：分配 = 小模型单腿——
 * 生产面 `PluginAllocatorMachine.face` 注入 mock registry）：
 *
 *  - LlmAllocator 回文解析：裸 JSON / 围栏 / 混排皆容；未知包名剔除；缺键/形错拒；
 *  - face 两态：小模型腿产出集合（via=LlmFallback，可能空集）／fail-open
 *    （FellBack——候选链耗尽 LlmError / 回文不可用 ParseFail / 无候选 Failure，
 *    绝不抛）；
 *  - 空目录短路口：零模型调用，空集分配成功；
 *  - 🔴 每次分配恰好一行 `allocation decision` 日志（成功 / 空目录 / 失败 /
 *    候选解析异常——四路断言，jev-retire 批）；
 *  - 探活写腿：成功 ⇒ Up；失败 ⇒ Down(reason)。
 */
class LlmAllocatorSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val catalog = List(
    CatalogEntry("backend-dev", "Scala backend development"),
    CatalogEntry("visual-report", "human-facing reports and decks")
  )

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-llm-alloc-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  // ── LlmAllocator.parsePlugins（纯函数判红锚） ────────────────────────────

  private def llm(reply: Option[String], calls: TrieMap[String, String] = TrieMap.empty): LlmAllocator =
    new LlmAllocator(
      params => IO { calls.update(params.model, params.messages.headOption.map(_.textContent).getOrElse("")); reply },
      candidate = IO.pure(
        Some(
          ModelCandidate(
            providerId = "p",
            provider = ProviderConfig("https://x", "k", LlmProtocol.OpenAI),
            model = "util-1"
          )
        )
      )
    )

  test("parsePlugins: bare, fenced and prose-wrapped JSON all parse; catalog order-preserving dedup") {
    val a = llm(None)
    assertEquals(
      a.parsePlugins("""{"plugins":["visual-report","backend-dev","visual-report"]}""", catalog),
      Right(List("visual-report", "backend-dev"))
    )
    assertEquals(a.parsePlugins("```json\n{\"plugins\": [\"backend-dev\"]}\n```", catalog), Right(List("backend-dev")))
    assertEquals(
      a.parsePlugins("I would allocate: {\"plugins\":[\"backend-dev\"]} — done.", catalog),
      Right(List("backend-dev"))
    )
    assertEquals(a.parsePlugins("""{"plugins":[]}""", catalog), Right(Nil), "explicit empty set is a legal answer")
  }

  test("parsePlugins: unknown names are dropped (hallucinated packages never reach the injection face)") {
    val a = llm(None)
    assertEquals(a.parsePlugins("""{"plugins":["backend-dev","no-such-pkg"]}""", catalog), Right(List("backend-dev")))
  }

  test(
    "parsePlugins: missing key / non-array / non-string members / non-JSON are all refusals (never a fabricated success)"
  ) {
    val a = llm(None)
    assert(a.parsePlugins("""{"other":1}""", catalog).isLeft)
    assert(a.parsePlugins("""{"plugins":"backend-dev"}""", catalog).isLeft)
    assert(a.parsePlugins("""{"plugins":[1,2]}""", catalog).isLeft)
    assert(a.parsePlugins("no json here", catalog).isLeft)
  }

  test("production candidate selection reads the small model and falls back when unconfigured") {
    withTempDataRoot { tmp =>
      var chosen: Option[String] = None
      val registry = new nebflow.llm.ProviderRegistry(null, null):
        override def getCandidatesForAgent(model: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
          IO { chosen = model.flatMap(_.preferred); Nil }
      IO {
        os.write.over(tmp / "agents" / "Nebula" / "agent.json", """{"model":{"preferred":"p/main"}}""", createFolders = true)
        os.write.over(tmp / "agents" / "small-llm" / "agent.json", """{"model":{"preferred":"p/small"}}""", createFolders = true)
      } *> LlmAllocator.candidatesFrom(registry) *> IO(assertEquals(chosen, Some("p/small"))) *>
        IO(os.remove(tmp / "agents" / "small-llm" / "agent.json")) *>
        LlmAllocator.candidatesFrom(registry) *> IO(assertEquals(chosen, Some("p/main")))
    }
  }

  // ── 生产 face 两态（PluginAllocatorMachine.face + mock registry）──────────

  private def cand(pid: String, model: String): ModelCandidate =
    ModelCandidate(providerId = pid, provider = ProviderConfig("https://x", "k", LlmProtocol.OpenAI), model = model)

  /** 按 providerId 记账的脚本化 adapter：模型名命中 failOn 即抛，否则按脚本回文。 */
  private class ScriptedAdapter(failOn: Set[String], answer: String, attempts: TrieMap[String, String])
      extends nebflow.llm.ProviderAdapter[IO]:
    def sendMessage(params: SendMessageParams): IO[nebflow.llm.AdapterResponse] =
      IO {
        attempts.update(params.model, "hit")
        if failOn.contains(params.model) then throw new RuntimeException(s"transport down for ${params.model}")
        nebflow.llm.AdapterResponse(reply = answer, toolCalls = Nil)
      }
    def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, nebflow.shared.StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("stream face not expected in this spec"))

  /** face 装配用 registry：adapter 表 + 候选链均脚本注入。 */
  private class FaceRegistry(
    adapters: Map[String, nebflow.llm.ProviderAdapter[IO]],
    candidates: List[ModelCandidate]
  ) extends nebflow.llm.ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[nebflow.llm.ProviderAdapter[IO]] =
      adapters.get(providerId) match
        case Some(a) => IO.pure(a)
        case None    => IO.raiseError(new RuntimeException(s"unknown provider: $providerId"))
    override def getCandidatesForAgent(model: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
      IO.pure(candidates)

  test("face ① small-model leg allocates: via=LlmFallback, the set reaches the caller") {
    val attempts = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      PluginAllocatorMachine
        .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"],"reason":"Scala work"}""", attempts)), List(cand("p", "util-1"))))
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(r.allocation, JevAllocation.Allocated(List("backend-dev")))
          assertEquals(attempts.keySet, Set("util-1"), "exactly the small-model candidate was called")
        }
    }
  }

  test("face ② no candidates → structural fail-open (small model unavailable), never a throw") {
    withTempDataRoot { _ =>
      PluginAllocatorMachine
        .face(new FaceRegistry(Map.empty, Nil))
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, None)
          assertEquals(r.allocation, JevAllocation.FellBack(JevFallbackKind.Failure, "small model unavailable"))
        }
    }
  }

  test("face ③ candidate chain exhausted → typed LlmError fallback (归因精确化批), never a throw") {
    val attempts = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      PluginAllocatorMachine
        .face(
          new FaceRegistry(
            Map(
              "a" -> ScriptedAdapter(Set("m-a"), "", attempts),
              "b" -> ScriptedAdapter(Set("m-b"), "", attempts)
            ),
            List(cand("a", "m-a"), cand("b", "m-b"))
          )
        )
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, None)
          r.allocation match
            case JevAllocation.FellBack(JevFallbackKind.LlmError, reason) =>
              assert(
                reason.contains("candidate chain exhausted"),
                s"the seam-level classification is carried (call-layer failure, not fail-open): $reason"
              )
            case other => fail(s"expected llm-error fallback, got: $other")
          assertEquals(attempts.keySet, Set("m-a", "m-b"), "every candidate got its attempt")
        }
    }
  }

  test("face ④ unusable reply → ParseFail (model-behaviour failure), not a fabricated success") {
    withTempDataRoot { _ =>
      PluginAllocatorMachine
        .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, "no json here", TrieMap.empty)), List(cand("p", "util-1"))))
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, None)
          r.allocation match
            case JevAllocation.FellBack(JevFallbackKind.ParseFail, reason) =>
              assert(reason.nonEmpty)
            case other => fail(s"expected parse-fail fallback, got: $other")
        }
    }
  }

  test("empty catalog short-circuits: zero model calls, an explicit empty allocation") {
    val attempts = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      PluginAllocatorMachine
        .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"]}""", attempts)), List(cand("p", "util-1"))))
        .allocatePlugins("delegate-x", "task", Nil)
        .map { r =>
          assertEquals(r.allocation, JevAllocation.Allocated(Nil))
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(attempts.size, 0)
        }
    }
  }

  test("health write legs: a success turns the monitor Up; a total failure turns it Down with the classified reason") {
    withTempDataRoot { _ =>
      val up = DecisionHealthMonitor.create.flatMap { m =>
        PluginAllocatorMachine
          .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"]}""", TrieMap.empty)), List(cand("p", "util-1"))), Some(m))
          .allocatePlugins("d", "t", catalog) *> m.getDecisionHealth
      }
      val down = DecisionHealthMonitor.create.flatMap { m =>
        PluginAllocatorMachine
          .face(new FaceRegistry(Map.empty, Nil), Some(m))
          .allocatePlugins("d", "t", catalog) *> m.getDecisionHealth
      }
      for h1 <- up; h2 <- down
      yield
        assertEquals(h1, DecisionHealth.Up)
        h2 match
          case DecisionHealth.Down(reason, _) =>
            assert(reason.nonEmpty, "the Down state carries the classified diagnostic")
          case other => fail(s"expected Down, got: $other")
    }
  }

  // ── 🔴 每次分配恰好一行 allocation decision 日志（jev-retire 批四路断言）────

  private def withAllocLogger[A](f: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent] => IO[A]): IO[A] =
    val logger = org.slf4j.LoggerFactory.getLogger("nebflow.llm.plugin-alloc")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    import scala.jdk.CollectionConverters.*
    IO { appender.start(); logger.addAppender(appender) }.bracket { _ =>
      f(appender)
    }(_ => IO(logger.detachAppender(appender)).void)

  private def allocationDecisionLines(appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]): List[String] =
    import scala.jdk.CollectionConverters.*
    appender.list.asScala.toList.map(_.getFormattedMessage).filter(_.contains("allocation decision"))

  test("log ① success: exactly ONE allocation decision line, carrying plugins and rationale") {
    withAllocLogger { appender =>
      withTempDataRoot { _ =>
        PluginAllocatorMachine
          .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"],"reason":"Scala work"}""", TrieMap.empty)), List(cand("p", "util-1"))))
          .allocatePlugins("delegate-x", "task", catalog)
          .map { _ =>
            val lines = allocationDecisionLines(appender)
            assertEquals(lines.size, 1, s"exactly one decision line — got: $lines")
            assert(lines.head.contains("backend-dev"))
            assert(lines.head.contains("Scala work"))
          }
      }
    }
  }

  test("log ② empty catalog: exactly ONE allocation decision line (reason=empty catalog)") {
    withAllocLogger { appender =>
      withTempDataRoot { _ =>
        PluginAllocatorMachine
          .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"]}""", TrieMap.empty)), List(cand("p", "util-1"))))
          .allocatePlugins("delegate-x", "task", Nil)
          .map { _ =>
            val lines = allocationDecisionLines(appender)
            assertEquals(lines.size, 1, s"exactly one decision line — got: $lines")
            assert(lines.head.contains("reason=empty catalog"))
          }
      }
    }
  }

  test("log ③ failure: exactly ONE allocation decision line (warn, empty plugins, the classified reason)") {
    withAllocLogger { appender =>
      withTempDataRoot { _ =>
        PluginAllocatorMachine
          .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set.empty, "no json here", TrieMap.empty)), List(cand("p", "util-1"))))
          .allocatePlugins("delegate-x", "task", catalog)
          .map { _ =>
            val lines = allocationDecisionLines(appender)
            assertEquals(lines.size, 1, s"exactly one decision line — got: $lines")
            assert(lines.head.contains("plugins= "))
            assert(lines.head.contains("llm allocator"))
          }
      }
    }
  }

  test("log ④ candidate resolution exception: exactly ONE allocation decision line naming the exception") {
    withAllocLogger { appender =>
      withTempDataRoot { tmp =>
        IO(os.write.over(tmp / "agents" / "small-llm" / "agent.json", """{"model":{"preferred":"p/small"}}""", createFolders = true)) *>
          IO {
            val registry = new nebflow.llm.ProviderRegistry(null, null):
              override def getCandidatesForAgent(model: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
                IO.raiseError(new java.util.concurrent.TimeoutException("mock timeout"))
            registry
          }
            .flatMap(registry => PluginAllocatorMachine.face(registry).allocatePlugins("subject", "task", catalog))
          .map { result =>
            assert(result.allocation.isInstanceOf[JevAllocation.FellBack])
            val lines = allocationDecisionLines(appender)
            assertEquals(lines.size, 1, s"exactly one decision line — got: $lines")
            assert(lines.head.contains("TimeoutException"))
          }
      }
    }
  }

  test("the llm prompt carries the catalog and the membership rule (state template single source)") {
    val calls = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      llm(Some("""{"plugins":[]}"""), calls).allocateFor("s", "a visual task", catalog).map { _ =>
        val prompt = calls("util-1")
        assert(prompt.contains(LlmAllocator.CatalogHeader), s"catalog header present:\n$prompt")
        assert(prompt.contains("### visual-report"), "catalog entries rendered")
        assert(prompt.contains(""""reason": "<brief rationale>""""), "the JSON answer requests a rationale")
      }
    }
  }

  // ── chainCall：整链遍历调用缝（fail-open 根因修复批 2026-10-29）─────────────
  //
  // 根因钉子：旧缝 = 链头单候选、传输错即弃（`case Left(_) => None`）——链头候选
  // 恰好挂掉时「主 agent 正常用 llm、兜底腿仍 fail-open」（jev-allocation.jsonl
  // 实证 27 例）。新缝语义：逐候选试，坏候选 WARN 留真因，**全链败才 None**。

  private val paramsFor: SendMessageParams =
    SendMessageParams(
      messages = List(nebflow.shared.Message(nebflow.shared.MessageRole.User, Left("allocate"))),
      model = "m-head" // 占位：chainCall 逐候选 copy(model) 覆写，本值不参与断言
    )

  private class PerProviderRegistry(adapters: Map[String, nebflow.llm.ProviderAdapter[IO]])
      extends nebflow.llm.ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[nebflow.llm.ProviderAdapter[IO]] =
      adapters.get(providerId) match
        case Some(a) => IO.pure(a)
        case None    => IO.raiseError(new RuntimeException(s"unknown provider: $providerId"))

  test("chainCall: 头候选传输错 ⇒ WARN 试下一候选，第二候选答出（主 agent 能用 ⇒ 兜底能用）") {
    val attempts = TrieMap.empty[String, String]
    val registry = new PerProviderRegistry(Map(
      "bad" -> ScriptedAdapter(Set("m-bad"), "", attempts),
      "good" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"]}""", attempts)
    ))
    val call = LlmAllocator.chainCall(registry, List(cand("bad", "m-bad"), cand("good", "m-good")))
    call(paramsFor).map { reply =>
      assertEquals(reply, Some("""{"plugins":["backend-dev"]}"""), "the chain answered via the second candidate")
      assertEquals(attempts.keySet, Set("m-bad", "m-good"), "both candidates were attempted in order")
    }
  }

  test("chainCall: 全链败 ⇒ None（链耗尽语义），每个失败候选都留有尝试痕迹") {
    val attempts = TrieMap.empty[String, String]
    val registry = new PerProviderRegistry(Map(
      "a" -> ScriptedAdapter(Set("m-a"), "", attempts),
      "b" -> ScriptedAdapter(Set("m-b"), "", attempts)
    ))
    val call = LlmAllocator.chainCall(registry, List(cand("a", "m-a"), cand("b", "m-b")))
    call(paramsFor).map { reply =>
      assertEquals(reply, None, "exhausted chain = None (the caller classifies it as llm-error)")
      assertEquals(attempts.keySet, Set("m-a", "m-b"), "every candidate got its attempt (per-candidate WARNs)")
    }
  }

  test("chainCall: 未知 provider 的候选被跳过（adapter 解析失败不炸整缝）") {
    val attempts = TrieMap.empty[String, String]
    val registry = new PerProviderRegistry(Map(
      "good" -> ScriptedAdapter(Set.empty, """{"plugins":[]}""", attempts)
    ))
    val call = LlmAllocator.chainCall(registry, List(cand("ghost", "m-ghost"), cand("good", "m-good")))
    call(paramsFor).map { reply =>
      assertEquals(reply, Some("""{"plugins":[]}"""), "the resolvable candidate still answers")
      assertEquals(attempts.keySet, Set("m-good"), "the ghost candidate was skipped, not fatal")
    }
  }

  test("chainCall: 空回文候选不截链（provider 侧故障同侧处理，下一候选照答）") {
    val attempts = TrieMap.empty[String, String]
    val registry = new PerProviderRegistry(Map(
      "mute" -> ScriptedAdapter(Set.empty, "   ", attempts),
      "good" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev"]}""", attempts)
    ))
    val call = LlmAllocator.chainCall(registry, List(cand("mute", "m-mute"), cand("good", "m-good")))
    call(paramsFor).map { reply =>
      assertEquals(reply, Some("""{"plugins":["backend-dev"]}"""), "the empty reply did not become the final answer")
      assertEquals(attempts.keySet, Set("m-mute", "m-good"), "the traversal continued past the mute candidate")
    }
  }

  test("chainCall: 超时类失败 ⇒ 同一候选先重试一次（缺陷① 2026-11-02），重试成功即答") {
    // 第一次超时（sttp 60s 掐断的同类形状），第二次答出：重试必须发生在**同一候选**上
    // ——单候选部署下「换下一候选」等于没有重试。
    var calls = 0
    val flaky = new nebflow.llm.ProviderAdapter[IO]:
      def sendMessage(params: SendMessageParams): IO[nebflow.llm.AdapterResponse] =
        IO {
          calls += 1
          if calls == 1 then throw new java.util.concurrent.TimeoutException("Request timed out after 60s")
          nebflow.llm.AdapterResponse(reply = """{"plugins":["stand-dev"]}""", toolCalls = Nil)
        }
      def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, nebflow.shared.StreamChunk] =
        fs2.Stream.raiseError[IO](new RuntimeException("stream face not expected in this spec"))
    val registry = new PerProviderRegistry(Map("slow" -> flaky))
    val call = LlmAllocator.chainCall(registry, List(cand("slow", "m-slow")))
    call(paramsFor).map { reply =>
      assertEquals(reply, Some("""{"plugins":["stand-dev"]}"""), "the retry answered")
      assertEquals(calls, 2, "exactly one retry on the same candidate")
    }
  }

  test("chainCall: 超时重试仍败 ⇒ 落下一候选（重试不是死循环）") {
    val attempts = TrieMap.empty[String, String]
    var calls = 0
    val alwaysSlow = new nebflow.llm.ProviderAdapter[IO]:
      def sendMessage(params: SendMessageParams): IO[nebflow.llm.AdapterResponse] =
        IO {
          calls += 1
          throw new java.util.concurrent.TimeoutException("Request timed out")
        }
      def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, nebflow.shared.StreamChunk] =
        fs2.Stream.raiseError[IO](new RuntimeException("stream face not expected in this spec"))
    val registry = new PerProviderRegistry(Map(
      "slow" -> alwaysSlow,
      "good" -> ScriptedAdapter(Set.empty, """{"plugins":["qa"]}""", attempts)
    ))
    val call = LlmAllocator.chainCall(registry, List(cand("slow", "m-slow"), cand("good", "m-good")))
    call(paramsFor).map { reply =>
      assertEquals(reply, Some("""{"plugins":["qa"]}"""), "the healthy candidate still answers")
      assertEquals(calls, 2, "the failing candidate was tried exactly twice (one retry, then move on)")
    }
  }

end LlmAllocatorSpec
