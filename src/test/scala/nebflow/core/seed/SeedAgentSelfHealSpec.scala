package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * agents 面退役 spec（builtin-def 批 2026-10-03 作者令①「四个 agent 全部代码硬编码，
 * 不扫盘，唯一标准源就是代码」）。
 *
 * 史实：本 spec 曾钉「agents 面缺失自愈」（2026-09-13 缺失自愈批 / 方案 C）——manifest
 * 在册 ∧ 种子带 agent.json 的 agent 逐名自愈补装、幂等、零覆盖、响亮 WARN。该机制随
 * agent 播种面在 builtin-def 批**整体退役**（def 面单点 = `nebflow.core.entity.BuiltinAgents`；
 * seed/agents 资源树删除、manifest `agents:` 条目清零、`reconcileAgents`/
 * `reconcileAgent`/`installAgentFromSeed`/`readAgentSeedPrompt` 全删）。
 *
 * 现行契约（本 spec 的三条钉，全部从 classpath 现读、零裸清单）：
 *  ① manifest 零 `agents:` 条目 ∧ classpath 零 seed/agents 资源——把任何一条写回即红
 *    （残留条目落入 seedItem 的通用 unknown-item WARN + 跳过，无专用分支——禁留永不
 *     触发的告警分支）；
 *  ② ensure() 对 agents/ 树零写入零删除（手工放置的文件逐字节保留）；
 *  ③ agents 面的消费证据单点：`BuiltinAgents.entry` 对四名各给代码定义（缺件态结构性
 *     不存在——这就是自愈机制可以被退役的根据）。
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

  private def manifestItems: List[String] =
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

  // ── ① 种子面零残留 ├────────────────────────────────────────
  test("manifest 零 agents: 条目 ∧ classpath 零 seed/agents 资源（builtin-def 2026-10-03 退役钉）"):
    val agentItems = manifestItems.filter(_.startsWith("agents:"))
    assertEquals(agentItems, Nil,
      "manifest 必须零 agents: 条目（四件 agent 代码定义——把任何 agents:* 条目写回 ⇒ 本条红，重启播种是产品决策）")
    for name <- BuiltinAgentProbe.names do
      assert(getClass.getClassLoader.getResource(s"seed/agents/$name/system.md") == null,
        s"seed/agents/$name 必须不在 classpath（种子镜像退役——代码即唯一权威；回滚 = revert 本批）")
      assert(getClass.getClassLoader.getResource(s"seed/agents/$name/agent.json") == null,
        s"seed/agents/$name/agent.json 必须不在 classpath")

  // ── ② ensure() 对 agents/ 零写入零删除 ├─────────────────────
  test("ensure() 绝不创建、绝不改写、绝不删除 agents/ 树（boot 对 agent 磁盘面零触碰）"):
    existingHome()
    // 手工放一套「史实形态」的磁盘定义（收敛名）——builtin-def 批起这是死信：
    // 读取侧跳过、种子侧零触碰。放置内容故意与代码定义不同，任何「种子回写 /
    // 自愈补装 / 死信复活」都会改变这里的内容或目录集。
    val stray = home / "agents" / "general"
    os.makeDir.all(stray)
    os.write.over(stray / "agent.json", """{"name":"general","userOwned":true}""")
    os.write.over(stray / "system.md", "user-owned stray disk def (dead letter)\n")
    val beforeTree =
      if os.exists(home / "agents") then
        os.walk(home / "agents").filter(os.isFile).map(p => p.relativeTo(home / "agents").toString -> os.mtime(p)).toMap
      else Map.empty[String, Long]

    ensure()
    ensure() // 连续两次 boot（幂等面一并覆盖）

    assert(os.exists(stray / "agent.json") && os.read(stray / "agent.json").contains("userOwned"),
      "手工 agent 文件逐字节保留（死信 ≠ 可删——零删除纪律）")
    val afterTree =
      os.walk(home / "agents").filter(os.isFile).map(p => p.relativeTo(home / "agents").toString -> os.mtime(p)).toMap
    assertEquals(afterTree, beforeTree, "boot 前后 agents/ 树零变化（零写入零删除，mtime 双证）")

  // ── ③ 消费证据单点：BuiltinAgents 恒在（缺件态结构性不存在）├──
  test("BuiltinAgents 对四名各给代码定义（自愈机制可退役的根据：不存在缺件态）"):
    for name <- BuiltinAgentProbe.names do
      val entry = nebflow.core.entity.BuiltinAgents.entry(name)
      assert(entry.isDefined, s"BuiltinAgents 必须给 '$name' 代码定义")
      val e = entry.get
      assert(e.systemPrompt.trim.nonEmpty, s"'$name' 的代码级 system prompt 非空")
      assertEquals(e.name, name)
      assert(e.tools.isEmpty, s"'$name' 的 tools 字段是恒空非权威面（fixedToolsFor 单点）")

  // ── 启动面告警捕获（④ 的墓碑/零残留核验共用）──────────────

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

end SeedAgentSelfHealSpec

/** 四名清单的间接引用（避免 spec 直抄裸字面；单点 = BuiltinAgents.Names）。 */
private object BuiltinAgentProbe:
  val names: List[String] = nebflow.core.entity.BuiltinAgents.Names.toList.sorted
