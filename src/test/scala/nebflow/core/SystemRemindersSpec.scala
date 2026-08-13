package nebflow.core

import munit.FunSuite
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

end SystemRemindersSpec
