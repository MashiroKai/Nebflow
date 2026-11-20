package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import nebflow.db.{Db, DbConfig, DbHandle, ReadResult}
import nebflow.db.format.GuardDocs
import nebflow.shared.*

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * db 接线二阶 B2（D-10 + M37 教训）——HealthMonitor 窗口态落盘：
 *  - 写穿：softAvoid / markDown / markUp 转换点 ⇒ guard/provider-avoid.json 在场；
 *  - 重启不归零：句柄失锁（kill -9 模拟）→ 重开 → 新 monitor 重水合 ⇒ 避坑窗仍在、
 *    熔断（Down）面仍在（cap M37「重启清零重新试探坏 provider」反面）；
 *  - markUp 后重启 = 不再 Down（up 态不落档=缺席）；
 *  - 坏档保守向：Corrupt ⇒ 隔离 + 有界全避（QuotaAvoidWindowMs，自愈）。
 */
class HealthMonitorGuardPersistenceSpec extends CatsEffectSuite:

  /**
   * 套件级换根（`QuotaChainSwitchSpec` 既有惯例）。
   *
   * B2 坏档保守向用例的前置断言是「registry 派生出非空候选链」。链解析走
   * `SchemePolicy.resolveFromInputs`，其 `mainModel` 腿 = `nebulaChain()` =
   * **直读磁盘** `PathUtil.dataRoot/agents/nebula/agent.json`
   * （`SchemePolicy.scala:97-103`），优先级高于本 spec 的 `llm.fallback`。
   * 开发机真实根里该文件带 `"preferred":"zhipu/glm-5.3-flash"`（本 spec 未配的
   * provider）⇒ 候选链解析为空 ⇒ 前置断言红（2026-11-21 实测）。
   * 钉到临时根（无 agent.json）⇒ 走 `twoProviderRegistry` 自己的 fallback 锚。
   *
   * 注意：本套件各用例自己还会 `PathUtil.setDataRoot` 到 per-test 临时根再还原，
   * 两者兼容（per-test 的在 finally 里还原到本 beforeAll 钉的根）。
   */
  private var savedDataRoot: Option[os.Path] = None

  override def beforeAll(): Unit =
    savedDataRoot = Some(PathUtil.dataRoot)
    PathUtil.setDataRoot(os.Path(java.nio.file.Files.createTempDirectory("hmguard-spec").toString))

  override def afterAll(): Unit = savedDataRoot.foreach(PathUtil.setDataRoot)

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def openDb(root: Path): DbHandle = Db.open(DbConfig(root)).unsafeRunSync()

  /** 两 provider 测试 registry（保守向测试用；null backend——探测腿不在本 spec 面）。 */
  private def twoProviderRegistry: ProviderRegistry =
    // config-single-source（作者裁定 ①③ 2026-11-05）后全局链锚 = llm.fallback 节
    // （FallbackChain），缺节且无 Nebula 侧车 ⇒ fail-closed 空链（provider inventory
    // 不再编造候选）——夹具补 preferred 锚供保守向键源派生。
    val cfg = NebflowServiceConfig(llm =
      ServiceLlmConfig(
        providers = Map(
          "prov-a" -> ProviderConfig(
            "https://a.example",
            "k",
            LlmProtocol.OpenAI,
            models = List(ModelConfig(id = "model-a"))
          ),
          "prov-b" -> ProviderConfig(
            "https://b.example",
            "k",
            LlmProtocol.OpenAI,
            models = List(ModelConfig(id = "model-b"))
          )
        ),
        fallback = Some(AgentModelConfig(preferred = Some("prov-a/model-a"), fallbacks = Nil))
      )
    )
    new ProviderRegistry(Ref.unsafe[IO, NebflowServiceConfig](cfg), null)

  end twoProviderRegistry

  test("B2 写穿 + 避坑窗重启不归零（kill -9 模拟）"):
    val root = Files.createTempDirectory("hm-guard-persist")
    val h1 = openDb(root)
    val m1 = ProviderHealthMonitor(null, db = Some(h1))
    val t0 = System.currentTimeMillis()
    m1.softAvoid("prov-a", "model-a", 45_000L).unsafeRunSync()
    // 写穿：档在场且值合理。
    h1.readAvoidGuard(Db.AvoidGuardSource).unsafeRunSync() match
      case ReadResult.Present(doc) =>
        assertEquals(doc.avoidUntil.keySet, Set("prov-a/model-a"), "写穿：avoid 键落档")
        assert(doc.avoidUntil("prov-a/model-a") >= t0 + 45_000L - 5_000L)
      case other => fail(s"unexpected $other")
    // 「重启」= 放锁（guard 档五步写已耐久；真 kill -9 面由 chaos 批覆盖）。
    h1.close()
    val h2 = openDb(root)
    val m2 = ProviderHealthMonitor(null, db = Some(h2))
    m2.getAvoidUntil.unsafeRunSync() match
      case avoids =>
        assertEquals(avoids.keySet, Set("prov-a/model-a"), "重启后避坑窗不归零（M37）")
        assert(avoids("prov-a/model-a") >= t0 + 45_000L - 5_000L, "窗口值跨重启保真")
    h2.close()

  test("B2 熔断（Down）面写穿 + 重启不归零；markUp 后重启 = 非 Down"):
    val root = Files.createTempDirectory("hm-guard-down")
    val h1 = openDb(root)
    val m1 = ProviderHealthMonitor(null, db = Some(h1))
    m1.markDown("prov-a", "model-a", "boom").unsafeRunSync()
    h1.readAvoidGuard(Db.AvoidGuardSource).unsafeRunSync() match
      case ReadResult.Present(doc) =>
        assertEquals(doc.down.keySet, Set("prov-a/model-a"), "写穿：down 键落档")
      case other => fail(s"unexpected $other")
    h1.close()
    // 重启 1：Down 仍在。
    val h2 = openDb(root)
    val m2 = ProviderHealthMonitor(null, db = Some(h2))
    m2.getStates.unsafeRunSync().get("prov-a/model-a") match
      case Some(HealthState.Down(reason, _)) =>
        assert(reason.nonEmpty, "重水合 Down 带诊断 reason")
      case other => fail(s"重启后 Down 不归零，got $other")
    // 恢复 + 再重启：Up 不落档 ⇒ 缺席（非 Down）。
    m2.markUp("prov-a", "model-a").unsafeRunSync()
    h2.close()
    val h3 = openDb(root)
    val m3 = ProviderHealthMonitor(null, db = Some(h3))
    m3.getStates.unsafeRunSync().get("prov-a/model-a") match
      case None | Some(HealthState.Up) => () // 正确：非 Down
      case other => fail(s"markUp 后重启应为非 Down，got $other")
    h3.close()

  test("B2 坏档保守向：Corrupt ⇒ 隔离 + 有界全避（自愈窗，非 MaxValue）"):
    val root = Files.createTempDirectory("hm-guard-corrupt")
    val h1 = openDb(root)
    // 先落一份合法档再写坏（Corrupt ≠ Absent 的注入前提）。
    ProviderHealthMonitor(null, db = Some(h1)).softAvoid("x", "y", 1_000L).unsafeRunSync()
    h1.close()
    val avoidFile = root.resolve("db").resolve("guard").resolve("provider-avoid.json")
    Files.writeString(avoidFile, "{ not json")
    val h2 = openDb(root)
    val registry = twoProviderRegistry
    val candidates = registry.getCandidates().unsafeRunSync()
    assert(candidates.nonEmpty, "前置：registry 派生出候选链（保守向键源）")
    val m2 = ProviderHealthMonitor(registry, db = Some(h2))
    val t0 = System.currentTimeMillis()
    m2.getAvoidUntil.unsafeRunSync() match
      case avoids if avoids.nonEmpty =>
        // 有界保守：每个候选键都避坑，窗口 = QuotaAvoidWindowMs（自愈），非 MaxValue。
        avoids.foreach { (k, until) =>
          assert(until > t0, s"$k 保守窗在未来")
          assert(until <= t0 + Defaults.QuotaAvoidWindowMs + 5_000L, s"$k 保守窗有界（非 MaxValue 全灭）")
        }
      case other => fail(s"保守向应产生非空避坑窗，got $other")
    // 隔离档在场（字节保全）。
    val guardDir = root.resolve("db").resolve("guard")
    val quarantined = Option(guardDir.toFile.listFiles).toList.flatten
      .map(_.getName)
      .filter(_.startsWith("provider-avoid.json.corrupt-"))
    assert(quarantined.nonEmpty, "坏档被隔离（.corrupt-<ts> 在场）")
    h2.close()

  test("B2 无 db 注入 = 现状行为（纯内存，零档面）"):
    val root = Files.createTempDirectory("hm-guard-nodb")
    // 隔离 dataRoot：guardDb = db.orElse(DbHost.peek(dataRoot))——本机真实数据根里
    // 若有 guard 档（应用在跑/别的套件写过），peek 会把它当「已接线」，B2 的「零档面」
    // 前提就被环境污染。B2 断的是「db=None 的纯内存路径」，必须自带干净根。
    val savedRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(os.Path(root, os.pwd))
    try
      val m = ProviderHealthMonitor(null)
      m.softAvoid("p", "m", 45_000L).unsafeRunSync()
      m.markDown("p", "m", "r").unsafeRunSync()
      assert(!Files.exists(root.resolve("db")), "未接线 ⇒ 不建 db 树")
      assertEquals(m.getAvoidUntil.unsafeRunSync().keySet, Set("p/m"))
      assert(m.getStates.unsafeRunSync().contains("p/m"))
    finally PathUtil.setDataRoot(savedRoot)
end HealthMonitorGuardPersistenceSpec
