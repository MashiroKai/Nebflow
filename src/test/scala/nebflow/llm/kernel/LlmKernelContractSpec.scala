package nebflow.llm.kernel

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.LlmUsageRecord
import nebflow.llm.*
import nebflow.shared.*

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

/**
 * **统一 LLM 内核契约闸**（kernel-spec 批 2026-11-19；K1–K7，逐条落成编译期/测试期
 * 断言，**运行期零开销**）。
 *
 * 依据档 `cache-hit-spec-coverage-and-unified-llm-kernel__20261119_111124.md` §6.2。
 * 每条闸的意图与判定方式：
 *
 *   - **K1 唯一调用入口**：全仓 grep `\.sendMessage(` / `\.sendMessageStream(` 的生产
 *     调用点只允许出现在 `interface.scala` / `LlmKernel.scala` / `AdaptiveThrottle.scala`
 *     （末者是 429 闸装饰器，透传不算调用点）；`SendMessageParams(` 的**构造**只允许
 *     出现在内核（装配单点）。
 *   - **K2 system 段来源单一**：编译期 = `KernelCall.systemPrefix` 无缺省值；测试期 =
 *     ① 每处 `site = …` 右值必须来自登记表 `KernelCallSites.`；② 每处
 *     `SystemPrefix.Absent(…)` 的实参必须来自登记表白名单理由；③ 登记表自身自洽。
 *   - **K3 断点落位**：wire 级判据在 `CachePrefixAssemblySpec`（单发腿三态）。
 *   - **K4 易变段归位**：装配单点把请求级临时段映射到 `requestOnlyContext` /
 *     `systemDynamic`，**永不进 `messages`**；同稳定前缀 + 仅临时段变化 ⇒ 第二发命中。
 *   - **K5 重试/超时单源**：候选链遍历只有一个模板（`:LlmKernel.scala`）；腿里不许
 *     出现 `getAdapter(` 取用点或遍历折叠；单调用预算由 `KernelCall.timeout` 声明。
 *   - **K6 计量单源**：`CacheStats.record` / `CacheAttribution.observeUsage` /
 *     `CacheAttribution.accountStream` 的调用点只允许出现在内核 / `interface.scala` /
 *     `CacheAttribution.scala` 自身；**计量与过闸解耦**（`Gating.Ungated` 仍过计量）；
 *     响应含失败都过落账点。
 *   - **K7 模型来源显式**：编译期 = `KernelCall.source` 无缺省值；测试期 = 每条腿的
 *     候选解析与登记来源逐值一致（判定机 `utilityCandidates` ≡
 *     `candidates(DecisionRoute("delegate-judge"))`）。
 */
class LlmKernelContractSpec extends FunSuite:

  // ── 源码扫描基建（测试期闸；零运行期开销）─────────────────────────────────

  /** 仓根：从测试 cwd 逐级上溯到含 `src/main/scala/nebflow` 的那一层。 */
  private val repoRoot: os.Path =
    Iterator
      .iterate(os.pwd)(_ / os.up)
      .takeWhile(p => p != p / os.up)
      .find(p => os.exists(p / "src" / "main" / "scala" / "nebflow"))
      .getOrElse(throw new AssertionError(s"cannot locate repo root from ${os.pwd}"))

  private case class Hit(file: String, line: Int, text: String)

  /** 扫 `src/main/scala` 全部 `.scala`，返回「行文本含 needles 中任意一个」的命中。 */
  private def scanMain(needles: List[String]): List[Hit] =
    val out = mutable.ListBuffer.empty[Hit]
    val root = repoRoot / "src" / "main" / "scala"
    for
      p <- os.walk(root)
      if p.last.endsWith(".scala") && os.isFile(p)
    do
      val rel = p.relativeTo(root).toString
      os.read.lines(p).zipWithIndex.foreach { case (text, idx) =>
        if needles.exists(n => text.contains(n)) then out += Hit(rel, idx + 1, text.trim)
      }
    out.toList

  private def offenders(hits: List[Hit], allowedFiles: Set[String]): List[Hit] =
    hits.filterNot(h => allowedFiles.contains(h.file))

  /** 只认代码行：丢掉 scaladoc / 行注释（登记面断言的噪音源）。 */
  private def codeOnly(hits: List[Hit]): List[Hit] =
    hits.filterNot(h => h.text.startsWith("*") || h.text.startsWith("//") || h.text.startsWith("/*"))

  private def show(hits: List[Hit]): String = hits.map(h => s"${h.file}:${h.line}  ${h.text}").mkString("\n")

  // ── K1 唯一调用入口 ──────────────────────────────────────────────────────

  test("K1: .sendMessage/.sendMessageStream production call sites live only in interface.scala + the kernel (+ the throttle decorator)"):
    val hits = scanMain(List(".sendMessage(", ".sendMessageStream("))
    val allowed = Set(
      "nebflow/llm/interface.scala",
      "nebflow/llm/kernel/LlmKernel.scala",
      "nebflow/llm/AdaptiveThrottle.scala"
    )
    val bad = offenders(hits, allowed)
    assert(
      bad.isEmpty,
      s"these call sites bypass the single call entry point (must go through LlmKernel):\n${show(bad)}"
    )
    assert(hits.nonEmpty, "the scan found no call sites at all — the scan itself is broken")

  test("K1: SendMessageParams is constructed only by the assembly point (LlmKernel.assemble)"):
    val hits = scanMain(List("SendMessageParams("))
    val allowed = Set("nebflow/llm/adapter.scala", "nebflow/llm/kernel/LlmKernel.scala")
    val bad = offenders(hits, allowed)
    assert(bad.isEmpty, s"wire parameters must be built only in the assembly point:\n${show(bad)}")

  // ── K2 system 段来源单一 ─────────────────────────────────────────────────

  test("K2: every `site = …` is a registered KernelCallSites entry"):
    val hits = codeOnly(scanMain(List("site = "))).filter(_.file.startsWith("nebflow/llm/"))
    val bad = hits.filterNot(_.text.replaceAll("\\s", "").contains("site=KernelCallSites."))
    assert(bad.isEmpty, s"call sites must be registered in KernelCallSites, not declared ad hoc:\n${show(bad)}")
    assert(hits.size >= 4, s"expected at least 4 registered call sites, found ${hits.size}")

  test("K2: every SystemPrefix.Absent(…) reason comes from the KernelCallSites whitelist"):
    val hits = codeOnly(scanMain(List("SystemPrefix.Absent(")))
    val bad = hits.filterNot(_.text.contains("SystemPrefix.Absent(KernelCallSites."))
    assert(bad.isEmpty, s"absence reasons must come from the registry whitelist:\n${show(bad)}")

  test("K2: the registry itself is self-consistent (unique ids, non-empty declarations)"):
    val all = KernelCallSites.all
    assertEquals(all.map(_.id).distinct.size, all.size, "call-site ids must be unique")
    all.foreach { s =>
      assert(s.id.nonEmpty && s.logName.nonEmpty && s.logTag.nonEmpty, s"site ${s.id} has an empty field")
      assert(s.systemPrefix.length >= 20, s"site ${s.id} must state its system-prefix disposition explicitly")
    }
    assertEquals(KernelCallSites.absenceReasons.distinct.size, KernelCallSites.absenceReasons.size)
    KernelCallSites.absenceReasons.foreach(r => assert(r.length >= 20, s"whitelist reason too terse: $r"))

  test("K2: SystemPrefix maps Stable→wire text and Absent→none (no fabricated prefix)"):
    assertEquals(LlmKernel.SystemPrefix.Stable("S").wireText, Some("S"))
    assertEquals(LlmKernel.SystemPrefix.Absent("why").wireText, None)
    assertEquals(LlmKernel.declaredPrefix(Some("S")), LlmKernel.SystemPrefix.Stable("S"))
    assertEquals(LlmKernel.declaredPrefix(None).wireText, None)
    assertEquals(LlmKernel.declaredPrefix(Some("")), LlmKernel.SystemPrefix.Absent(KernelCallSites.MainLegAbsence))

  // ── K4 易变段归位 ────────────────────────────────────────────────────────

  private def cand(pid: String, model: String): ModelCandidate =
    ModelCandidate(providerId = pid, provider = ProviderConfig("https://x", "k", LlmProtocol.Anthropic), model = model)

  private def userText(t: String): Message = Message(MessageRole.User, Left(t))

  test("K4: the assembly point keeps request-level ephmera out of `messages` and puts them on the ephemeral tail"):
    val c        = cand("p", "m")
    val persisted = userText("persistent")
    val call = LlmKernel.KernelCall(
      site = KernelCallSites.PluginAlloc,
      systemPrefix = LlmKernel.SystemPrefix.Stable("STABLE"),
      messages = List(persisted),
      source = LlmKernel.CandidateSource.SmallLlmModel,
      requestOnlyContext = List(userText("<system-reminder>\nephemeral\n</system-reminder>")),
      systemDynamic = Some("[request-level dynamic ctx]")
    )
    val params = LlmKernel.assemble(call, c)
    assertEquals(params.messages, List(persisted), "request-only content must not ride in messages")
    assertEquals(params.requestOnlyContext.size, 1)
    assertEquals(params.systemStable, Some("STABLE"))
    assertEquals(params.systemDynamic, Some("[request-level dynamic ctx]"))
    assertEquals(params.sessionId, Some("plugin-alloc"), "session id defaults to the registry purpose")
    assertEquals(params.agentId, Some("plugin-alloc"))

  test("K4: two kernel-assembled calls differing only in the ephemeral tail keep `messages` byte-identical"):
    val c        = cand("p", "m")
    val persisted = userText("persistent body")
    def params(turn: Int) =
      LlmKernel.assemble(
        LlmKernel.KernelCall(
          site = KernelCallSites.PluginAlloc,
          systemPrefix = LlmKernel.SystemPrefix.Stable("STABLE-SYSTEM"),
          messages = List(persisted),
          source = LlmKernel.CandidateSource.SmallLlmModel,
          requestOnlyContext = List(userText(s"<system-reminder>\nreminder turn $turn\n</system-reminder>")),
          systemDynamic = Some(s"epoch_ms ${1793970000000L + turn}")
        ),
        c
      )
    val p1 = params(1)
    val p2 = params(2)
    assertEquals(
      p1.messages,
      List(persisted),
      "request-only content never rides in `messages` (K4 / C1 / C4)"
    )
    assertEquals(
      p1.messages,
      p2.messages,
      "the persisted messages stay byte-identical although only the ephemera differ (K4)"
    )
    assertEquals(p1.requestOnlyContext.size, 1, "the request-only reminder rides the ephemeral tail")
    assertNotEquals(p1.systemDynamic, p2.systemDynamic, "each turn re-anchors its request-level clock")
    // wire 级判据（断点落位 + 跨发逐块不变）落在 providers.CachePrefixAssemblySpec ⑨
    // ——那一层才有 `AnthropicAdapter.bodyOf` 的包内访问权。

  // ── K5 重试/超时单源 ─────────────────────────────────────────────────────

  test("K5: candidate-chain traversal exists only in the kernel; legs never acquire adapters themselves"):
    val adapterHits = scanMain(List("getAdapter(", "getAdapterRaw("))
    val allowedAdapter = Set(
      "nebflow/llm/registry.scala",
      "nebflow/llm/interface.scala",
      "nebflow/llm/kernel/LlmKernel.scala"
    )
    val badAdapters = offenders(adapterHits, allowedAdapter)
    assert(badAdapters.isEmpty, s"legs must not acquire adapters directly (K5):\n${show(badAdapters)}")

    val traversalHits = scanMain(List("candidates.foldLeft[IO[Option["))
    assert(
      offenders(traversalHits, Set("nebflow/llm/kernel/LlmKernel.scala")).isEmpty,
      "the candidate-chain traversal template must be single-sourced in the kernel"
    )

    // 腿内的 chainCall 只是内核模板的薄委托（不是第二份遍历）
    val legChainCalls = scanMain(List("def chainCall")).filterNot(_.file.contains("kernel/LlmKernel.scala"))
    assert(legChainCalls.nonEmpty, "expected the legs to keep their test-facing chainCall seam")
    legChainCalls.foreach { h =>
      val text = os.read(repoRoot / "src" / "main" / "scala" / os.RelPath(h.file))
      val body = text.linesIterator.drop(h.line - 1).take(6).mkString("\n")
      assert(body.contains("LlmKernel.chainCall("), s"${h.file}:${h.line} is not a thin kernel delegate:\n$body")
    }

  // ── K6 计量单源 ──────────────────────────────────────────────────────────

  test("K6: usage accounting entry points live only in the kernel / main leg / the attribution object"):
    val hits = scanMain(List("CacheStats.record(", "CacheAttribution.observeUsage(", "CacheAttribution.accountStream("))
    val allowed = Set(
      "nebflow/llm/kernel/LlmKernel.scala",
      "nebflow/llm/interface.scala",
      "nebflow/llm/CacheAttribution.scala"
    )
    val bad = offenders(hits, allowed)
    assert(bad.isEmpty, s"usage accounting must pass the single kernel point (K6):\n${show(bad)}")

  /** 记录型 registry：只认显式 adapter 表。 */
  private class StubRegistry(adapter: ProviderAdapter[IO]) extends ProviderRegistry(null, null):
    override def getAdapter(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)
    override private[llm] def getAdapterRaw(providerId: String): IO[ProviderAdapter[IO]] = IO.pure(adapter)

  private class ScriptedAdapter(reply: String, usage: Option[TokenUsage], boom: Boolean) extends ProviderAdapter[IO]:
    val calls = new AtomicReference(0)
    def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
      IO {
        calls.updateAndGet(_ + 1)
        if boom then throw new RuntimeException("transport down")
        AdapterResponse(reply = reply, toolCalls = Nil, usage = usage)
      }
    def sendMessageStream(params: SendMessageParams): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.raiseError[IO](new RuntimeException("stream face not expected in this spec"))

  private def kernelCall(gating: LlmKernel.Gating, meter: LlmKernel.MeterKind): LlmKernel.KernelCall =
    LlmKernel.KernelCall(
      site = KernelCallSites.PluginAlloc,
      systemPrefix = LlmKernel.SystemPrefix.Stable("S"),
      messages = List(userText("t")),
      source = LlmKernel.CandidateSource.Explicit(List(cand("p", "m"))),
      gating = gating,
      meterKind = meter
    )

  private def withCleanAccounts[A](f: => A): A =
    CacheStats.resetForTest()
    CacheAttribution.resetForTest()
    LlmKernel.UsageSink.clear()
    try f
    finally
      LlmKernel.UsageSink.clear()
      CacheStats.resetForTest()
      CacheAttribution.resetForTest()

  test("K6: a successful auxiliary-leg response reaches all three accounts (stats + attribution + usage row)"):
    withCleanAccounts {
      val usage = TokenUsage(inputTokens = 1200, outputTokens = 10, cacheReadTokens = Some(900), cacheWriteTokens = Some(0))
      val adapter = new ScriptedAdapter("ok", Some(usage), boom = false)
      val rows = new AtomicReference(List.empty[LlmUsageRecord])
      LlmKernel.UsageSink.install(r => IO(rows.updateAndGet(_ :+ r)))
      val out = LlmKernel.run(new StubRegistry(adapter), kernelCall(LlmKernel.Gating.Throttled, LlmKernel.MeterKind.Business)).unsafeRunSync()
      assertEquals(out.map(_.reply), Some("ok"))
      assertEquals(CacheStats.totalSnapshot.requests, 1L, "the aux leg must enter the cache-hit aggregate")
      assertEquals(CacheStats.totalSnapshot.cacheRead, 900L)
      assertEquals(CacheStats.domainSnapshots.keySet, Set("p/m"))
      val written = rows.get()
      assertEquals(written.size, 1, "exactly one usage-record row for one response")
      assertEquals((written.head.provider, written.head.model, written.head.cacheReadTokens), ("p", "m", 900))
      assertEquals(written.head.agent, "plugin-alloc", "the usage row is attributed to the registered purpose")
    }

  test("K6: a failed auxiliary-leg call still passes the accounting point (usage absent → miss attribution, no row)"):
    withCleanAccounts {
      val rows = new AtomicReference(List.empty[LlmUsageRecord])
      LlmKernel.UsageSink.install(r => IO(rows.updateAndGet(_ :+ r)))
      val out = LlmKernel.run(new StubRegistry(new ScriptedAdapter("", None, boom = true)), kernelCall(LlmKernel.Gating.Throttled, LlmKernel.MeterKind.Business)).unsafeRunSync()
      assertEquals(out, None, "exhausted chain = None")
      assertEquals(rows.get(), Nil, "no usage ⇒ no zero-token row (the row carries information only when usage exists)")
      assert(
        CacheAttribution.snapshot.missCounts.nonEmpty,
        "a failed business call must leave a miss-attribution entry (usage absent in completed response)"
      )
    }

  test("K6: metering is decoupled from gating — the ungated probe channel is still metered"):
    withCleanAccounts {
      val usage = TokenUsage(inputTokens = 32, outputTokens = 8, cacheReadTokens = Some(0), cacheWriteTokens = Some(0))
      val adapter = new ScriptedAdapter("ok", Some(usage), boom = false)
      val rows = new AtomicReference(List.empty[LlmUsageRecord])
      LlmKernel.UsageSink.install(r => IO(rows.updateAndGet(_ :+ r)))
      LlmKernel
        .run(
          new StubRegistry(adapter),
          kernelCall(LlmKernel.Gating.Ungated, LlmKernel.MeterKind.Probe)
        )
        .unsafeRunSync()
      assertEquals(rows.get().size, 1, "the ungated (probe) channel must still reach the usage sink")
      assertEquals(CacheStats.totalSnapshot.requests, 1L)
      assertEquals(
        CacheAttribution.snapshot.warmup.inputTokens,
        32L,
        "probe traffic is booked as warmup, never as business attribution"
      )
      assertEquals(CacheAttribution.snapshot.business.inputTokens, 0L)
    }

  // ── K7 模型来源显式 ──────────────────────────────────────────────────────

  test("K7: the chain-based legs resolve candidates through the kernel's single resolver (one resolver, source-scan)"):
    // ① small-llm / decision-route 两条链式来源只许经内核解析（腿里不得自建链读法）
    val resolvers = List(
      "nebflow/llm/decision/LlmAllocator.scala" ->
        "LlmKernel.candidates(registry, LlmKernel.CandidateSource.SmallLlmModel)",
      "nebflow/llm/decision/DelegateJudgeMachine.scala" ->
        "LlmKernel.candidates(registry, LlmKernel.CandidateSource.DecisionRoute(\"delegate-judge\"))"
    )
    resolvers.foreach { case (f, needle) =>
      val text = os.read(repoRoot / "src" / "main" / "scala" / os.RelPath(f))
      assert(text.contains(needle), s"$f must resolve its candidates via the kernel resolver: $needle")
    }
    // ② 每条辅腿的 LLM 调用都必须显式声明来源（三条腿都在用 KernelCall + CandidateSource）
    List(
      "nebflow/llm/decision/LlmAllocator.scala",
      "nebflow/llm/decision/DelegateJudgeMachine.scala",
      "nebflow/llm/HealthMonitor.scala"
    ).foreach { f =>
      val text = os.read(repoRoot / "src" / "main" / "scala" / os.RelPath(f))
      assert(text.contains("LlmKernel.KernelCall("), s"$f must declare its calls as a KernelCall (K7)")
      assert(text.contains("source = LlmKernel.CandidateSource."), s"$f must state its candidate source explicitly (K7)")
    }

  test("K7: CandidateSource has no implicit default — every leg states its source (compile-time, pinned here)"):
    val sources = List(
      LlmKernel.CandidateSource.SmallLlmModel,
      LlmKernel.CandidateSource.NebulaChain,
      LlmKernel.CandidateSource.Slot(None),
      LlmKernel.CandidateSource.DecisionRoute("delegate-judge"),
      LlmKernel.CandidateSource.Explicit(Nil)
    )
    assertEquals(sources.distinct.size, 5, "the source enum is closed and explicit")

end LlmKernelContractSpec
