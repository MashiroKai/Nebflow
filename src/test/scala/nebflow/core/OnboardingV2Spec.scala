package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.*

import java.nio.file.Files

/**
 * Onboarding v2 引擎侧验收（personal-agent 批 2026-10-04）。
 *
 * 覆盖三面（全部机械可查）：
 *  - [[OnboardingModelStep.listConfiguredModels]]：模型列表**来自已配置 provider**
 *    （与设置面同源、同算式 `min(configured, modelMaxContext)`）。
 *  - [[OnboardingModelStep.applySelectionJson]] + `updateConfig`：选定模型 + context
 *    window **写回同一配置存储**（`llm.providers.<id>.models[].contextWindow`），
 *    并且**往返可读**（双向一致）。
 *  - [[OnboardingArtifacts]]：11 题答案 → `Soul.md` / `User.md`；**全跳过路径**留下
 *    结构完整（含 `## 备注`）的骨架；起名结果写 `displayName`（🔴 机制键不变）。
 *
 * 隔离纪律：全部动作在 `setDataRoot` 钉住的临时目录内，零写真实 `~/.nebflow`。
 */
class OnboardingV2Spec extends FunSuite:

  private var originalRoot: os.Path = null
  private var home: os.Path = null

  override def beforeAll(): Unit =
    originalRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-ob2-spec"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(home)

  private def reset(): Unit =
    List(home / "Soul.md", home / "User.md", home / "agents", home / "memory-backups", home / "onboarding.json")
      .foreach(p => if os.exists(p) then os.remove.all(p))
    MemoryStore.invalidateSoulCache()
    MemoryStore.invalidateUserCache()

  private def rootName: String = nebflow.actor.RootAgentIdentity.Name

  // ---------------------------------------------------------------
  // 模型步骤：同源
  // ---------------------------------------------------------------

  private def cfgWith(providers: (String, List[(String, Int, Option[Int])])*): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(providers = providers.map { (pid, models) =>
        pid -> ProviderConfig(
          baseUrl = "https://example.invalid/v1/",
          apiKey = "k",
          protocol = LlmProtocol.OpenAI,
          models = models.map((id, cw, mx) => ModelConfig(id = id, contextWindow = cw, modelMaxContext = mx)).toList
        )
      }.toMap)
    )

  /** `${pid}.models[]` → the `llm.providers.<pid>.models[]` JSON array (the same
    *  shape `nebflow.json` holds). Built by hand: `NebflowServiceConfig` carries
    *  a Decoder only (no Encoder) by design, so the spec writes the wire shape
    *  explicitly — the same thing the config file on disk contains. */
  private def providerModelsJson(models: List[(String, Int, Option[Int])]): Json =
    Json.arr(models.map { (id, cw, mx) =>
      Json.obj(
        "id" -> id.asJson,
        "contextWindow" -> cw.asJson
      ).deepMerge(mx.fold(Json.obj())(v => Json.obj("modelMaxContext" -> v.asJson)))
    }*)

  private def cfgJson(providers: (String, List[(String, Int, Option[Int])])*): Json =
    Json.obj("llm" -> Json.obj("providers" -> Json.fromFields(
      providers.map { (pid, models) =>
        pid -> Json.obj(
          "baseUrl" -> "https://example.invalid/v1/".asJson,
          "apiKey" -> "k".asJson,
          "protocol" -> "openai".asJson,
          "models" -> providerModelsJson(models)
        )
      }
    )))

  test("models: 列表来自已配置 provider，且 effective = min(configured, modelMaxContext)"):
    val cfg = cfgWith(
      "zhipu" -> List(("glm-4.6", 128000, None), ("glm-4.5-air", 200000, Some(32000))),
      "empty" -> Nil
    )
    val rows = OnboardingModelStep.listConfiguredModels(cfg)
    assertEquals(rows.map(_.ref), List("zhipu/glm-4.5-air", "zhipu/glm-4.6"), "按 provider/model 排序")
    val air = rows.find(_.ref == "zhipu/glm-4.5-air").get
    assertEquals(air.effectiveContextWindow, 32000, "modelMaxContext 压低生效窗口")
    val g46 = rows.find(_.ref == "zhipu/glm-4.6").get
    assertEquals(g46.effectiveContextWindow, 128000, "无物理上界 ⇒ 生效值 = 配置值")
    assertEquals(OnboardingModelStep.configuredProviders(cfg), List("zhipu"), "无模型的 provider 不进清单")

  test("models: 零 provider ⇒ 空列表（前端退到「先加一家」引导，不造第二数据源）"):
    assertEquals(OnboardingModelStep.listConfiguredModels(cfgWith()), Nil)

  // ---------------------------------------------------------------
  // 模型步骤：写回同一配置存储 + 双向一致
  // ---------------------------------------------------------------

  test("write-back: contextWindow 写回同一存储，且配置可往返读回（双向一致）"):
    val json = cfgJson("zhipu" -> List(("glm-4.6", 128000, None)))
    OnboardingModelStep.applySelectionJson(json, "zhipu/glm-4.6", Some(64000)) match
      case Left(e) => fail(s"应成功，实际 $e")
      case Right(patched) =>
        // 写回后仍是同一份可解码配置（同源 ⇒ 双向一致的结构保证）
        val round = patched.as[NebflowServiceConfig].toOption.getOrElse(fail("patched 必须仍可解码"))
        assertEquals(round.llm.providers("zhipu").models.head.contextWindow, 64000)
        // 写回不影响别的键（原子改一个字段，不重写整文件）
        assert(patched.hcursor.downField("llm").downField("providers").downField("zhipu").focus.isDefined)

  test("write-back: contextWindow = None ⇒ 只校验 ref，不改任何值"):
    val before = cfgJson("zhipu" -> List(("glm-4.6", 128000, None)))
    OnboardingModelStep.applySelectionJson(before, "zhipu/glm-4.6", None) match
      case Left(e) => fail(s"应成功，实际 $e")
      case Right(patched) =>
        assertEquals(patched.as[NebflowServiceConfig].toOption.map(_.llm.providers("zhipu").models.head.contextWindow), Some(128000))

  test("write-back: 未知 provider / 未知 model ⇒ Left，且不静默新建 provider"):
    val json = cfgJson("zhipu" -> List(("glm-4.6", 128000, None)))
    assert(OnboardingModelStep.applySelectionJson(json, "ghost/glm-4.6", Some(1000)).isLeft)
    assert(OnboardingModelStep.applySelectionJson(json, "zhipu/nope", Some(1000)).isLeft)
    assert(OnboardingModelStep.applySelectionJson(json, "no-slash", Some(1000)).isLeft)

  test("write-back: 改窗值经既有单点链参与注入预算（同一算式，无旁路）"):
    // 32k 窗口 ⇒ 注入硬顶收紧到 12800（与 MemoryBudget 同一算式）
    assertEquals(MemoryBudget.userInjectionHardBytes(32000), 12800L)
    assertEquals(MemoryBudget.agentInjectionHardBytes(32000), 12800L)

  // ---------------------------------------------------------------
  // 模型步骤：选定模型写回配置存储（模型链面）
  // ---------------------------------------------------------------

  test("write-back: 选定模型写回 agents/<root>/agent.json 的 model 键（设置面同一字段）"):
    reset()
    assertEquals(
      OnboardingModelStep.writeSelectedChain("zhipu/glm-4.6").unsafeRunSync(),
      Right(AgentModelConfig(preferred = Some("zhipu/glm-4.6"), fallbacks = Nil)),
      "空链用户 ⇒ 单元素链（fallback 是显式决定，不自动填充）"
    )
    val chain = SchemePolicy.ownChainOf(rootName)
    assertEquals(chain.flatMap(_.preferred), Some("zhipu/glm-4.6"))
    // 设置面的读取面（GET /agents/:name/model 的解析腿）现在必须从 seed 变成
    // own-chain —— 这正是 round-2 复核 fail 的那半条判据。
    val (effective, from) = SchemePolicy.resolveModel(rootName, chain)
    assertEquals(from, SchemePolicy.OwnChainSource, s"设置面 resolvedFrom 必须是 own-chain，实际 $from")
    assertEquals(effective.preferred, Some("zhipu/glm-4.6"))
    // 机制键不因写链而漂移（身份键冻结）
    val agentJson = io.circe.parser.parse(os.read(home / "agents" / rootName / "agent.json")).toOption.get
    assertEquals(agentJson.hcursor.downField("name").as[String].toOption, Some("Nebula"))

  test("write-back: 已有链的用户重跑引导 ⇒ 选定项提升为首选，既有 fallback 深度全保（不塌缩）"):
    reset()
    // 用户已积累的链（真实用户形态：一个 preferred + 三条 fallback）
    os.write(
      home / "agents" / rootName / "agent.json",
      """{"name":"Nebula","model":{"preferred":"zhipu/GLM-5.3-Flash","fallbacks":["cmdcode/deepseek/deepseek-v4.1-flash","kimi/kimi-k3","deepseek/deepseek-flash"]}}""",
      createFolders = true
    )
    OnboardingModelStep.writeSelectedChain("kimi/kimi-k3").unsafeRunSync() match
      case Left(e) => fail(s"应成功，实际 $e")
      case Right(next) =>
        assertEquals(next.preferred, Some("kimi/kimi-k3"))
        // 🔴 旧 preferred 与其余 fallback 全部保留、保序；选定项从后备里去重
        assertEquals(
          next.fallbacks,
          List("zhipu/GLM-5.3-Flash", "cmdcode/deepseek/deepseek-v4.1-flash", "deepseek/deepseek-flash"),
          "重跑引导不得把用户的 fallback 深度塌缩掉"
        )
        assertEquals(next.fallbacks.size, 3, "链深必须仍是 4（1 preferred + 3 fallbacks）")
    // displayName / name 等其它键不被写链抹掉
    val reinstated = io.circe.parser.parse(os.read(home / "agents" / rootName / "agent.json")).toOption.get
    assertEquals(reinstated.hcursor.downField("name").as[String].toOption, Some("Nebula"))

  test("write-back: 选定模型幂等 —— 同一 ref 重选，链不变；坏 ref ⇒ Left"):
    reset()
    OnboardingModelStep.writeSelectedChain("zhipu/glm-4.6").unsafeRunSync()
    val first = SchemePolicy.ownChainOf(rootName)
    OnboardingModelStep.writeSelectedChain("zhipu/glm-4.6").unsafeRunSync()
    assertEquals(SchemePolicy.ownChainOf(rootName), first, "同 ref 重复选择必须幂等")
    assert(OnboardingModelStep.writeSelectedChain("no-slash").unsafeRunSync().isLeft, "无法解析的 ref ⇒ Left")
    assert(OnboardingModelStep.writeSelectedChain("   ").unsafeRunSync().isLeft, "空白 ref ⇒ Left")
    assertEquals(SchemePolicy.ownChainOf(rootName), first, "失败的写链不得动盘上的链")

  // ---------------------------------------------------------------
  // 答案持久化（逐题 upsert，保留 state / probeOkAt）
  // ---------------------------------------------------------------

  test("answers: mergeAnswers 逐题 upsert，且 state / probeOkAt 不被抹掉"):
    reset()
    // 用**终局同一写入路径** setState 预置状态：本对象的验收必须走真实写路径
    // （legacy `writeState` 按设计绕过闸门，见 OnboardingService:136-139）。
    assertEquals(OnboardingService.setState(OnboardingService.OnboardingState.Pending).unsafeRunSync().isRight, true)
    OnboardingService.mergeAnswers(Map("name" -> Json.obj("kind" -> "free".asJson, "value" -> "星尘".asJson))).unsafeRunSync()
    OnboardingService.mergeAnswers(Map("call" -> Json.obj("kind" -> "skip".asJson, "value" -> "".asJson))).unsafeRunSync()
    val answers = OnboardingService.readAnswers().unsafeRunSync()
    assertEquals(answers.keySet, Set("name", "call"), "逐题追加，不整表覆盖")
    assertEquals(OnboardingService.readState().unsafeRunSync(), Some(OnboardingService.OnboardingState.Pending))

  test("answers: clearAnswers 清空答案但保留 state"):
    reset()
    // done 需先有一次成功探测（服务端硬闸）；`recordProbeOk` 是记录点本尊，
    // 与 probeLlm 成功腿同源，避免这里再造一个假的 LlmHandle。
    OnboardingService.recordProbeOk().unsafeRunSync()
    assertEquals(OnboardingService.setState(OnboardingService.OnboardingState.Done).unsafeRunSync().isRight, true)
    OnboardingService.mergeAnswers(Map("name" -> Json.obj("value" -> "x".asJson))).unsafeRunSync()
    OnboardingService.clearAnswers().unsafeRunSync()
    assertEquals(OnboardingService.readAnswers().unsafeRunSync(), Map.empty)
    assertEquals(OnboardingService.readState().unsafeRunSync(), Some(OnboardingService.OnboardingState.Done))

  // ---------------------------------------------------------------
  // 终局闸门：走 setState（真实写路径），且拒绝不吞掉用户答案
  // ---------------------------------------------------------------

  test("gate: 终局转移走 setState —— 无探测记录时 done 被拒，答案保留，补探测后可通过"):
    // 缺口复现（verifier fail 的根因链）：前端终局写 marker 用 setOnboardingState →
    // 服务端 `setState`。若验收用 legacy `writeState`（绕过闸门），这条链上的
    // 拒绝永远不被测到 —— 正是「全跳过到不了 done」能长期潜伏的原因。
    reset()
    val answers = Map(
      "name" -> Json.obj("kind" -> "free".asJson, "value" -> "星尘".asJson),
      "model" -> Json.obj("kind" -> "model".asJson, "value" -> "zhipu/glm-4.6".asJson),
    )
    OnboardingService.mergeAnswers(answers).unsafeRunSync()
    // ① 无探测 → 拒绝，且 marker 原地不动
    OnboardingService.setState(OnboardingService.OnboardingState.Done).unsafeRunSync() match
      case Left(reason) => assert(reason.contains("probe"), s"拒绝理由须点名 probe：$reason")
      case Right(_) => fail("没有探测记录时 done 必须被拒")
    // 拒绝 = marker 原地不动（答案写入已建文件、但 state 从未推进到 Done）
    assertEquals(
      OnboardingService.readStored().unsafeRunSync().map(_.state),
      Some(OnboardingService.OnboardingState.Pending)
    )
    // ② 拒绝不吞答案 —— 用户不必重答 11 题（重试收尾才有意义）
    assertEquals(OnboardingService.readAnswers().unsafeRunSync().keySet, Set("name", "model"))
    // ③ 补一次成功探测后，同一条转移可通过（用户的出口）
    OnboardingService.recordProbeOk().unsafeRunSync()
    assertEquals(OnboardingService.setState(OnboardingService.OnboardingState.Done).unsafeRunSync().isRight, true)
    assertEquals(OnboardingService.readState().unsafeRunSync(), Some(OnboardingService.OnboardingState.Done))

  // ---------------------------------------------------------------
  // 产物：Soul.md / User.md
  // ---------------------------------------------------------------

  private def ans(id: String, label: String, scope: String, kind: OnboardingArtifacts.Kind, value: String = "") =
    id -> OnboardingArtifacts.Answer(id = id, label = label, scope = scope, kind = kind, value = value)

  test("artifacts: 正常路径写出 Soul.md / User.md 于根层（同级）"):
    reset()
    val answers = Map(
      ans("name", "名字", "base", OnboardingArtifacts.Kind.Free, "星尘"),
      ans("call", "怎么称呼你", "user", OnboardingArtifacts.Kind.Free, "阿凯"),
      ans("role", "角色定位", "soul", OnboardingArtifacts.Kind.Choice, "靠谱的管家"),
      ans("style", "语言风格", "soul", OnboardingArtifacts.Kind.Choice, "温和细腻"),
      ans("pro", "主动程度", "soul", OnboardingArtifacts.Kind.Choice, "适度提醒"),
      ans("boundaries", "相处约定", "soul", OnboardingArtifacts.Kind.Multi, "可以跟我争论；记住随口提过的小事"),
      ans("temper1", "语气基线", "soul", OnboardingArtifacts.Kind.Choice, "直接坦率"),
      ans("temper2", "做事方式", "soul", OnboardingArtifacts.Kind.Choice, "随性灵活"),
      ans("identity", "日常身份", "user", OnboardingArtifacts.Kind.Choice, "创作者"),
      ans("focus", "当前关注", "user", OnboardingArtifacts.Kind.Free, "把 Nebflow 做成真正的 Personal Agent"),
    )
    val res = OnboardingArtifacts.apply(answers, Some("zhipu/glm-4.6")).unsafeRunSync()
    assertEquals(res.soulPath, (home / "Soul.md").toString)
    assertEquals(res.userPath, (home / "User.md").toString)
    val soul = os.read(home / "Soul.md")
    assert(soul.contains("- 名字：星尘"))
    assert(soul.contains("- 角色定位：靠谱的管家"))
    assert(soul.contains("## 你说过的原话"))
    assert(soul.contains("星尘"), "自由输入原话进原话段")
    val user = os.read(home / "User.md")
    assert(user.contains("- 怎么称呼：阿凯"))
    assert(user.contains("- 日常身份：创作者"))
    assert(user.contains("把 Nebflow 做成真正的 Personal Agent"))
    assert(!res.allSkipped)

  test("artifacts: 全跳过路径留下结构完整骨架（含 ## 备注），非空壳"):
    reset()
    val skipped = List("name", "call", "model", "role", "style", "pro", "boundaries", "temper1", "temper2", "identity", "focus")
      .map(id => ans(id, id, if id == "call" || id == "identity" || id == "focus" then "user" else if id == "model" then "base" else "soul", OnboardingArtifacts.Kind.Skip))
      .toMap
    val res = OnboardingArtifacts.apply(skipped, None).unsafeRunSync()
    assert(res.allSkipped, "全跳过必须被识别")
    val soul = os.read(home / "Soul.md")
    for sec <- List("## 自我认知", "## 相处之道", "## 你说过的原话", "## 成长约定", "## 备注") do
      assert(soul.contains(sec), s"Soul.md 缺段 $sec")
    assert(soul.contains("未设置（这一题你跳过了）"), "跳过项逐字段占位")
    val user = os.read(home / "User.md")
    for sec <- List("## 称呼与身份", "## 当前关注", "## 你说过的原话", "## 记录规则") do
      assert(user.contains(sec), s"User.md 缺段 $sec")
    assert(res.soulBytes > 0L && res.userBytes > 0L)

  test("artifacts: 起名写 displayName，且机制键（agent 目录名）不变"):
    reset()
    val answers = Map(ans("name", "名字", "base", OnboardingArtifacts.Kind.Free, "小满"))
    val res = OnboardingArtifacts.apply(answers, None).unsafeRunSync()
    assertEquals(res.agentName, Some("小满"))
    val agentJson = home / "agents" / rootName / "agent.json"
    assert(os.exists(agentJson), "displayName 落既有 agent.json")
    val dn = io.circe.parser.parse(os.read(agentJson)).toOption.flatMap(_.hcursor.downField("displayName").as[String].toOption)
    assertEquals(dn, Some("小满"))
    assertEquals(OnboardingArtifacts.currentDisplayName(), "小满")
    assertEquals(rootName, "Nebula", "🔴 机制键恒为 Nebula（不以参数化之名改判据键）")

  test("artifacts: displayName 缺省回落机制名（无名字用户零回归）"):
    reset()
    assertEquals(OnboardingArtifacts.currentDisplayName(), rootName)

  test("artifacts: 重跑幂等（覆盖式写入，不追加旧内容）"):
    reset()
    val a1 = Map(ans("name", "名字", "base", OnboardingArtifacts.Kind.Free, "甲"))
    OnboardingArtifacts.apply(a1, None).unsafeRunSync()
    val a2 = Map(ans("name", "名字", "base", OnboardingArtifacts.Kind.Free, "乙"))
    OnboardingArtifacts.apply(a2, None).unsafeRunSync()
    val soul = os.read(home / "Soul.md")
    assert(soul.contains("乙"))
    assert(!soul.contains("甲"), "覆盖式写入，不残留上一跑")

end OnboardingV2Spec
