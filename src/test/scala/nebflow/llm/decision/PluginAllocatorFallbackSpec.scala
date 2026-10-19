package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevGateOutcome, JevGateReason, JevGateSnapshot, PluginAllocator}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmProtocol, PathUtil, ProviderConfig}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * 插件分配**自动回退**判读（jev-panel-threefix 批，2026-10-09 用户反馈②③）——
 * stub 腿注入，网络腿不在 spec 范围：
 *
 *  - Jev 腿不可用（Timeout / Auth / Transport——超时、网络、鉴权失败一视同仁）
 *    ⇒ **llm 腿自动接管，分配不丢**（via = LlmFallback，集合照产）；
 *  - 探活**分腿记账**（三改③）：jev 腿终态与通道整体终态分开记录——回退后
 *    通道 up（llm 接管）时 `getDecisionHealth` = Up 但 `legs("jev")` = Down，
 *    REST `toJson` 带 `legs` 键（设置面「Jev 不可用 → 已回退 llm」的数据源）；
 *  - 回退后 llm 腿再挂 ⇒ 通道整体 Down（双腿 reason 在案），fail-open 不丢面。
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
    val tmp = os.temp.dir(prefix = "nb-jev-fallback-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

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

  private def machine(
    jev: Option[JevAllocator],
    llmOpt: Option[LlmAllocator],
    gate: JevGateOutcome,
    health: Option[DecisionHealthMonitor] = None
  ): JevPluginAllocator =
    new JevPluginAllocator(IO.pure(jev), IO.pure(llmOpt), IO.pure(gate), health)

  /**
   * 跑一次分配并取回（通道终态, 分腿终态, 分配结果）——读面统一进最外层
   * awaited IO（无嵌套空转）。
   */
  private def runAllocation(
    m: DecisionHealthMonitor,
    gate: JevGateOutcome,
    jev: Option[JevAllocator],
    llmReply: Option[String],
    llmCalls: TrieMap[String, String] = TrieMap.empty
  ): IO[(DecisionHealth, Map[String, DecisionHealth], PluginAllocator.PluginAllocationResult)] =
    machine(jev, Some(llm(llmReply, llmCalls)), gate, health = Some(m))
      .allocatePlugins("delegate-x", "task", catalog)
      .flatMap(r => (m.getDecisionHealth, m.legs).mapN((h, l) => (h, l, r)))

  private val gateOn = JevGateOutcome.Applied(
    JevGateSnapshot(
      configured = true,
      enabled = true,
      provider = "typesafe-jev",
      choiceHighBasePolicy = ""
    )
  )
  private val gateOff = JevGateOutcome.Off(JevGateReason.NotConfigured)

  // ── 反馈②：Jev 腿不可用 ⇒ 自动回退 llm，分配不丢 ────────────────────────

  test("fallback on timeout: jev leg times out → llm leg takes over, the allocation is NOT lost") {
    withTempDataRoot { _ =>
      machine(
        Some(jevFailing(DecisionError.Timeout("allocation deadline exceeded"))),
        Some(llm(Some("""{"plugins":["backend-dev","visual-report"]}"""))),
        gateOn
      )
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(r.allocation, JevAllocation.Allocated(List("backend-dev", "visual-report")))
        }
    }
  }

  test("fallback on auth failure: jev leg 401s → llm leg takes over (超时/网络/鉴权一视同仁)") {
    withTempDataRoot { _ =>
      machine(
        Some(jevFailing(DecisionError.Auth("401 unauthorized"))),
        Some(llm(Some("""{"plugins":["visual-report"]}"""))),
        gateOn
      )
        .allocatePlugins("delegate-x", "task", catalog)
        .map { r =>
          assertEquals(r.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(r.allocation, JevAllocation.Allocated(List("visual-report")))
        }
    }
  }

  // ── 反馈③：分腿记账——回退态如实可见 ────────────────────────────────────

  test("leg bookkeeping: jev down + llm took over → channel Up but legs show jev Down + llm Up") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap(m =>
          runAllocation(
            m,
            gateOn,
            Some(jevFailing(DecisionError.Timeout("boom"))),
            Some("""{"plugins":["backend-dev"]}""")
          )
        )
        .map { case (channel, legs, result) =>
          assertEquals(result.via, Some(PluginAllocator.AllocationVia.LlmFallback))
          assertEquals(channel, DecisionHealth.Up, "the channel produced an allocation (llm takeover) — overall Up")
          legs.get("jev") match
            case Some(DecisionHealth.Down(reason, _)) =>
              assert(reason.contains("jev leg"), s"the jev leg state carries its own diagnostic: $reason")
            case other => fail(s"expected a Down jev leg, got: $other")
          assertEquals(legs.get("llm"), Some(DecisionHealth.Up), "llm leg recorded Up")
        }
    }
  }

  test("leg bookkeeping in toJson: the fallback story reaches the REST face (设置面「已回退 llm」数据源)") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap { m =>
          machine(
            Some(jevFailing(DecisionError.Auth("401"))),
            Some(llm(Some("""{"plugins":["backend-dev"]}"""))),
            gateOn,
            health = Some(m)
          )
            .allocatePlugins("delegate-x", "task", catalog) *> m.toJson
        }
        .map { json =>
          val legs = json.hcursor.downField("legs")
          assertEquals(legs.downField("jev").get[String]("status").toOption, Some("down"), "legs.jev.status = down")
          assert(
            legs.downField("jev").get[String]("reason").toOption.exists(_.contains("auth")),
            "legs.jev.reason names the classified failure"
          )
          assertEquals(legs.downField("llm").get[String]("status").toOption, Some("up"), "legs.llm.status = up")
          assertEquals(
            json.hcursor.get[String]("status").toOption,
            Some("up"),
            "top-level status stays the effective channel state"
          )
        }
    }
  }

  test("fallback then llm leg fails too → channel Down with the llm reason; both legs recorded down") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap(m => runAllocation(m, gateOn, Some(jevFailing(DecisionError.Transport("net down"))), None))
        .map { case (channel, legs, result) =>
          assertEquals(result.via, None, "fail-open: no via when both legs failed")
          channel match
            case DecisionHealth.Down(reason, _) =>
              assert(reason.contains("llm"), s"the effective failure names the llm leg: $reason")
            case other => fail(s"expected channel Down after both legs failed, got: $other")
          assert(legs.get("jev").exists(_.toString.contains("Down")), "jev leg down")
          assert(legs.get("llm").exists(_.toString.contains("Down")), "llm leg down too")
        }
    }
  }

  test("gate off → only the llm leg is recorded (the jev leg never ran); the channel stays Up on llm success") {
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap(m => runAllocation(m, gateOff, None, Some("""{"plugins":["backend-dev"]}""")))
        .map { case (channel, legs, _) =>
          assertEquals(legs.contains("jev"), false, "gate off = the jev leg never ran — no jev leg state invented")
          assertEquals(legs.get("llm"), Some(DecisionHealth.Up))
          assertEquals(channel, DecisionHealth.Up)
        }
    }
  }

  test("jev leg healthy → legs record jev Up only; the llm leg is never consulted") {
    val llmCalls = TrieMap.empty[String, String]
    withTempDataRoot { _ =>
      DecisionHealthMonitor.create
        .flatMap(m =>
          runAllocation(m, gateOn, Some(jevAnswering(List("visual-report"))), Some("""{"plugins":["x"]}"""), llmCalls)
        )
        .map { case (channel, legs, _) =>
          assertEquals(llmCalls.size, 0, "llm leg untouched when the jev leg allocated")
          assertEquals(legs.get("jev"), Some(DecisionHealth.Up))
          assertEquals(legs.contains("llm"), false, "the llm leg did not run — no llm leg state invented")
          assertEquals(channel, DecisionHealth.Up)
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
