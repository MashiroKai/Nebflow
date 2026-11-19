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
 * 插件分配**探活分腿记账**判读（jev-retire 批 2026-11-16 改写：分配 = 小模型
 * 单腿——face 注入 mock registry）：
 *
 *  - 成功 ⇒ 通道 Up，`legs("llm")` = Up；
 *  - 失败（无候选/链耗尽/回文不可用）⇒ 通道 Down（reason 在案），`legs("llm")`
 *    = Down——REST `toJson` 带 `legs` 键（`GET /api/decision/health` 数据源）；
 *  - 🔴 **jev 腿键永不出现**（退役回归锚：任何 face 产出都不造 "jev" 腿状态）；
 *  - 既有直写腿（`recordDecisionSuccess` / `recordDecisionFailure`）三态语义
 *    不受影响。
 *
 * 🔴 测试体纪律：断言一律落在**最外层 awaited IO** 里（CatsEffectSuite 不展平
 * 嵌套 IO——嵌套 .map 里的断言永不执行 = 空转假绿）。
 */
class PluginAllocatorFallbackSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val catalog = List(
    CatalogEntry("backend-dev", "Scala backend development"),
    CatalogEntry("visual-report", "human-facing reports and decks")
  )

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-llm-fallback-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

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

  /**
   * 跑一次 face 分配并取回（通道终态, 分腿终态, 分配结果）——读面统一进最外层
   * awaited IO（无嵌套空转）。
   */
  private def runAllocation(
    m: DecisionHealthMonitor,
    registry: FaceRegistry
  ): IO[(DecisionHealth, Map[String, DecisionHealth], PluginAllocator.PluginAllocationResult)] =
    PluginAllocatorMachine.face(registry, health = Some(m))
      .allocatePlugins("delegate-x", "task", catalog)
      .flatMap(r => (m.getDecisionHealth, m.legs).mapN((h, l) => (h, l, r)))

  // ── 成功腿：llm Up，通道 Up ─────────────────────────────────────────────

  test("leg bookkeeping: llm leg allocates → channel Up, legs show llm Up (and only llm)") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap { m =>
          runAllocation(m, new FaceRegistry(
            Map("p" -> ScriptedAdapter(Set.empty, """{"plugins":["backend-dev","visual-report"]}""", TrieMap.empty)),
            List(cand("p", "util-1"))
          ))
        }
        .map { case (channel, legs, result) =>
          assertEquals(result.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(result.allocation, JevAllocation.Allocated(List("backend-dev", "visual-report")))
          assertEquals(channel, DecisionHealth.Up)
          assertEquals(legs.get("llm"), Some(DecisionHealth.Up))
          assertEquals(legs.keySet, Set("llm"), "the retired jev leg key is never invented")
        }
    }
  }

  // ── 失败腿：llm Down，通道 Down（reason 在案）───────────────────────────

  test("leg bookkeeping: total failure → channel Down with the classified reason; legs show llm Down") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap { m =>
          runAllocation(m, new FaceRegistry(Map("p" -> ScriptedAdapter(Set("util-1"), "", TrieMap.empty)), List(cand("p", "util-1"))))
        }
        .map { case (channel, legs, result) =>
          assertEquals(result.via, None, "fail-open: no via when the leg failed")
          channel match
            case DecisionHealth.Down(reason, _) =>
              assert(reason.contains("llm"), s"the effective failure names the llm leg: $reason")
            case other => fail(s"expected channel Down after the leg failed, got: $other")
          legs.get("llm") match
            case Some(DecisionHealth.Down(reason, _)) =>
              assert(reason.nonEmpty, "the llm leg state carries its own diagnostic")
            case other => fail(s"expected a Down llm leg, got: $other")
          assertEquals(legs.keySet, Set("llm"), "the retired jev leg key is never invented")
        }
    }
  }

  test("structural fail-open (no candidates) is leg-recorded too — not a silent gap") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap { m => runAllocation(m, new FaceRegistry(Map.empty, Nil)) }
        .map { case (channel, legs, result) =>
          assertEquals(result.allocation, JevAllocation.FellBack(JevFallbackKind.Failure, "small model unavailable"))
          channel match
            case DecisionHealth.Down(reason, _) => assert(reason.contains("small model unavailable"))
            case other                          => fail(s"expected Down, got: $other")
          legs.get("llm") match
            case Some(DecisionHealth.Down(_, _)) => ()
            case other                           => fail(s"expected a Down llm leg, got: $other")
        }
    }
  }

  // ── REST 面：legs 块到达 GET /api/decision/health 的数据源 ────────────────

  test("leg bookkeeping in toJson: the llm-only leg state reaches the REST face") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap { m =>
          PluginAllocatorMachine
            .face(new FaceRegistry(Map("p" -> ScriptedAdapter(Set("util-1"), "", TrieMap.empty)), List(cand("p", "util-1"))), health = Some(m))
            .allocatePlugins("delegate-x", "task", catalog) *> m.toJson
        }
        .map { json =>
          val legs = json.hcursor.downField("legs")
          assertEquals(legs.downField("llm").get[String]("status").toOption, Some("down"), "legs.llm.status = down")
          assert(
            legs.downField("llm").get[String]("reason").toOption.exists(_.contains("llm")),
            "legs.llm.reason names the classified failure"
          )
          assert(legs.downField("jev").focus.isEmpty, "the retired jev leg never appears in the REST legs block")
          assertEquals(
            json.hcursor.get[String]("status").toOption,
            Some("down"),
            "top-level status is the effective channel state"
          )
        }
    }
  }

  test("legacy faces unaffected: a plain success/failure write keeps the tri-state semantics (transport write path)") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap(m =>
          m.recordDecisionSuccess() *> m.recordDecisionFailure("decision API timeout: x") *> (m.toJson, m.legs)
            .mapN((j, l) => (j, l))
        )
        .map { case (json, legs) =>
          assertEquals(json.hcursor.get[String]("status").toOption, Some("down"))
          assert(json.hcursor.get[String]("reason").toOption.exists(_.contains("timeout")))
          assertEquals(legs, Map.empty[String, DecisionHealth], "no leg bookkeeping happened on the legacy write path")
        }
    }
  }

end PluginAllocatorFallbackSpec
