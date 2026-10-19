package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.jev.{
  CatalogEntry,
  JevAllocation,
  JevFallbackKind,
  JevGateOutcome,
  JevGateReason,
  JevGateSnapshot,
  PluginAllocator
}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmProtocol, PathUtil, ProviderConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 插件分配族（jev-plugins 批 #1226）判读——纯函数面＋三态编排（stub 腿注入，网络腿
 * 不在 spec 范围）：
 *
 *  - LlmAllocator 回文解析：裸 JSON / 围栏 / 混排皆容；未知包名剔除；缺键/形错拒；
 *  - 编排三态：Jev 优先（gate Applied＋Jev 腿成功 ⇒ LLM 零调用）／LLM 兜底（gate
 *    Off 或 Jev 腿回落 ⇒ 兜底腿产出）／fail-open（两腿全败 ⇒ 空集回落，绝不抛）；
 *  - 空目录短路口：零模型调用，空集分配成功；
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

  // ── 编排三态（stub 腿注入） ─────────────────────────────────────────────

  private def jevAnswering(names: List[String]): JevAllocator =
    new JevAllocator(
      new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) =
          IO.pure(
            Right(
              DecisionResponse(
                None,
                catalog.map { e =>
                  val yes = if names.contains(e.name) then 1.0 else 0.0
                  val no = 1.0 - yes
                  s"need_${e.name}" -> DecisionAnswer.Choice(
                    if yes >= DecisionAnswer.YesThreshold then "yes" else "no",
                    None,
                    Map("yes" -> yes, "no" -> no)
                  )
                }.toMap,
                None
              )
            )
          )
    )

  private def jevFailing(kind: DecisionError): JevAllocator =
    new JevAllocator(
      new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Left(kind))
    )

  private def machine(
    jev: Option[JevAllocator],
    llmOpt: Option[LlmAllocator],
    gate: JevGateOutcome,
    health: Option[DecisionHealthMonitor] = None
  ): JevPluginAllocator =
    new JevPluginAllocator(IO.pure(jev), IO.pure(llmOpt), IO.pure(gate), health)

  private val gateOn = JevGateOutcome.Applied(
    JevGateSnapshot(
      configured = true,
      enabled = true,
      provider = "typesafe-jev",
      choiceHighBasePolicy = ""
    )
  )
  private val gateOff = JevGateOutcome.Off(JevGateReason.NotConfigured)

  test("three states ① jev: gate applied + jev leg allocates — the llm leg is never consulted") {
    val llmCalls = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      machine(
        Some(jevAnswering(List("backend-dev"))),
        Some(llm(Some("""{"plugins":["visual-report"]}"""), llmCalls)),
        gateOn
      )
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.Jev))
          assertEquals(r.allocation, JevAllocation.Allocated(List("backend-dev")))
          assertEquals(llmCalls.size, 0, "the llm fallback leg must not run when the jev leg allocated")
        }
    }
  }

  test("three states ② llm-fallback: gate off → the llm leg decides directly (原文: 未配 Jev → LlmAllocator)") {
    withTempDataRoot { _ =>
      machine(None, Some(llm(Some("""{"plugins":["visual-report"]}"""))), gateOff)
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(r.allocation, JevAllocation.Allocated(List("visual-report")))
        }
    }
  }

  test("three states ②b llm-fallback: jev leg ran but fell back → the llm leg catches (判定机同族: 失败也兜)") {
    withTempDataRoot { _ =>
      machine(
        Some(jevFailing(DecisionError.Timeout("boom"))),
        Some(llm(Some("""{"plugins":["backend-dev"]}"""))),
        gateOn
      )
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(r.allocation, JevAllocation.Allocated(List("backend-dev")))
        }
    }
  }

  test("three states ③ fail-open: both legs fail → typed fallback, never a throw") {
    withTempDataRoot { _ =>
      machine(Some(jevFailing(DecisionError.Transport("down"))), Some(llm(None)), gateOn)
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, None)
          r.allocation match
            case JevAllocation.FellBack(JevFallbackKind.Failure, reason) =>
              assert(reason.toLowerCase.contains("llm"), s"the diagnostic names the failing leg: $reason")
            case other => fail(s"expected fail-open fallback, got: $other")
        }
    }
  }

  test("empty catalog short-circuits: zero model calls on both legs, an explicit empty allocation") {
    val llmCalls = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      machine(Some(jevAnswering(Nil)), Some(llm(None, llmCalls)), gateOn)
        .allocatePlugins("delegate-x", "task", Nil)
        .map { r =>
          assertEquals(r.allocation, JevAllocation.Allocated(Nil))
          assertEquals(llmCalls.size, 0)
        }
    }
  }

  test("health write legs: a success turns the monitor Up; a total failure turns it Down with the classified reason") {
    withTempDataRoot { _ =>
      val up = DecisionHealthMonitor.create.flatMap { m =>
        machine(None, Some(llm(Some("""{"plugins":["backend-dev"]}"""))), gateOff, health = Some(m))
          .allocatePlugins("d", "t", catalog) *> m.getDecisionHealth
      }
      val down = DecisionHealthMonitor.create.flatMap { m =>
        machine(None, Some(llm(None)), gateOff, health = Some(m))
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

  test("the llm prompt carries the catalog and the membership rule (state template single source)") {
    val calls = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      llm(Some("""{"plugins":[]}"""), calls).allocateFor("s", "a visual task", catalog).map { _ =>
        val prompt = calls("util-1")
        assert(prompt.contains(JevAllocator.CatalogHeader), s"catalog header present:\n$prompt")
        assert(prompt.contains("### visual-report"), "catalog entries rendered")
        assert(prompt.contains("""{"plugins": ["<package-name>", ...]}"""), "the JSON answer shape is stated")
      }
    }
  }

end LlmAllocatorSpec
