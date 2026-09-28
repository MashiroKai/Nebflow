package nebflow.core

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** P1 修复批（20260910 LoopNode 实测批逮到）：日志/数据落盘根必须跟随实例
  * 数据根（PathUtil.dataRoot：CLI --home 旗标 / NEBFLOW_HOME env / 默认
  * ~/.nebflow），禁止硬编码 user.home。
  *
  * 缺陷原貌（20260910_loopnode新形式实测报告.md P1 节）：LlmLogWriter.logDir
  * 硬编码 `Paths.get(user.home, ".nebflow", "logs", "router")`——`--home
  * /tmp/qa-loop-e2e` 的隔离实例把 LLM 请求日志（summary/full/sse + objects/
  * 正文）全部写进真实 ~/.nebflow/logs/router/（155+ 条 mock 条目实测污染）。
  * 本批同类修复面：ToolsLogWriter（logs/tools）、FileHistory（history 默认根）。
  *
  * 三条契约（每条独立可断言）：
  *   1. 隔离跟随——setDataRoot(临时目录) 后三类落盘根 = <dataRoot>/logs/router、
  *      <dataRoot>/logs/tools、<dataRoot>/history（物理写入断言）；
  *   2. 生产语义不变——dataRoot = os.home/.nebflow（默认解析结果）时路径仍为
  *      ~/.nebflow/logs/{router,tools}（零行为回归钉子；纯路径断言不写入）；
  *   3. 不越界——隔离 home 写入后，真实 ~/.nebflow 侧零本测试条目/零触碰。
  *
  * 隔离配方仿仓内先例（MemorySnapshotSpec/ConfigServiceSpec 等 30+ 套件的
  * prevRoot 存取模式）：Test / parallelExecution := false 保证全局重定向安全，
  * afterAll 恢复原 dataRoot + 清理临时目录。
  */
class LogWriterHomeIsolationSpec extends CatsEffectSuite:

  private var prevRoot: Option[os.Path] = None
  private var tmpHome: java.nio.file.Path = null
  // 全量跑中先行的 suite 会翻转这些全局开关（多处 setEnabled(false) 污染向量；
  // 实测：单独跑 6/6 绿、全量跑被前置 suite 残留态打掉）——本 spec 进场自钉
  // 前置条件，出场恢复进场时快照，双向零泄漏。
  private var prevLlmEnabled: Boolean = true
  private var prevToolsEnabled: Boolean = true

  private def isolatedRoot: os.Path = os.Path(tmpHome.toString, os.pwd)

  override def beforeAll(): Unit =
    prevRoot = Some(PathUtil.dataRoot)
    prevLlmEnabled = LlmLogWriter.isEnabled
    prevToolsEnabled = ToolsLogWriter.isEnabled
    LlmLogWriter.resetLogDirForTest()
    ToolsLogWriter.resetDirForTest()
    ToolsLogWriter.resetClockForTest()
    LlmLogWriter.setEnabled(true)
    ToolsLogWriter.setEnabled(true)
    tmpHome = Files.createTempDirectory("nb-loghome-iso")
    PathUtil.setDataRoot(isolatedRoot)

  override def afterAll(): Unit =
    ToolsLogWriter.flushSync()
    prevRoot.foreach(PathUtil.setDataRoot)
    LlmLogWriter.setEnabled(prevLlmEnabled)
    ToolsLogWriter.setEnabled(prevToolsEnabled)
    if tmpHome != null && Files.exists(tmpHome) then
      Files
        .walk(tmpHome)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)

  private def today: String = Instant.now().toString.take(10)

  private def realHome: String = System.getProperty("user.home")

  // ── 契约 1：隔离 dataRoot 跟随（物理落盘）────────────────────────────

  test("router 日志跟随隔离 dataRoot（intake 行落盘于临时 home）") {
    PathUtil.setDataRoot(isolatedRoot)
    // 纯路径断言先行（不受 enabled/clock 全局态影响）：logDir ≡ dataRoot/logs/router。
    assertEquals(
      LlmLogWriter.logDirForTest,
      Paths.get(tmpHome.toString, "logs", "router"),
      "router logDir must derive from the isolated dataRoot"
    )
    val id = s"homeiso-router-${UUID.randomUUID()}"
    LlmLogWriter.logIntake(id, "homeiso-session", "spec-agent").unsafeRunSync()
    val sseFile = Paths.get(tmpHome.toString, "logs", "router", s"${today}_sse.jsonl")
    assert(Files.exists(sseFile), s"intake line must land under the isolated home: $sseFile")
    assert(Files.readString(sseFile).contains(id), "intake request_id must be inside the isolated home")
  }

  test("tools 日志跟随隔离 dataRoot（flush 后落盘于临时 home）") {
    PathUtil.setDataRoot(isolatedRoot)
    assertEquals(
      ToolsLogWriter.logDirForTest,
      Paths.get(tmpHome.toString, "logs", "tools"),
      "tools logDir must derive from the isolated dataRoot"
    )
    val marker = s"homeiso-tools-${UUID.randomUUID()}"
    ToolsLogWriter
      .log(
        tool = "Read",
        agent = Some("spec-agent"),
        sessionId = Some("homeiso-session"),
        kind = Some("exec"),
        isError = false,
        elapsedMs = 1,
        errorText = "",
        inputSummary = marker,
        resultChars = 3,
        requestId = None
      )
      .unsafeRunSync()
    ToolsLogWriter.flushSync()
    val toolsFile = Paths.get(tmpHome.toString, "logs", "tools", s"${today}.jsonl")
    assert(Files.exists(toolsFile), s"tools line must land under the isolated home: $toolsFile")
    assert(Files.readString(toolsFile).contains(marker), "tools marker must be inside the isolated home")
  }

  test("FileHistory 默认根跟随隔离 dataRoot（create() 落在临时 home）") {
    PathUtil.setDataRoot(isolatedRoot)
    val fh = nebflow.core.tools.FileHistory.create().unsafeRunSync()
    // historyRoot 是 private[tools]——不触字段，直接断言物理目录位置。
    assert(
      Files.exists(Paths.get(tmpHome.toString, "history")),
      "FileHistory.create() default root must materialize under the isolated dataRoot"
    )
    // 行为级旁证：snapshot 落进隔离 home 的 history 树。
    val probe = Files.createTempFile("homeiso-probe", ".txt")
    try
      Files.writeString(probe, "home-iso-content")
      fh.snapshot(probe).unsafeRunSync()
      val snapshots = Files
        .walk(Paths.get(tmpHome.toString, "history"))
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && !p.getFileName.toString.endsWith(".identity"))
        .toList
      assert(snapshots.nonEmpty, "snapshot content must exist under the isolated history root")
      assertEquals(Files.readString(snapshots.head), "home-iso-content")
    finally Files.deleteIfExists(probe)
  }

  // ── 契约 2：生产语义零回归（纯路径断言，不写入）──────────────────────

  test("生产默认路径不变：dataRoot=os.home/.nebflow → ~/.nebflow/logs/{router,tools}") {
    // os.home ≡ System.getProperty("user.home")（os-lib 同源），故 dataRoot
    // 钉到生产默认值后，logDir 必须逐字节回到旧硬编码路径。
    PathUtil.setDataRoot(os.home / ".nebflow")
    assertEquals(
      LlmLogWriter.logDirForTest,
      Paths.get(realHome, ".nebflow", "logs", "router"),
      "production default router log path must remain ~/.nebflow/logs/router (zero behavior change)"
    )
    assertEquals(
      ToolsLogWriter.logDirForTest,
      Paths.get(realHome, ".nebflow", "logs", "tools"),
      "production default tools log path must remain ~/.nebflow/logs/tools (zero behavior change)"
    )
  }

  // ── 契约 3：不越界（真实 ~/.nebflow 零本测试痕迹）────────────────────

  test("隔离写入不越界：真实 ~/.nebflow/logs/router 零本测试条目") {
    PathUtil.setDataRoot(isolatedRoot)
    val id = s"homeiso-LEAKCHECK-${UUID.randomUUID()}"
    LlmLogWriter.logIntake(id, "homeiso-leakcheck", "spec-agent").unsafeRunSync()
    val realSse = Paths.get(realHome, ".nebflow", "logs", "router", s"${today}_sse.jsonl")
    if Files.exists(realSse) then
      // 流式逐行扫描（真实侧文件可达数百 MB，禁 readString 全量载入，#26 教训）。
      // 宿主实例并发追加不影响判定——只查本测试唯一 request_id 是否出现。
      val leaked = Using(scala.io.Source.fromFile(realSse.toFile, "UTF-8"))(_.getLines().exists(_.contains(id)))
        .toOption
      assertEquals(
        leaked,
        Some(false),
        "isolated-instance intake line must NEVER reach the real ~/.nebflow/logs/router"
      )
    else
      assert(!Files.exists(realSse)) // 真实侧连当日文件都不存在——天然不越界
  }

  test("隔离写入不越界：真实 ~/.nebflow/history 零触碰") {
    PathUtil.setDataRoot(isolatedRoot)
    val realHistory = Paths.get(realHome, ".nebflow", "history")
    val mtimeBefore = if Files.exists(realHistory) then Some(Files.getLastModifiedTime(realHistory)) else None
    val _ = nebflow.core.tools.FileHistory.create().unsafeRunSync()
    val mtimeAfter = if Files.exists(realHistory) then Some(Files.getLastModifiedTime(realHistory)) else None
    // createDirectories 对已存在目录不更新 mtime：mtime 变化 = 被新建/触碰 = 泄漏。
    assertEquals(mtimeAfter, mtimeBefore, "real ~/.nebflow/history must not be created/touched by an isolated instance")
  }

end LogWriterHomeIsolationSpec
