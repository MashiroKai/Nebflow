package nebflow.core

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.scheduler.ScheduledTaskStore
import nebflow.shared.{Message, MessageRole}

/**
 * SystemReminders — 时钟注入契约。
 *
 * 沿革：原契约（任务 O）= `Current time: yyyy-MM-dd HH:mm +08:00`；时间感知修复批
 * （2026-11-05）加宽为全字段锚；**时钟简化批（作者令 2026-11-06 22:26/22:29）改写
 * 现契约**：
 *  - 时钟**只随真用户轮**（source=None 的真人输入）注入——Mail / 节点消息 / 定时
 *    任务 / 外部事件驱动的 system-event 轮与工具往返轮**零时钟**（「永不主动注入」，
 *    空转轮被每请求时钟反复叫醒、无法收轮是本批病根）；
 *  - 内容 = 极简形 `Now: yyyy-MM-dd HH:mm:ss +08:00 周一 | epoch_ms <ms>`
 *    （TimeAnchor.userLine，无推导文案——行为纪律由 systemStable 时间纪律段承担）；
 *  - 持久化 = **append-only**：新形态不被 isTimeReminderMessage 识别 ⇒ 永不裁剪；
 *    旧 `Current time:` 形态的识别/裁剪契约保留（管旧会话历史）。
 */
class SystemRemindersSpec extends FunSuite:

  private val timePattern =
    raw"Now: \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} (Z|[+-]\d{2}:\d{2}) 周[一二三四五六日] \| epoch_ms \d+".r

  test("clock generation is absent from reminder collection (input conversion owns it)"):
    assertEquals(SystemReminders.collectAll(), Nil)

  test("insertAnchor puts the anchor BEFORE the triggering input (last-user-input invariant kept)"):
    val input = Message(MessageRole.User, Left("hello"), source = None)
    val mail = Message(MessageRole.User, Left("[MAIL] task"), source = Some("mail"))
    val anchor = Message(MessageRole.User, Left("<system-reminder>\nCurrent time: x\n</system-reminder>"))
    // ① 真人输入：锚在前、输入仍在最后（「最后一条 user = 本轮输入」不变量）
    val a = SystemReminders.insertAnchor(List(input), List(anchor))
    assertEquals(a.map(_.textContent), List(anchor.textContent, "hello"))
    assertEquals(a.last.source, None)
    // ② Mail 注入：锚在前，注入源仍是最后一条（注入气泡归因靠它）
    val b = SystemReminders.insertAnchor(List(input, mail), List(anchor))
    assertEquals(b.map(_.textContent), List("hello", anchor.textContent, "[MAIL] task"))
    assertEquals(b.last.source, Some("mail"), "注入源必须仍是最后一条（否则归因读成『无源』）")
    // ③ 空锚 = 零改动
    assertEquals(SystemReminders.insertAnchor(List(input), Nil), List(input))
    // ④ 工具结果轮（最后一条是 ToolResult）：不得插在工具结果之后造成 user/user 交替错位
    val toolTurn = Message(
      MessageRole.User,
      Right(List(nebflow.shared.ContentBlock.ToolResult("id-1", "ok")))
    )
    assertEquals(SystemReminders.insertAnchor(List(toolTurn), List(anchor)).last, anchor)

  test("reminder collection never creates a clock for any turn"):
    val taskStore = new ScheduledTaskStore(os.temp.dir() / "scheduled-tasks")
    val reminders = SystemReminders
      .collectAllIO(isUserTurn = true, taskStore = taskStore, sessionId = None)
      .unsafeRunSync()
    assert(!reminders.exists(_.category == "time"), s"clock is attached only during real input conversion: $reminders")
    // 工具往返 / delegate 续轮 / 空转轮（!isUserTurn）：收集面直接短路 Nil——零时钟
    val continuation = SystemReminders
      .collectAllIO(isUserTurn = false, taskStore = taskStore, sessionId = None)
      .unsafeRunSync()
    assertEquals(continuation, Nil, "continuation/tool rounds: collection must short-circuit to Nil (zero clock)")

  test("isTimeReminderMessage matches persisted time-reminder format (legacy face)"):
    val reminder =
      Message(MessageRole.User, Left("<system-reminder>\nCurrent time: 2026-08-11 17:00 +08:00\n</system-reminder>"))
    assert(SystemReminders.isTimeReminderMessage(reminder))
    assert(!SystemReminders.isTimeReminderMessage(Message(MessageRole.User, Left("hello"))))
    assert(!SystemReminders.isTimeReminderMessage(Message(MessageRole.Assistant, Left("Current time: x"))))
    // 时钟简化批：新极简形态**不被识别** ⇒ append-only 永不裁剪
    assert(
      !SystemReminders.isTimeReminderMessage(
        Message(
          MessageRole.User,
          Left("<system-reminder>\nNow: 2026-11-06 22:29:36 +08:00 周五 | epoch_ms 1793975376978\n</system-reminder>")
        )
      ),
      "新形态不得进旧裁剪闸"
    )

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
    assert(
      !reminders.exists(_.category == "projects"),
      s"must not inject a projects reminder: ${reminders.map(_.category)}"
    )

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
      case (
            catsSuppressed,
            loggedSuppressed,
            cntSuppressed,
            emittedDev,
            loggedDev,
            cntEmitted,
            linesSuppressed,
            lines
          ) =>
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

    end match

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

    end match

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

    end match

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
