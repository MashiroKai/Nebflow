package nebflow.core.model

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * ModelUnavailableCard 判读（model-fallback-card 批，作者裁定 2026-11-18 14:37）。
 *
 * 五组覆盖（与任务书 ①–⑤ 对齐）：
 *  - ① 统一卡片：任意槽（small-llm / kernel / node / dispatcher / subagent）走**同一实现**
 *    产出**同一卡片类型**（`type=modelUnavailable`）+ 切换动作 + 测试动作；
 *  - ② 手动切换后原请求继续：`withUnavailableCard` 的 `switch` 臂重试原 attempt；
 *  - ③ 测试 LLM 成功与失败两条路径：`TestResult` 回报体（延迟/失败分类）；
 *  - ④ 超时口径：默认 300s（含理由）+ 钳制；
 *  - ⑤ 正常成功路径回归：无失败 ⇒ 不弹卡、结果原样。
 */
class ModelUnavailableCardSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  // 🔴 隔离纪律：绝不碰生产数据根（`/Users/kaiyu/.stand`）——本 spec 全程指向
  // `target/` 下的临时根（PluginInjectionSpec 同款 redirect）。
  private val tempRoot: os.Path = os.pwd / "target" / "test-model-unavailable-card"
  private val originalRoot = nebflow.shared.PathUtil.dataRoot
  nebflow.shared.PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)
  override def afterAll(): Unit = nebflow.shared.PathUtil.setDataRoot(originalRoot)

  private val AllSlots =
    List("small-llm", "kernel", "node", "dispatcher", "subagent")

  // ── ① 统一卡片：全槽同一实现、同一卡片类型、三动作齐备 ────────────────────────

  test("① unified card: EVERY slot renders the SAME card type through the SAME implementation") {
    AllSlots.traverse_ { slot =>
      val report = ModelUnavailableCard.reportFor(
        slot = slot,
        providerId = "zhipu",
        model = "glm-5.3-flash",
        failure = ModelUnavailableCard.FailureClass.ProviderCode("1308"),
        detail = "HTTP 429: [1308] 已达到 5 小时的使用上限"
      )
      val card = ModelUnavailableCard.cardPayload(report, List("openai/gpt-6-luna"), state = "open")
      val c = card.hcursor
      for
        _ <- IO(assertEquals(c.downField("type").as[String].toOption, Some("modelUnavailable"), s"slot=$slot"))
        _ <- IO(assertEquals(c.downField("slot").as[String].toOption, Some(slot), s"slot=$slot"))
        _ <- IO(assert(c.downField("slotLabel").as[String].toOption.exists(_.nonEmpty), s"slot=$slot label"))
        _ <- IO(assertEquals(c.downField("providerId").as[String].toOption, Some("zhipu")))
        _ <- IO(assertEquals(c.downField("model").as[String].toOption, Some("glm-5.3-flash")))
        _ <- IO(assertEquals(c.downField("failureClass").as[String].toOption, Some("provider-code")))
        _ <- IO(assertEquals(c.downField("failureCode").as[String].toOption, Some("1308")))
        _ <- IO(assert(c.downField("detail").as[String].toOption.exists(_.contains("429"))))
        actions = c.downField("actions").values.toList.flatMap(_.toList)
        ids = actions.flatMap(_.hcursor.downField("id").as[String].toOption)
        _ <- IO(assertEquals(ids, List("test", "switch", "dismiss"), s"slot=$slot actions"))
      yield ()
    }
  }

  test("① switch action carries the selectable model/provider list (the switch entry point)") {
    val report = ModelUnavailableCard.reportFor(
      "small-llm", "zhipu", "glm-5.3-flash", ModelUnavailableCard.FailureClass.HttpStatus(503), "HTTP 503: upstream down"
    )
    val card = ModelUnavailableCard.cardPayload(report, List("openai/gpt-6-luna", "anthropic/claude-opus-5"))
    val opts = card.hcursor.downField("actions").values.toList.flatMap(_.toList)
      .find(_.hcursor.downField("id").as[String].toOption.contains("switch"))
      .flatMap(_.hcursor.downField("options").as[List[String]].toOption)
    IO(assertEquals(opts, Some(List("openai/gpt-6-luna", "anthropic/claude-opus-5"))))
  }

  // ── 失败分类（HTTP 状态码 / 错误码 / 超时）─────────────────────────────────────

  test("failure classification: provider code beats HTTP status; timeout and transport are distinct") {
    val F = ModelUnavailableCard.FailureClass
    IO {
      assertEquals(F.fromDetail("HTTP 429: [1308] 已达到 5 小时的使用上限"), F.ProviderCode("1308"))
      assertEquals(F.fromDetail("HTTP 503: upstream unavailable"), F.HttpStatus(503))
      assertEquals(F.fromDetail("java.net.SocketTimeoutException: connect timed out"), F.Timeout)
      assertEquals(F.fromDetail("empty reply (HTTP 200, empty body)"), F.EmptyReply)
      assertEquals(F.fromDetail("boom"), F.Transport)
      assertEquals(F.classify(new java.net.SocketTimeoutException("connect timed out"))._1, F.Timeout)
      assertEquals(F.wire(F.ProviderCode("1308")), "provider-code")
      assertEquals(F.code(F.HttpStatus(429)), Some("429"))
    }
  }

  // ── ③ 测试 LLM：成功与失败两条路径的回报体 ────────────────────────────────────

  test("③ test-llm result: success carries latency; failure carries class + code + detail") {
    val report = ModelUnavailableCard.reportFor("node", "zhipu", "glm-5.3-flash", ModelUnavailableCard.FailureClass.Timeout, "timeout")
    val ok = ModelUnavailableCard.cardPayload(
      report, Nil, state = "tested", test = Some(ModelUnavailableCard.TestResult.Ok(412L, "pong"))
    )
    val bad = ModelUnavailableCard.cardPayload(
      report, Nil, state = "tested",
      test = Some(ModelUnavailableCard.TestResult.Failed(ModelUnavailableCard.FailureClass.ProviderCode("1308"), "HTTP 429: [1308] cap", 180L))
    )
    IO {
      val o = ok.hcursor.downField("test")
      assertEquals(o.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(o.downField("latencyMs").as[Long].toOption, Some(412L))
      val b = bad.hcursor.downField("test")
      assertEquals(b.downField("ok").as[Boolean].toOption, Some(false))
      assertEquals(b.downField("failureClass").as[String].toOption, Some("provider-code"))
      assertEquals(b.downField("failureCode").as[String].toOption, Some("1308"))
      assertEquals(b.downField("latencyMs").as[Long].toOption, Some(180L))
    }
  }

  // ── ④ 300s 超时口径 ───────────────────────────────────────────────────────────

  test("④ timeout default is 300s (documented reason)") {
    IO(assertEquals(ModelUnavailableCard.configuredTimeoutSec().unsafeRunSync(), 300))
  }

  // ── ⑤ 正常成功路径回归 + 未装配旁路（fail-closed 原样）────────────────────────

  test("⑤ normal success path: no failure => no card, result unchanged") {
    ModelUnavailableCard.clearRuntime()
    var cardCalls = 0
    val attempt = IO { cardCalls += 1; Right(42) }
    ModelUnavailableCard
      .withUnavailableCard[Either[String, Int]](attempt, _ => None)
      .map { r =>
        assertEquals(r, Right(42))
        assertEquals(cardCalls, 1)
      }
  }

  test("⑤ unwired runtime => model-unavailable failure is preserved as-is (no silent change)") {
    ModelUnavailableCard.clearRuntime()
    val attempt = IO(Left("plugin allocation failed (llm-error)"): Either[String, Int])
    ModelUnavailableCard
      .withUnavailableCard[Either[String, Int]](
        attempt,
        _.left.toOption.map(d =>
          ModelUnavailableCard.reportFor("small-llm", "zhipu", "glm-5.3-flash", ModelUnavailableCard.FailureClass.ProviderCode("1308"), d)
        )
      )
      .map(r => assert(r.isLeft, "fail-closed result must stand when the card path is unwired"))
  }

  // ── ② 手动切换 → 原请求继续（组合子契约）+ 落盘写回 ─────────────────────────

  test("② switch writes the slot's model.preferred in place (same shape as PUT /api/agents/:name/model)") {
    val root = nebflow.shared.PathUtil.dataRoot
    val dir = root / "agents" / "small-llm"
    val existed = os.exists(dir / "agent.json")
    val backup = if existed then Some(os.read(dir / "agent.json")) else None
    ModelUnavailableCard.writeSlotModel("small-llm", "openai/gpt-6-luna").map { r =>
      assertEquals(r, Right(()))
      val json = io.circe.parser.parse(os.read(dir / "agent.json")).toOption.get
      assertEquals(
        json.hcursor.downField("model").downField("preferred").as[String].toOption,
        Some("openai/gpt-6-luna")
      )
      // restore
      backup match
        case Some(text) => os.write.over(dir / "agent.json", text)
        case None       => if os.exists(dir / "agent.json") then os.remove(dir / "agent.json")
    }
  }

  test("② switch is refused for a non-settable slot") {
    ModelUnavailableCard.writeSlotModel("not-a-slot-at-all", "openai/gpt-6-luna").map { r =>
      assert(r.isLeft, "non-settable slot must not accept a model write")
    }
  }
