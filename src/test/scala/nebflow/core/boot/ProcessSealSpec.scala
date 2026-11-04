package nebflow.core.boot

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import munit.FunSuite
import nebflow.core.tools.MailWindowProbe
import nebflow.gateway.PerceptionDaemonDock
import nebflow.neblink.DeviceMailAck
import nebflow.shared.Defaults
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * **邮件 / 心跳进程封存**（seal 批，2026-11-03；作者令逐字「邮件和心跳进程功能，进入
 * 封存，发布剔除，本地sbt run 可用。」）——本 spec 是那条令的**机械判据面**。
 *
 * 判据分三组（都能「改前必红、改后必绿」）：
 *
 *   1. **决策 + 注册面**（[[ProcessSeal]]）：默认两个开关读数都是 false ⇒ `decideFor` 对
 *      两族都给 `Sealed`；`RecordingRegistrar` 记账里 `launchedNames` 恒空、`sealedNames`
 *      逐条在场；生产出口 [[ProcessSeal.launch]] 在 `Sealed` 下**纤维体一次都不执行**
 *      （`AtomicInteger` 读数 = 0，不是「跑了但没做事」）；`-D…=true` 打开后恢复执行。
 *      ⇒ 「关闭时零后台进程/零定时器注册」是**记账读数**，不靠肉眼。
 *   2. **三处功能腿的关态读数**：分发器回复打包窗（`MailTool.dispatcherMailWindowMs`）
 *      关 ⇒ 0（单条直投）；设备邮件回执超时腿（`DeviceMailAck.await`）关 ⇒ 过了整个等待窗
 *      仍无 `delivery receipt not seen` 且登记不被动掉；感知守护 upsert（
 *      `PerceptionDaemonDock.prepare`）关 ⇒ `None` + 不写 token + `daemons.json` 零条目。
 *      三处都带**正控**（`-D…=true` 下恢复旧读数）——禁把功能本身一并吞掉。
 *   3. **接线落点 + 发布剔除**（源码/装配文本钉）：`GatewayMain` 的 daemon 家族 boot 扇出与
 *      `HeldDeliveryWatchdog` 拍必须经 `ProcessSeal` 门；打包脚本与 Dockerfile 必须显式写
 *      `-Dnebflow.mail.process.enabled=false` / `-Dnebflow.heartbeat.enabled=false`。
 *
 * 红侧（改前实测）：`dispatcherMailWindowMs` = 5000（非 0）、`DeviceMailAck` 15s 后打
 * `delivery receipt not seen`、`prepare` 返回 Some 且写了 token、`GatewayMain`/打包脚本里
 * 没有 `ProcessSeal` / 两个 `-D…=false` ⇒ 第 2、3 组各条皆红。
 */
class ProcessSealSpec extends FunSuite:

  /** 两条 15s 等待窗实测（关态 + 正控）⇒ 默认 30s 不够。 */
  override def munitTimeout: Duration = 120.seconds

  private val AcksLog = "nebflow.neblink.devicemail.ack"

  override def beforeEach(context: BeforeEach): Unit =
    DeviceMailAck.resetForTest().unsafeRunSync()
    ProcessSealProps.clearAll()

  override def afterAll(): Unit = ProcessSealProps.clearAll()

  // ── 1. 决策 + 注册面 ────────────────────────────────────────────────

  test("A · 默认封存：两个开关读数皆 false，两族 decision 皆 Sealed（单点默认值即发布意图）"):
    assertEquals(Defaults.MailProcessEnabled, false, "邮件后台进程默认封存")
    assertEquals(Defaults.HeartbeatProcessEnabled, false, "心跳（daemon 家族）默认封存")
    assertEquals(ProcessSeal.decide(true), ProcessSeal.Decision.Launch, "开 ⇒ Launch")
    assertEquals(ProcessSeal.decide(false), ProcessSeal.Decision.Sealed, "关 ⇒ Sealed")
    assertEquals(ProcessSeal.decideFor(ProcessSeal.Realm.Mail), ProcessSeal.Decision.Sealed)
    assertEquals(ProcessSeal.decideFor(ProcessSeal.Realm.Heartbeat), ProcessSeal.Decision.Sealed)

  test("B · 关态零注册：两族经 RecordingRegistrar 记账，launchedNames 全空、sealedNames 逐条在场"):
    val reg = new ProcessSeal.RecordingRegistrar
    val names = List(
      ProcessSeal.Realm.Mail      -> "perception-daemon dock",
      ProcessSeal.Realm.Mail      -> "HeldDeliveryWatchdog",
      ProcessSeal.Realm.Heartbeat -> "DaemonService.autoStart"
    )
    names.foldLeft(IO.unit)((acc, na) => acc *> ProcessSeal.launchWith(reg, na._1, na._2)(IO.unit)).unsafeRunSync()
    assertEquals(reg.attempts.map(a => a.realm -> a.name), names, "每条尝试都记账（零静默跳过）")
    assertEquals(reg.launchedNames, Nil, "🔴 关态必须零注册（launchedNames 全空）")
    assertEquals(reg.sealedNames, names.map(_._2), "每条都判 Sealed（不是漏记）")

  test("C · 打开恢复：-D…=true 后同一条记账面对两族都给 Launch（关态不是终态）"):
    ProcessSealProps.withMail(true) {
      ProcessSealProps.withHeartbeat(true) {
        val reg = new ProcessSeal.RecordingRegistrar
        ProcessSeal.launchWith(reg, ProcessSeal.Realm.Mail, "HeldDeliveryWatchdog")(IO.unit).unsafeRunSync()
        ProcessSeal.launchWith(reg, ProcessSeal.Realm.Heartbeat, "DaemonService.autoStart")(IO.unit).unsafeRunSync()
        assertEquals(reg.sealedNames, Nil, "开态零封存判定")
        assertEquals(reg.launchedNames, List("HeldDeliveryWatchdog", "DaemonService.autoStart"))
      }
    }

  test("D · 生产出口（ProcessSeal.launch）关态：纤维体一次都不执行（不是「起了但空转」）"):
    val runs = new AtomicInteger(0)
    ProcessSeal.launch(ProcessSeal.Realm.Mail, "probe-fiber")(IO { runs.incrementAndGet(); () }).unsafeRunSync()
    ProcessSeal.launch(ProcessSeal.Realm.Heartbeat, "probe-fiber")(IO { runs.incrementAndGet(); () }).unsafeRunSync()
    IO.sleep(300.millis).unsafeRunSync()
    assertEquals(runs.get(), 0, "🔴 Sealed ⇒ 零 .start（零后台进程/零定时器）")

  test("E · 生产出口正控：-D…=true 后同一调用确实起纤维（封存可逆）"):
    ProcessSealProps.withMail(true) {
      val runs = new AtomicInteger(0)
      ProcessSeal.launch(ProcessSeal.Realm.Mail, "probe-fiber")(IO { runs.incrementAndGet(); () }).unsafeRunSync()
      val deadline = System.currentTimeMillis() + 5000
      while runs.get() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(20)
      assertEquals(runs.get(), 1, "开态必须注册并执行（否则「封存」会变成「拆除」）")
    }

  test("F · 封存说明日志：Sealed 路径必须留一行 [seal]（可审计的唯一可见读数）"):
    val lines = withSealLog {
      ProcessSeal.launch(ProcessSeal.Realm.Heartbeat, "DaemonService.autoStart")(IO.unit).unsafeRunSync()
    }
    assert(
      lines.exists(l => l.contains("[seal]") && l.contains("已封存") && l.contains("nebflow.heartbeat.enabled=false")),
      s"封存行必须写明 prop 与族名，实得：$lines"
    )

  // ── 2. 三处功能腿 ──────────────────────────────────────────────────

  test("G · 分发器回复打包窗关态：dispatcherMailWindowMs == 0（单条直投），开态恢复默认窗"):
    assertEquals(MailWindowProbe.windowMs, 0L, "关态必须关窗（0 = 每条直投，零定时腿）")
    ProcessSealProps.withMail(true) {
      assertEquals(MailWindowProbe.windowMs, 5000L, "开态恢复 prop 默认窗长（功能本体不删）")
    }

  test("H · 回执超时腿关态：过了整个等待窗仍无 `delivery receipt not seen`，登记不动（零定时器）"):
    val (warns, _) = withAckLog {
      assertEquals(DeviceMailAck.await("dev-sealed", "sealed-1").unsafeRunSync(), "message-sealed-1")
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 1, "登记照旧（ack 关联/审计是功能本体，不封）")
      IO.sleep(DeviceMailAck.AckTimeout + 1.second).unsafeRunSync()
      assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 1, "🔴 关态不得挂超时腿（到点不得清掉登记）")
    }
    assert(
      !warns.exists(_.contains("delivery receipt not seen")),
      s"🔴 封存后禁再打超时结论，实得：$warns"
    )

  test("I · 回执超时腿正控：-D…=true 后 15s 到点照旧出 `delivery receipt not seen` 并出队"):
    ProcessSealProps.withMail(true) {
      val (warns, _) = withAckLog {
        DeviceMailAck.await("dev-offline", "open-1").unsafeRunSync()
        IO.sleep(DeviceMailAck.AckTimeout + 1.second).unsafeRunSync()
        assertEquals(DeviceMailAck.pendingCount.unsafeRunSync(), 0, "开态到点出队")
      }
      assert(warns.exists(_.contains("delivery receipt not seen")), s"正控：真超时仍判出，实得：$warns")
    }

  test("J · 感知守护 dock 关态：prepare ⇒ None，不写 token、daemons.json 零条目；开态恢复 upsert"):
    val home = os.pwd / "target" / "process-seal-spec" / s"dock-${System.nanoTime()}"
    os.makeDir.all(home)
    val sealedStore = new nebflow.core.daemon.DaemonStore(home / "sealed-daemons.json")
    assertEquals(
      PerceptionDaemonDock.prepare(sealedStore, enginePort = 59999, home = home).unsafeRunSync(),
      None,
      "关态 ⇒ None（不 spawn、不握手）"
    )
    assertEquals(os.exists(PerceptionDaemonDock.tokenFile(home)), false, "🔴 关态不得写 boot token（零初始化）")
    assertEquals(sealedStore.load().unsafeRunSync(), Nil, "🔴 关态不得 upsert 托管条目")
    ProcessSealProps.withMail(true) {
      val openStore = new nebflow.core.daemon.DaemonStore(home / "open-daemons.json")
      val up = PerceptionDaemonDock.prepare(openStore, enginePort = 59999, home = home).unsafeRunSync()
      assert(up.exists(_.spawnable), "开态恢复 upsert（测试 JVM classpath 非空 ⇒ spawnable）")
      assert(os.exists(PerceptionDaemonDock.tokenFile(home)), "开态写 token")
      assertEquals(openStore.load().unsafeRunSync().count(_.id == PerceptionDaemonDock.EntryId), 1)
    }

  // ── 3. 接线落点 + 发布剔除 ─────────────────────────────────────────

  test("K · 接线落点：GatewayMain 的 daemon 家族扇出与 HeldDeliveryWatchdog 拍都经 ProcessSeal 门"):
    val src = os.read(os.pwd / "src/main/scala/nebflow/gateway/GatewayMain.scala")
    val daemonIdx = src.indexOf("autoStart(DaemonStore())")
    assert(daemonIdx > 0, "daemon 家族 boot 扇出必须仍在 GatewayMain（本批不搬动行为）")
    val daemonWindow = src.substring(math.max(0, daemonIdx - 1200), daemonIdx)
    assert(daemonWindow.contains("ProcessSeal"), s"🔴 autoStart 前必须有 ProcessSeal 门：…${daemonWindow.takeRight(200)}")

    val hdwIdx = src.indexOf("nebflow.core.processor.HeldDeliveryWatchdog")
    assert(hdwIdx > 0, "HeldDeliveryWatchdog 拍必须仍在 boot 链")
    val hdwWindow = src.substring(math.max(0, hdwIdx - 400), hdwIdx)
    assert(
      hdwWindow.contains("ProcessSeal.launch"),
      s"🔴 HeldDeliveryWatchdog 必须经 ProcessSeal 引导（禁裸 .start）：…${hdwWindow.takeRight(200)}"
    )
    assert(
      !src.substring(hdwIdx, hdwIdx + 400).contains(".start"),
      "🔴 引导器内部已负责 .start；调用点不得再挂裸 .start（否则封存失效）"
    )

    val dockSrc = os.read(os.pwd / "src/main/scala/nebflow/gateway/PerceptionDaemonDock.scala")
    val prepIdx = dockSrc.indexOf("def prepare(")
    assert(prepIdx > 0, "prepare 必须在场（spec 另有调用）")
    assert(
      dockSrc.substring(prepIdx, math.min(dockSrc.length, prepIdx + 900)).contains("MailProcessEnabled"),
      "🔴 prepare 自身必须读封存开关（生产与 spec 同一条门）"
    )

  test("L · 发布剔除：三平台打包脚本 + Dockerfile 显式写两条 -D…=false（装配级默认，与本地 run 分离）"):
    val files = List(
      "packaging/build-dmg.sh",
      "packaging/build-linux.sh",
      "packaging/build-msi.sh",
      "Dockerfile"
    )
    files.foreach { rel =>
      val src = os.read(os.pwd / os.RelPath(rel))
      assert(
        src.contains("nebflow.mail.process.enabled=false"),
        s"$rel 必须显式写 -Dnebflow.mail.process.enabled=false"
      )
      assert(
        src.contains("nebflow.heartbeat.enabled=false"),
        s"$rel 必须显式写 -Dnebflow.heartbeat.enabled=false"
      )
    }

  // ── 读数设施 ──────────────────────────────────────────────────────

  private def withSealLog(f: => Unit): List[String] =
    val lb = LoggerFactory.getLogger("nebflow.seal").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    lb.addAppender(appender)
    try
      f
      appender.list.toArray(Array.empty[ILoggingEvent]).toList.map(_.getFormattedMessage)
    finally lb.detachAppender(appender)

  private def withAckLog(f: => Unit): (List[String], List[String]) =
    val lb = LoggerFactory.getLogger(AcksLog).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    lb.addAppender(appender)
    try
      f
      val events = appender.list.toArray(Array.empty[ILoggingEvent]).toList
      (events.filter(_.getLevel.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)).map(_.getFormattedMessage),
       events.filter(_.getLevel == ch.qos.logback.classic.Level.INFO).map(_.getFormattedMessage))
    finally lb.detachAppender(appender)

end ProcessSealSpec

/** 开关属性的**测试括号**（同一套手法见 `MailToolTestAccess.withWindowMs`）：跑完必还原。 */
object ProcessSealProps:

  private val MailKey = "nebflow.mail.process.enabled"
  private val HeartbeatKey = "nebflow.heartbeat.enabled"

  private def set[A](key: String, value: Boolean)(body: => A): A =
    val old = System.getProperty(key)
    System.setProperty(key, value.toString)
    try body
    finally
      if old == null then System.clearProperty(key)
      else System.setProperty(key, old)

  def withMail[A](enabled: Boolean)(body: => A): A = set(MailKey, enabled)(body)

  def withHeartbeat[A](enabled: Boolean)(body: => A): A = set(HeartbeatKey, enabled)(body)

  /**
   * **既有邮件 spec 的复活闸**（seal 批）：这些 spec 判的是「后台腿在场时的行为」
   * （打包窗 / 15s 超时腿），封存后默认关 ⇒ 在它们的 `beforeEach` 里打开、
   * `afterEach` 里 [[clearAll]]（套件间零残留；`Test / parallelExecution := false`）。
   */
  def enableMail(): Unit = System.setProperty(MailKey, "true")

  def clearAll(): Unit =
    System.clearProperty(MailKey)
    System.clearProperty(HeartbeatKey)

end ProcessSealProps
