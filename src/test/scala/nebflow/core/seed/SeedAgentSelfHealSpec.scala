package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * agents 面缺失自愈 spec（2026-09-13 缺失自愈批 / 方案 C，作者令「改成缺失自愈」）。
 *
 * 与 plugins 面 `SeedPluginReconcileSpec` 同构（同一类缺口、同一处修复形态）：
 *  ① 既有 home + 默认集 agent 缺失 ⇒ 从种子整目录自愈补装（逐名遍历「manifest 在册
 *     ∧ 种子带 agent.json」的全集；kernel 为 prompt-only 项、无种子 agent.json、
 *     不在自愈面）
 *  ② 幂等：第二次 ensure 对该目录零动作
 *  ③ 自愈面 = manifest 默认集（不向种子树全集扩张；非默认集无 agent 面）
 *  ④ 已存在目录零覆盖：用户改过的运行时不进自愈分支，内容逐字节保留
 *  ⑤ 启动面告警核验（root 四条之末）：真缺件仍占响亮 WARN 面（**正向控制**，同时
 *     证明日志捕获本身有载力）；已退役的记忆消费链校验面**零残留告警**（**反向控制**
 *     ——若那条旧校验复活，它在该 home 上必然 WARN，本断言必红）
 *
 * E5 批 2026-10-01（悬空名族收口）：本 spec 原 ①/⑤ 的举证对象是一个 2026-09-25 已退役的
 * 记忆整理 agent（工具与队列删除、种子资源 ABSENT、manifest 清单零该名）⇒ 那些断言在
 * 种子面上**结构性为假**（spec 即红）。本批把 ①–④ 的断言对象改为**可证伪的真集合**（逐名
 * 遍历 + 「kernel 不入自愈面」的反向钉），并把 ⑤ 从「退役机制的告警」改为「退役机制零
 * 告警」的反向控制。裸字面零复制：集合从 classpath 的 `seed/manifest.json` 现读。
 *
 * classpath 资源（src/main/resources/seed/）在 sbt test classpath 上，种子读取走真实链路。
 */
class SeedAgentSelfHealSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-seed-agent-heal"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def ensure(): Unit = SeedService.ensureSeeded().unsafeRunSync()
  private def rm(p: os.Path): Unit = if os.exists(p) then os.remove.all(p)

  /** 种子 agent 目录（test classpath 上的实件）。 */
  private def seedDirOf(name: String): os.Path =
    val url = getClass.getClassLoader.getResource(s"seed/agents/$name/agent.json")
    os.Path(java.nio.file.Paths.get(url.toURI)) / os.up

  /**
   * 自愈面全集 = **manifest 在册 ∧ 种子树带 `agent.json`** 的 agent 名。两个条件都从
   * classpath 现读（零裸清单）：kernel 在 manifest 在册但只有 `system.md`（prompt-only
   * 项）⇒ 不在自愈面——`reconcileAgent`/`installAgentFromSeed` 的锚件就是 `agent.json`。
   */
  private def healableAgentNames: List[String] =
    manifestAgentNames.filter(name =>
      getClass.getClassLoader.getResource(s"seed/agents/$name/agent.json") != null
    ).sorted

  // ── 启动面告警捕获（⑤：告警是否真的发出——返回值断言测不到 built-but-discarded）──

  private def attachSeedLogger()
    : (ch.qos.logback.classic.Logger, ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]) =
    val lb = org.slf4j.LoggerFactory
      .getLogger("nebflow.core.seed")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lb.addAppender(appender)
    (lb, appender)

  private def capturedWarns(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
  ): List[String] =
    import scala.jdk.CollectionConverters.*
    appender.list.asScala.toList
      .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
      .map(_.getFormattedMessage)

  private def treeAsText(d: os.Path): Map[String, String] =
    os.walk(d).filter(os.isFile).map(p => p.relativeTo(d).toString -> new String(os.read.bytes(p), UTF_8)).toMap

  private def manifestAgentNames: List[String] =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try
      io.circe.parser
        .parse(new String(in.readAllBytes(), UTF_8))
        .toOption
        .get
        .hcursor
        .downField("items")
        .as[List[String]]
        .toOption
        .get
        .collect { case id if id.startsWith("agents:") => id.stripPrefix("agents:") }
    finally in.close()

  private def manifestVersion: String =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try
      io.circe.parser
        .parse(new String(in.readAllBytes(), UTF_8))
        .toOption
        .get
        .hcursor
        .downField("seedVersion")
        .as[String]
        .toOption
        .get
    finally in.close()

  /** 既有 home 形态：有项目（守卫命中，不完整播种）+ marker 同版本（marker 分支 no-op）。 */
  private def existingHome(): Unit =
    rm(home / "agents"); rm(home / "projects"); rm(home / "plugins"); rm(home / ".seed-state.json")
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")
    os.write.over(home / ".seed-state.json", s"""{"version":"$manifestVersion","seededAt":1,"items":[]}""")

  // ── ① 缺失 ⇒ 自愈补装 ├────────────────────────────────────
  test("existing home + 默认集 agent 缺失 ⇒ 从种子逐名自愈补装（全集断言，kernel 为 prompt-only 不入自愈面）"):
    existingHome()
    // 前置：种子不在 home、默认集全缺席（= 既有 home 的实际形态：`agents/` 未建）
    for name <- healableAgentNames do
      assert(!os.exists(home / "agents" / name / "agent.json"), s"前置：$name 缺席")

    ensure()

    // 正向：自愈面全集（manifest 在册 ∧ 种子带 agent.json）逐名补装，内容 == 种子树（逐字节）
    for name <- healableAgentNames do
      val dir = home / "agents" / name
      assert(os.exists(dir / "agent.json"), s"缺失 ⇒ 自愈补装 $name/agent.json")
      assert(os.exists(dir / "system.md"), s"缺失 ⇒ 自愈补装 $name/system.md")
      assertEquals(treeAsText(dir), treeAsText(seedDirOf(name)), s"$name 自愈内容 == 种子树（逐字节）")
    // 反向：prompt-only 项（manifest 在册但种子无 agent.json）**不入自愈面**——自愈锚件
    // 就是 agent.json；kernel 的 system.md 由播种腿（`seedAgent`）落，不由自愈腿落。
    val promptOnly = manifestAgentNames.filterNot(healableAgentNames.contains)
    for name <- promptOnly do
      assert(
        !os.exists(home / "agents" / name / "agent.json"),
        s"prompt-only 项 '$name' 不得有 agent.json（自愈面锚件缺席 ⇒ 不入自愈）"
      )
    assertEquals(promptOnly, List("kernel"), "现读 prompt-only 项恰为 kernel（manifest 在册、种子无 agent.json）")
    // 守卫语义不变：不完整播种（既有 home 走 marker-only 分支、不重播默认集）。项目面
    // （kernelgen 2026-09-26 全圆回归）：manifest 现读**零** `project:` 条目 ⇒
    // `reconcileProjects` **休眠** ⇒ 既有 home 缺 general 项目**不补建**——与
    // `SeedServiceSpec` 守卫组（`:248` 负向断言 + 「full circle」注）同一现读口径。
    // 本条原为正向（2026-09-17 裁定②残留），该正向在 kernelgen 落地后结构性为假；
    // 本批按现读翻回**负向**（真断言：把 `project:general` 加回 manifest 即变红）。
    assert(
      !os.exists(home / "projects" / "general"),
      "既有 home 不补建 general 项目（reconcileProjects 休眠：manifest 零 project: 条目）"
    )

  // ── ② 幂等 ├────────────────────────────────────────────────
  test("自愈幂等：第二次 ensure 对该目录零动作（内容与 mtime 双证）"):
    existingHome()
    ensure()
    val dir = home / "agents" / "general"
    val again = treeAsText(dir)
    val mtimes = os.walk(dir).filter(os.isFile).map(p => p -> os.mtime(p)).toMap
    ensure()
    assertEquals(treeAsText(dir), again, "第二次零动作（digest 已与种子一致）")
    mtimes.foreach { (p, before) =>
      assertEquals(os.mtime(p), before, s"第二次零写入（mtime 未变）：${p.relativeTo(home)}")
    }
    // ── E5 批（2026-10-01）落的逐名 digest 腿：main 既有断言，本位逐字保留（零删除 / 零弱化）。
    // 与上方取回腿互补：上方钉 `general` 的内容 + mtime 双证，本腿钉自愈面全集的第二次零动作。
    for name <- healableAgentNames do
      val dir = home / "agents" / name
      val again = treeAsText(dir)
      ensure()
      assertEquals(treeAsText(dir), again, s"$name 第二次零动作（digest 已与种子一致）")

  // ── ③ 已存在目录零覆盖 ├────────────────────────────────────
  test("自愈不改已存在目录（用户版本保留）"):
    existingHome()
    val name = healableAgentNames.head
    val dir = home / "agents" / name
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", s"""{"name":"$name","userEdited":true}""")
    os.write.over(dir / "system.md", "user edited system prompt\n")
    val before = treeAsText(dir)

    ensure()

    assertEquals(treeAsText(dir), before, "已存在目录逐字节未动（零覆盖）")
  // 注：已存在目录走 diff/digest 仲裁分支（三条硬条件），其行为由既有 reconcile 机制覆盖；
  // 本批只新增「缺失 ⇒ 补装」这一条分支，改动面严格限定于此。

  // ── ④ 启动面告警核验（root 四条之末：响亮 WARN 面 + 退役面零残留）├──────────
  test("启动面告警：真缺件仍占响亮 WARN 面；已退役的记忆消费链校验面零残留告警"):
    // 覆盖范围的自证（不是「没跑过」）：本 spec 不覆盖记忆消费链校验，判据是
    // ① 源码面零残留（实现在同批删除，编译期即约束）+ ② 下面两条运行时控制。
    existingHome()
    val (lb, appender) = attachSeedLogger()
    try
      // ── 正向控制：真缺件 = 响亮 WARN（证明日志捕获本身有载力，不是「恒空」）──
      // `_pre` 不在 manifest 也不在种子树 ⇒ `reconcileAgent` 必走「无种子资源」分支。
      SeedService.reconcileAgentForTest(home, "_pre_e5_probe_absent_")
      assert(
        capturedWarns(appender).exists(m => m.contains("_pre_e5_probe_absent_") && m.contains("no seed resources")),
        s"真缺件必须响亮 WARN（证明日志捕获有载力），实得 WARN=${capturedWarns(appender)}"
      )
      // ── 反向控制：ensure 后不得落任何**面外** agent 目录 ──
      appender.list.clear()
      ensure()
      // 强形态（严格集合相等，零裸清单）：`agents/` 下的目录集必须**恰好** = 自愈面全集。
      // 任何「面外」agent 目录出现（含本批收口的已退役记忆队列消费者——其种子资源 ABSENT、
      // manifest `agents:` 清单零该名）都会使本断言红；任何面内缺件同样红。故本断言既据
      // 「落盘面零扩张」，也据「自愈面全集就位」。
      val landedDirs = os.list(home / "agents").filter(os.isDir).map(_.last).toSet
      assertEquals(
        landedDirs,
        healableAgentNames.toSet,
        "home/agents 目录集必须恰等于自愈面全集（零面外 agent 目录 ⇒ 已退役名不得复活）"
      )
      val warns = capturedWarns(appender)
      // 若旧消费链校验复活，它在该 home 上**必然** WARN（判据对象缺席）⇒ 本断言必红。
      assert(
        !warns.exists(m => m.contains("MEMORY CONSUMPTION CHAIN")),
        s"已退役的消费链校验不得再产出告警（否则说明它复活），实得 WARN=$warns"
      )
      // 负向的载力护栏：该 home 上自愈面全集就位 ⇒ 不得有自愈面缺件 WARN（否则反向控制
      // 会因无关告警而假绿）。
      for name <- healableAgentNames do
        assert(
          !warns.exists(m => m.contains(s"'$name'") && m.contains("no seed resources")),
          s"$name 的种子在册 ⇒ 不得有「无种子资源」WARN：$warns"
        )
    finally
      lb.detachAppender(appender)
    end try

end SeedAgentSelfHealSpec
