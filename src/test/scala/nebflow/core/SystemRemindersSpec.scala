package nebflow.core

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.scheduler.ScheduledTaskStore
import nebflow.shared.{Message, MessageRole}

/**
 * SystemReminders — current-time injection contract (任务 O):
 *   - timezone-aware format: "Current time: 2026-08-11 17:00 +08:00"
 *   - persisted time-reminder retention: pruneTimeReminders keeps only the
 *     most recent MaxPersistedTimeReminders, never drops non-time messages.
 */
class SystemRemindersSpec extends FunSuite:

  private val timePattern = raw"Current time: \d{4}-\d{2}-\d{2} \d{2}:\d{2} (Z|[+-]\d{2}:\d{2})".r

  test("time reminder is injected on user turns only"):
    val on = SystemReminders.collectAll(isUserTurn = true)
    val off = SystemReminders.collectAll(isUserTurn = false)
    assertEquals(on.size, 1)
    assertEquals(on.head.category, "time")
    assertEquals(off.size, 0)

  test("time format includes timezone offset (yyyy-MM-dd HH:mm XXX)"):
    val on = SystemReminders.collectAll(isUserTurn = true)
    val content = on.head.content
    assert(
      timePattern.matches(content),
      s"expected 'Current time: yyyy-MM-dd HH:mm +HH:MM' but got: $content"
    )
    // Offset must match the JVM default zone's offset (e.g. +08:00 for China)
    val offset = java.time.ZonedDateTime.now().getOffset.toString
    assert(
      content.endsWith(s" $offset") || content.contains(offset),
      s"offset $offset not present in: $content"
    )

  test("isTimeReminderMessage matches persisted time-reminder format"):
    val reminder =
      Message(MessageRole.User, Left("<system-reminder>\nCurrent time: 2026-08-11 17:00 +08:00\n</system-reminder>"))
    assert(SystemReminders.isTimeReminderMessage(reminder))
    assert(!SystemReminders.isTimeReminderMessage(Message(MessageRole.User, Left("hello"))))
    assert(!SystemReminders.isTimeReminderMessage(Message(MessageRole.Assistant, Left("Current time: x"))))

  test("pruneTimeReminders keeps only the most recent N time reminders, preserving order"):
    val before = (0 until 25).map { i =>
      Message(
        MessageRole.User,
        Left(s"<system-reminder>\nCurrent time: 2026-08-11 ${i / 60}:${i % 60} +08:00\n</system-reminder>")
      )
    }.toList
    // Interleave some non-time messages to verify they are never dropped
    val mixed = List(
      before.head,
      Message(MessageRole.User, Left("user msg 1")),
      Message(MessageRole.Assistant, Left("assistant 1"))
    ) ++ before.drop(1) :+ Message(MessageRole.User, Left("user msg 2"))

    val pruned = SystemReminders.pruneTimeReminders(mixed)
    val keptTime = pruned.filter(SystemReminders.isTimeReminderMessage)
    assertEquals(keptTime.size, SystemReminders.MaxPersistedTimeReminders)
    // The 20 most recent time reminders survive, oldest 5 dropped
    assert(pruned.exists(m => m.textContent == "user msg 1"), "non-time user message kept")
    assert(pruned.exists(m => m.textContent == "assistant 1"), "non-time assistant message kept")
    assert(pruned.exists(m => m.textContent == "user msg 2"), "trailing user message kept")
    // Order preserved: relative order of kept messages unchanged
    assertEquals(pruned.filter(SystemReminders.isTimeReminderMessage), keptTime)

  test("pruneTimeReminders is a no-op when under the cap"):
    val messages = (0 until 3).map { i =>
      Message(MessageRole.User, Left(s"<system-reminder>\nCurrent time: 2026-08-11 0$i:00 +08:00\n</system-reminder>"))
    }.toList
    assertEquals(SystemReminders.pruneTimeReminders(messages), messages)

  // ============================================================
  // Mounted projects reminder (progressive disclosure, 2026-09-07):
  // fires only when a non-empty delta is supplied; none otherwise.
  // ============================================================

  test("projects reminder fires when mountedProjectsDelta is non-empty"):
    val taskStore = new ScheduledTaskStore(os.temp.dir() / "scheduled-tasks")
    val reminders = SystemReminders
      .collectAllIO(
        isUserTurn = true,
        taskStore = taskStore,
        sessionId = None,
        mountedProjectsDelta = "+ - voice-recognition-test: STT\n- - czt-project"
      )
      .unsafeRunSync()
    val projects = reminders.find(_.category == "projects")
    assert(projects.isDefined, s"expected a 'projects' reminder, got: ${reminders.map(_.category)}")
    assert(projects.get.content.contains("+ - voice-recognition-test: STT"), projects.get.content)
    assert(projects.get.content.contains("- - czt-project"), projects.get.content)

  test("no projects reminder when mountedProjectsDelta is empty"):
    val taskStore = new ScheduledTaskStore(os.temp.dir() / "scheduled-tasks")
    val reminders = SystemReminders
      .collectAllIO(
        isUserTurn = true,
        taskStore = taskStore,
        sessionId = None,
        mountedProjectsDelta = ""
      )
      .unsafeRunSync()
    assert(!reminders.exists(_.category == "projects"), s"must not inject a projects reminder: ${reminders.map(_.category)}")

  // ============================================================
  // F-2（presdial 批 2026-09-19）—— devices 计数面：
  //   ① 未交付不计数（compact/ask 轮零对象零计数行）；
  //   ② 同一变化跨会话只计一次（M1）；
  //   ③ 文本轴抖动（画像 / ⚠stale）零计数增量（M3）；
  //   ④ 计数键 = 成员集合（deviceId 面），与渲染文本无关。
  // 计数面 = `[devices]` 日志行本身（诊断里 KAI 侧 28 条的正身）。
  // ============================================================

  private final class RemindersAppender
      extends ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent]:
    val lines = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
      lines.add(event.getFormattedMessage)

  /** Capture the `nebflow.reminders` channel while `body` runs. */
  private def captureRemindersLog[A](body: java.util.concurrent.ConcurrentLinkedQueue[String] => A): A =
    org.slf4j.LoggerFactory.getLogger("nebflow.reminders") match
      case lb: ch.qos.logback.classic.Logger =>
        val appender = new RemindersAppender
        appender.setContext(lb.getLoggerContext)
        appender.start()
        lb.addAppender(appender)
        try body(appender.lines)
        finally lb.detachAppender(appender)
      case other => fail(s"expected a logback logger for the reminders channel, got $other")

  private def deviceLines(lines: java.util.concurrent.ConcurrentLinkedQueue[String]): List[String] =
    lines.toArray.toList.map(_.toString).filter(_.contains("[devices]"))

  private def freshTaskStore(): ScheduledTaskStore =
    new ScheduledTaskStore(os.temp.dir() / "scheduled-tasks")

  test("devices reminder is neither logged nor emitted on compact/ask turns"):
    SystemReminders.DeviceChangeCount.reset()
    val taskStore = freshTaskStore()
    captureRemindersLog { lines =>
      val suppressed = SystemReminders
        .collectAllIO(
          isUserTurn = true,
          taskStore = taskStore,
          sessionId = None,
          deviceDelta = "+DEVX [cwd=/p2]",
          deviceMemberKey = "members:x",
          suppressContextReminders = true
        )
        .unsafeRunSync()
      val loggedSuppressed = SystemReminders.logAndReturn(suppressed).unsafeRunSync()
      val afterSuppressed = SystemReminders.DeviceChangeCount.total
      // 🔴 快照必须在控制项**之前**取：控制项自己会打一行（这里要判的是抑制轮零行）
      val linesAfterSuppressed = deviceLines(lines)
      // 控制项：同一 delta 在**真用户轮**（未抑制）仍必须出对象 + 出一行
      val emitted = SystemReminders
        .collectAllIO(
          isUserTurn = true,
          taskStore = taskStore,
          sessionId = None,
          deviceDelta = "+DEVX [cwd=/p2]",
          deviceMemberKey = "members:x"
        )
        .unsafeRunSync()
      val loggedEmitted = SystemReminders.logAndReturn(emitted).unsafeRunSync()
      val afterEmitted = SystemReminders.DeviceChangeCount.total
      (
        suppressed.map(_.category),
        loggedSuppressed.map(_.category),
        afterSuppressed,
        emitted.exists(_.category == "devices"),
        loggedEmitted.exists(_.category == "devices"),
        afterEmitted,
        linesAfterSuppressed,
        deviceLines(lines)
      )
    } match
      case (catsSuppressed, loggedSuppressed, cntSuppressed, emittedDev, loggedDev, cntEmitted, linesSuppressed, lines) =>
        assert(
          !catsSuppressed.contains("devices"),
          s"compact/ask 轮不得产生 devices 提醒对象（改前会产出 ⇒ 打了行但没注入 = M2）: $catsSuppressed"
        )
        assert(
          !loggedSuppressed.contains("devices"),
          s"被抑制的轮零 devices 提醒对象（time 等非抑制通道不变）: $loggedSuppressed"
        )
        assertEquals(cntSuppressed, 0L, "被抑制的轮零计数增量")
        assertEquals(linesSuppressed, Nil, "compact/ask 轮不得打 [devices] 计数行")
        // 控制项（同一 delta、同一计数键）：真用户轮照常
        assert(emittedDev, "控制项：真用户轮的 devices 提醒对象必须仍在")
        assert(loggedDev, "控制项：真用户轮的 devices 提醒必须仍被返回（注入不受计数面影响）")
        assertEquals(lines.size, 1, s"控制项：真用户轮恰一行 [devices]: $lines")
        assertEquals(cntEmitted, 1L, "控制项：计数恰 1")

  test("F-2 (a) a single device change is counted once across N=2 sessions"):
    SystemReminders.DeviceChangeCount.reset()
    val taskStore = freshTaskStore()
    val delta = "+DEVZ [cwd=/p1]"
    val key = SystemReminders.deviceMemberKey(List("local-1", "dev-z"))
    captureRemindersLog { lines =>
      val s1 = SystemReminders
        .collectAllIO(true, taskStore, Some("session-1"), deviceDelta = delta, deviceMemberKey = key)
        .unsafeRunSync()
      val l1 = SystemReminders.logAndReturn(s1).unsafeRunSync()
      val s2 = SystemReminders
        .collectAllIO(true, taskStore, Some("session-2"), deviceDelta = delta, deviceMemberKey = key)
        .unsafeRunSync()
      val l2 = SystemReminders.logAndReturn(s2).unsafeRunSync()
      (
        l1.exists(_.category == "devices"),
        l2.exists(_.category == "devices"),
        deviceLines(lines),
        SystemReminders.DeviceChangeCount.total
      )
    } match
      case (dev1, dev2, lines, total) =>
        assert(dev1 && dev2, "两个会话**都**必须收到 devices 提醒（注入是每会话的）")
        assertEquals(lines.size, 1, s"同一变化在 2 个会话只计一次（改前 = 2 行）: $lines")
        assertEquals(total, 1L, "计数 = 1")

  test("F-2 (b) re-rendering the same member set yields zero count increment"):
    SystemReminders.DeviceChangeCount.reset()
    val taskStore = freshTaskStore()
    val key = SystemReminders.deviceMemberKey(List("local-1", "dev-z"))
    captureRemindersLog { lines =>
      val first = SystemReminders
        .collectAllIO(true, taskStore, None, deviceDelta = "+DEVZ [cwd=/p1]", deviceMemberKey = key)
        .unsafeRunSync()
      SystemReminders.logAndReturn(first).unsafeRunSync()
      // 只换**渲染文本**（⚠stale / 画像轴），成员集合不变 ⇒ 不得算新变化
      val rerender = SystemReminders
        .collectAllIO(true, taskStore, None, deviceDelta = "+DEVZ [cwd=/p1, ⚠stale]", deviceMemberKey = key)
        .unsafeRunSync()
      SystemReminders.logAndReturn(rerender).unsafeRunSync()
      val afterRerender = SystemReminders.DeviceChangeCount.total
      // 负控：**成员集合真的变了** ⇒ 必须再计一次（去重不是「一次之后永不计数」）
      val otherKey = SystemReminders.deviceMemberKey(List("local-1", "dev-z", "dev-w"))
      val grown = SystemReminders
        .collectAllIO(true, taskStore, None, deviceDelta = "+DEVW", deviceMemberKey = otherKey)
        .unsafeRunSync()
      SystemReminders.logAndReturn(grown).unsafeRunSync()
      (
        rerender.exists(_.category == "devices"),
        deviceLines(lines),
        afterRerender,
        SystemReminders.DeviceChangeCount.total
      )
    } match
      case (rerendered, lines, afterRerender, afterGrown) =>
        assert(rerendered, "文本轴变化仍必须注入（条目字面属提示词面，本批零改动）")
        assertEquals(lines.size, 2, s"重渲染零增量 + 成员集合变化计一次 ⇒ 总共 2 行: $lines")
        assertEquals(afterRerender, 1L, "同一成员集合的重渲染零计数增量")
        assertEquals(afterGrown, 2L, "负控：成员集合变化必须重新计数")

  test("F-2 count key is the member set (deviceId face), never the rendered text"):
    assertEquals(
      SystemReminders.deviceMemberKey(List("dev-b", "dev-a", "dev-b")),
      SystemReminders.deviceMemberKey(List("dev-a", "dev-b")),
      "顺序/重复不影响键"
    )
    assert(
      SystemReminders.deviceMemberKey(List("dev-a")) != SystemReminders.deviceMemberKey(List("dev-a", "dev-b")),
      "成员增删必须换键"
    )
    assertEquals(SystemReminders.deviceMemberKey(List("", "  ")), "", "无成员 ⇒ 空键（不参与去重 = 旧行为）")
    assertEquals(SystemReminders.DeviceChangeCount.claim("k-x"), true)
    assertEquals(SystemReminders.DeviceChangeCount.claim("k-x"), false, "同一键第二次 = 零增量")
    assertEquals(SystemReminders.DeviceChangeCount.claim("k-y"), true, "换键 = 新变化")

end SystemRemindersSpec
