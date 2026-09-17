package nebflow.core.compact

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.tools.MemoryQueue
import nebflow.service.{MemoryBudget, MemoryStore}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * P0-c 分流 spec（2026-09-14 裁定 ③「收窄 dream 生产者 —— 按目标面 / 预算分流」）。
 *
 * 覆盖三支判词（纯函数直测）+ 三条 IO 正控：
 *   ① 首选面（user）有余量 ⇒ **仍投 user**（既有行为回归；**无落点节**——`section`
 *      恒 `None`，`## Dream Extract` 节已随 DreamMode 机制停用退役，引擎不再拥有具名节）；
 *   ② user 面无余量而 agent 面有余量 ⇒ **改投 agent 面**（`section=None` 文件尾追加）；
 *   ③ 两面皆无余量 ⇒ **停投**（零入队）+ **逐条 WARN 记录带条目全文** ——
 *      「禁静默丢」的运行时端断言（ListAppender 源级取证，先例见 DeadLoggingResurrectionSpec）。
 * 另一支「读数不可得 ⇒ 不丢（fail-open 到首选面）」：丢一条不可复得的事实比投进一个可能
 * 在 plan 侧被扣发的面更坏 ⇒ `Drop` 只可能在两面**都测到无余量**时发生。
 *
 * 记录一律经 `warnSync`（`NebflowLogger.warn` 返回 IO，在 `IO.blocking` 裸语句位会被丢弃 =
 * 死日志家族）⇒ 本 spec 的 ListAppender 断言同时构成「记录真的会打印」的证据。
 *
 * dataRoot 经 `PathUtil.setDataRoot` 钉临时目录（MemoryTrackSpec / DreamModeSpec 先例）⇒
 * 现场真实记忆文件（`~/.nebflow/User.md`、`agents/Nebula/memory.md`）与 `queue.jsonl`
 * **零接触**（本 spec 全程只写临时根内的副本）。
 */
class NebulaMemoryHookRouteSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-memhook-route"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def reset(): Unit =
    os.remove.all(home / "memory")
    os.remove.all(home / "User.md")
    os.remove.all(home / "agents")
    MemoryStore.invalidateUserCache()
    MemoryStore.invalidateAgentCache("Nebula")

  /** 写一个**恰为 `bytes` 字节**的 ASCII 记忆文件（余量判据要精确 ⇒ 不用近似填充）。 */
  private def fill(path: os.Path, bytes: Long): Unit =
    val head = "# m\n"
    os.write.over(path, head + ("x" * (bytes - head.length).toInt), createFolders = true)

  // ===== ① 纯函数：三支 + fail-open =====

  test("decideRoute：user 有余量 ⇒ 仍投 user（首选面，无落点节）"):
    val d = NebulaMemoryHook.decideRoute(100L, _ => Some(1000L))
    assertEquals(
      d,
      NebulaMemoryHook.RouteDecision.Send(
        "user",
        NebulaMemoryHook.RouteCode.Preferred,
        true
      )
    )

  test("decideRoute：user 无余量、agent 有余量 ⇒ 改投 agent 面"):
    val rooms = Map("user" -> -5L, "agent" -> 500L)
    val d = NebulaMemoryHook.decideRoute(100L, f => rooms.get(f))
    assertEquals(
      d,
      NebulaMemoryHook.RouteDecision.Send("agent", NebulaMemoryHook.RouteCode.Reroute, false)
    )

  test("decideRoute：两面皆无余量 ⇒ 停投（带两面余量读数）"):
    val rooms = Map("user" -> -11L, "agent" -> -3L)
    val d = NebulaMemoryHook.decideRoute(100L, f => rooms.get(f))
    assertEquals(d, NebulaMemoryHook.RouteDecision.Drop(NebulaMemoryHook.RouteCode.Drop, -11L, -3L))

  test("decideRoute：余量读数不可得 ⇒ 不丢（fail-open 到首选面）"):
    val d = NebulaMemoryHook.decideRoute(100L, _ => None)
    assertEquals(
      d,
      NebulaMemoryHook.RouteDecision.Send(
        "user",
        NebulaMemoryHook.RouteCode.FailOpen,
        false
      )
    )

  // ===== ② IO 正控：三支各自的可观测后果 =====

  test("正控-仍投 user：user 面有余量 ⇒ target 与既有行为逐字一致、无落点节"):
    reset()
    os.write.over(MemoryStore.userMemoryPath, "# User\n\n- 既有条目\n", createFolders = true)
    NebulaMemoryHook.enqueueFacts(List("FACT 1: [PATTERN] 正常事实"), Some("sess-1")).unsafeRunSync()
    val notes = MemoryQueue.readState().notes
    assertEquals(notes.size, 1)
    assertEquals(notes.map(_.target).distinct, Vector("user"))
    assertEquals(
      notes.map(_.section).distinct,
      Vector[Option[String]](None),
      "引擎不再拥有具名节（`## Dream Extract` 已随 DreamMode 停用退役）⇒ 恒文件尾追加"
    )

  test("正控-改投：User.md 顶格 ⇒ facts 改投 agent 面；User.md 零写"):
    reset()
    fill(MemoryStore.userMemoryPath, MemoryBudget.UserHardBytes)
    os.write.over(MemoryStore.agentMemoryPath("Nebula"), "# agent\n\n- 既有\n", createFolders = true)
    val before = os.read(MemoryStore.userMemoryPath)
    NebulaMemoryHook
      .enqueueFacts(List("FACT 1: [PATTERN] 事实甲", "FACT 2: [DECISION] 裁定乙"), Some("sess-1"))
      .unsafeRunSync()
    assertEquals(os.read(MemoryStore.userMemoryPath), before, "User.md 零写（直写通道保持关闭）")
    val notes = MemoryQueue.readState().notes
    assertEquals(notes.size, 2)
    assertEquals(notes.map(_.target).distinct, Vector("agent"), "user 面顶格 ⇒ 改投 agent 面")
    assertEquals(
      notes.map(_.section).distinct,
      Vector[Option[String]](None),
      "无具名节 ⇒ 文件尾追加"
    )

  test("正控-停投：两面皆顶格 ⇒ 零入队，且逐条 WARN 记录带全文（禁静默丢）"):
    reset()
    fill(MemoryStore.userMemoryPath, MemoryBudget.UserHardBytes)
    fill(MemoryStore.agentMemoryPath("Nebula"), MemoryBudget.AgentHardBytes)
    val lbLogger = org.slf4j.LoggerFactory
      .getLogger("nebflow.prehook.nebula")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      NebulaMemoryHook
        .enqueueFacts(List("FACT 1: [PATTERN] 无处可落的事实"), Some("sess-1"))
        .unsafeRunSync()
      assertEquals(MemoryQueue.readState().notes.size, 0, "两面皆无余量 ⇒ 停投：不灌队列")
      val warns = appender.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage)
        .filter(_.contains("[memory-route]"))
      assertEquals(warns.size, 1, s"停投必逐条留痕（禁静默丢）；实际 WARN 行：$warns")
      val line = warns.head
      assert(line.contains(NebulaMemoryHook.RouteCode.Drop), s"机读码在场：$line")
      assert(line.contains(NebulaMemoryHook.refOf("无处可落的事实")), s"识别子在场：$line")
      assert(line.contains("无处可落的事实"), s"条目全文在场（能逐条回答「为什么没落」）：$line")
    finally
      lbLogger.detachAppender(appender)

end NebulaMemoryHookRouteSpec
