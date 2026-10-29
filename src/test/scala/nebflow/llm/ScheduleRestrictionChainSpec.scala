package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

import java.time.ZoneId

/**
 * 时段闸链过滤 spec（model-schedule-unavailable 批，2026-10-29）——`ProviderRegistry`
 * 链出口过滤面（设计件 §4/§7）：
 *   - 窗内候选剔除且**链序不变**；窗外/缺省 = 逐字原样（零行为变化铁律）；
 *   - provider 级 ∪ 模型级窗口；跨午夜；
 *   - 储备层照旧追加且未受限者可承接（链深不因时段塌成 0）；
 *   - 全受限 ⇒ 链出口给空链（终局报错单点在 LlmInterface，spec2 端到端腿覆盖）。
 *
 * 时钟注入 = 覆写 [[ProviderRegistry.nowMillis]]（观测缝），固定时刻构造 ⇒ 判据
 * 不依赖跑测时刻。配置全在 spec 内构造，不读/不写真实 HOME。
 */
class ScheduleRestrictionChainSpec extends CatsEffectSuite:

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]
  private val zone = ZoneId.systemDefault()

  /** 2026-10-29 = 周四（ISO 4）。 */
  private def at(day: Int, h: Int, m: Int): Long =
    java.time.LocalDate.of(2026, 10, day).atTime(h, m).atZone(zone).toInstant.toEpochMilli

  private val ThuInsideWindow = at(29, 15, 0) // 14:00-18:00 窗内
  private val ThuOutsideWindow = at(29, 10, 0) // 窗外

  /** 链出口过滤的时钟缝：固定时刻实例。 */
  private final class ClockedRegistry(
    configRef: Ref[IO, NebflowServiceConfig],
    fixedNow: Long
  ) extends ProviderRegistry(configRef, NullBackend):
    override protected def nowMillis(): Long = fixedNow

  private def providerWith(
    models: List[ModelConfig],
    windows: List[UnavailableWindow] = Nil
  ): ProviderConfig =
    ProviderConfig(
      baseUrl = "http://127.0.0.1:1",
      apiKey = "k",
      protocol = LlmProtocol.Anthropic,
      models = models,
      unavailableWindows = if windows.isEmpty then None else Some(windows) // Option 形：deriveDecoder 不认默认值
    )

  private def mkRegistry(cfg: NebflowServiceConfig, now: Long): IO[ClockedRegistry] =
    Ref.of[IO, NebflowServiceConfig](cfg).map(ref => ClockedRegistry(ref, now))

  private def refs(cs: List[ModelCandidate]): List[String] =
    cs.map(c => s"${c.providerId}/${c.model}")

  private val Window14to18 = UnavailableWindow("14:00", "18:00")

  // ── T1：窗内剔除、窗序不变 ────────────────────────────────

  test("T1 窗内：受限 provider 的候选被剔除，其余保持原链序") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm")), windows = List(Window14to18)),
      "kimi" -> providerWith(List(ModelConfig("kimi-for-coding")), windows = List(Window14to18)),
      "cmdcode" -> providerWith(List(ModelConfig("deepseek-flash")))
    )))
    val chain = AgentModelConfig(
      preferred = Some("zhipu/glm"),
      fallbacks = List("kimi/kimi-for-coding", "cmdcode/deepseek-flash")
    )
    mkRegistry(cfg, ThuInsideWindow).flatMap(_.getCandidatesForAgent(Some(chain))).map { cs =>
      val r = refs(cs)
      assert(!r.contains("zhipu/glm"), s"zhipu 在窗内必须被剔除；实际链 = $r")
      assert(!r.contains("kimi/kimi-for-coding"), s"kimi 在窗内必须被剔除；实际链 = $r")
      assertEquals(r.headOption, Some("cmdcode/deepseek-flash"), s"承接方 = 未受限的 cmdcode；实际 = $r")
    }
  }

  test("T2 窗外：同一配置链**逐字等于**无窗口配置的链（零行为变化铁律）") {
    val baseModels = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm"))),
      "kimi" -> providerWith(List(ModelConfig("kimi-for-coding"))),
      "cmdcode" -> providerWith(List(ModelConfig("deepseek-flash")))
    )
    val withWindows = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm")), windows = List(Window14to18)),
      "kimi" -> providerWith(List(ModelConfig("kimi-for-coding")), windows = List(Window14to18)),
      "cmdcode" -> providerWith(List(ModelConfig("deepseek-flash")))
    )
    val chain = AgentModelConfig(
      preferred = Some("zhipu/glm"),
      fallbacks = List("kimi/kimi-for-coding", "cmdcode/deepseek-flash")
    )
    for
      plain <- mkRegistry(NebflowServiceConfig(llm = ServiceLlmConfig(providers = baseModels)), ThuOutsideWindow)
        .flatMap(_.getCandidatesForAgent(Some(chain)))
      gated <- mkRegistry(NebflowServiceConfig(llm = ServiceLlmConfig(providers = withWindows)), ThuOutsideWindow)
        .flatMap(_.getCandidatesForAgent(Some(chain)))
    yield
      assertEquals(refs(gated), refs(plain), "窗外必须逐字等于无窗口配置的链")
      assertEquals(refs(gated).take(3), List("zhipu/glm", "kimi/kimi-for-coding", "cmdcode/deepseek-flash"))
  }

  // ── T3/T4：provider 级 vs 模型级 ────────────────────────────

  test("T3 provider 级窗口对该 provider 全部模型生效") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "kimi" -> providerWith(List(ModelConfig("a"), ModelConfig("b")), windows = List(Window14to18)),
      "ok" -> providerWith(List(ModelConfig("m")))
    )))
    val chain = AgentModelConfig(preferred = Some("kimi/a"), fallbacks = List("kimi/b", "ok/m"))
    mkRegistry(cfg, ThuInsideWindow).flatMap(_.getCandidatesForAgent(Some(chain))).map { cs =>
      assertEquals(refs(cs), List("ok/m"), "provider 级窗口下 kimi 的两个模型都必须被剔除")
    }
  }

  test("T4 模型级窗口只影响该模型，同 provider 其它模型照常") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "kimi" -> providerWith(List(
        ModelConfig("hit", unavailableWindows = Some(List(Window14to18))),
        ModelConfig("free")
      ))
    )))
    val chain = AgentModelConfig(preferred = Some("kimi/hit"), fallbacks = List("kimi/free"))
    mkRegistry(cfg, ThuInsideWindow).flatMap(_.getCandidatesForAgent(Some(chain))).map { cs =>
      assertEquals(refs(cs).headOption, Some("kimi/free"), s"模型级窗口只剔该模型；实际 = ${refs(cs)}")
      assert(!refs(cs).contains("kimi/hit"))
    }
  }

  // ── T5：储备层承接（链深不因时段塌成 0）────────────────────

  test("T5 储备层：预设链两根全受限 ⇒ 未受限的储备模型承接（链非空）") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm")), windows = List(Window14to18)),
      "kimi" -> providerWith(List(ModelConfig("kimi")), windows = List(Window14to18)),
      "cmdcode" -> providerWith(List(ModelConfig("deepseek-flash")))
    )))
    val chain = AgentModelConfig(preferred = Some("zhipu/glm"), fallbacks = List("kimi/kimi"))
    mkRegistry(cfg, ThuInsideWindow).flatMap(_.getCandidatesForAgent(Some(chain))).map { cs =>
      val r = refs(cs)
      assertEquals(r, List("cmdcode/deepseek-flash"), s"只有未受限的储备模型留链；实际 = $r")
    }
  }

  // ── T6：全受限 ⇒ 空链（终局报错单点在 LlmInterface）────────

  test("T6 全受限（含储备层）⇒ 链出口给空链，不静默回退到受限模型") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm")), windows = List(Window14to18)),
      "kimi" -> providerWith(List(ModelConfig("kimi")), windows = List(Window14to18))
    )))
    val chain = AgentModelConfig(preferred = Some("zhipu/glm"), fallbacks = List("kimi/kimi"))
    mkRegistry(cfg, ThuInsideWindow).flatMap(_.getCandidatesForAgent(Some(chain))).map { cs =>
      assertEquals(refs(cs), Nil, s"全受限必须给空链（不得静默回退到受限模型）；实际 = ${refs(cs)}")
    }
  }

  // ── T7：跨午夜 + filterScheduleRestricted（override 补滤通道）──

  test("T7 跨午夜窗：23:30 与 06:59 受限、07:00 与 22:59 不受限（filterScheduleRestricted 同一谓词）") {
    val overnight = UnavailableWindow("23:00", "07:00")
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "kimi" -> providerWith(List(ModelConfig("k")), windows = List(overnight))
    )))
    def cand = ModelCandidate("kimi", cfg.llm.providers("kimi"), "k")
    for
      night <- mkRegistry(cfg, at(29, 23, 30)).flatMap(_.filterScheduleRestricted(List(cand)))
      dawn <- mkRegistry(cfg, at(30, 6, 59)).flatMap(_.filterScheduleRestricted(List(cand)))
      morning <- mkRegistry(cfg, at(30, 7, 0)).flatMap(_.filterScheduleRestricted(List(cand)))
      evening <- mkRegistry(cfg, at(29, 22, 59)).flatMap(_.filterScheduleRestricted(List(cand)))
    yield
      assertEquals(night._1.map(_.model), Nil, "23:30 在跨午夜窗内")
      assertEquals(dawn._1.map(_.model), Nil, "次日 06:59 仍在窗内")
      assertEquals(morning._1.map(_.model), List("k"), "07:00 整即解")
      assertEquals(evening._1.map(_.model), List("k"), "开窗前可用")
      assertEquals(night._2.map(_._2), List("23:00-07:00"), "受限原因 = 窗口描述（日志/错误消息直出）")
  }

  // ── T8：scheduleRestrictionFor / scheduleWindowsByRef 可见性面 ──

  test("T8 可见性读数：ref 当前受限原因 + 声明窗口 + active 双态") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm")), windows = List(Window14to18)),
      "cmdcode" -> providerWith(List(ModelConfig("deepseek-flash")))
    )))
    for
      inside <- mkRegistry(cfg, ThuInsideWindow)
      r1 <- inside.scheduleRestrictionFor("zhipu/glm")
      r2 <- inside.scheduleRestrictionFor("cmdcode/deepseek-flash")
      byRef <- inside.scheduleWindowsByRef(List("zhipu/glm", "cmdcode/deepseek-flash"))
      outside <- mkRegistry(cfg, ThuOutsideWindow)
      r3 <- outside.scheduleRestrictionFor("zhipu/glm")
    yield
      assertEquals(r1, Some("14:00-18:00"), "窗内：受限原因 = 窗口描述")
      assertEquals(r2, None, "无窗口 = 不受限")
      assertEquals(r3, None, "窗外：不受限")
      assertEquals(byRef, List(
        ("zhipu/glm", List("14:00-18:00"), true),
        ("cmdcode/deepseek-flash", Nil, false)
      ))
  }

  // ── T9：getCandidateForRef 不过滤（探测集必须能取回窗内候选）──

  test("T9 getCandidateForRef 保持纯构造不过滤——HealthMonitor 探测集不被时段闸打穿") {
    val cfg = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map(
      "zhipu" -> providerWith(List(ModelConfig("glm")), windows = List(Window14to18))
    )))
    mkRegistry(cfg, ThuInsideWindow).flatMap(_.getCandidateForRef("zhipu/glm")).map { c =>
      assert(c.isDefined, "窗内候选仍必须可构造（探测恢复路径依赖它）")
    }
  }

  // ── T10：配置 decode 兼容（存量零漂移 + 新键解析 + 非法 days 容忍）──

  test("T10 decode：存量配置（无 unavailableWindows）解析不变；新键两层解析；缺字段容忍") {
    import io.circe.parser.parse
    import io.circe.syntax.*
    // 存量形态：无新键 ⇒ 解析后窗口恒空（零行为变化铁律）
    val legacy = parse("""{"llm":{"providers":{"zhipu":{"baseUrl":"http://x","apiKey":"k",
      "protocol":"anthropic","models":[{"id":"glm","contextWindow":100000}]}}}}""").toOption.get
    val legacyCfg = legacy.as[NebflowServiceConfig].toOption.get
    assertEquals(legacyCfg.llm.providers("zhipu").unavailableWindows, None)
    assertEquals(legacyCfg.llm.providers("zhipu").models.head.unavailableWindows, None)
    // 新键两层 + days
    val modern = parse("""{"llm":{"providers":{"zhipu":{"baseUrl":"http://x","apiKey":"k",
      "protocol":"anthropic",
      "unavailableWindows":[{"from":"14:00","to":"18:00"}],
      "models":[{"id":"glm","contextWindow":100000,
        "unavailableWindows":[{"from":"23:00","to":"07:00","days":[6,7]}]}]}}}}""").toOption.get
    val modernCfg = modern.as[NebflowServiceConfig].toOption.get
    val p = modernCfg.llm.providers("zhipu")
    assertEquals(p.unavailableWindows, Some(List(UnavailableWindow("14:00", "18:00"))))
    assertEquals(p.models.head.unavailableWindows, Some(List(UnavailableWindow("23:00", "07:00", Some(List(6, 7))))))
  }
end ScheduleRestrictionChainSpec
