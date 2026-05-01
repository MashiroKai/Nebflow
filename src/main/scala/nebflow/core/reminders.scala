package nebflow.core

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.TokenUsage
import nebflow.skill.SkillMatch

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

case class SystemReminder(category: String, content: String):
  def render: String = s"<system-reminder>\n$content\n</system-reminder>"

object SystemReminder:

  def renderAll(reminders: List[SystemReminder]): String =
    if reminders.isEmpty then ""
    else if reminders.length == 1 then reminders.head.render
    else
      val body = reminders.map(_.content).mkString("\n\n")
      s"<system-reminder>\n$body\n</system-reminder>"

case class ReminderState(
  lastActivityMs: Long = 0L,
  sessionStarted: Boolean = false,
  highestPressureLevel: Int = 0,
  sandboxReminderPending: Boolean = true,
  policyReminderPending: Boolean = false
)

object SystemReminders:
  private val IdleThresholdMs: Long = 10 * 60 * 1000L // 10 minutes

  private val PressureLevels = List(20, 40, 60, 80)

  def contextPressure(usage: TokenUsage, contextWindow: Int, highestLevel: Int): (Option[SystemReminder], Int) =
    if contextWindow <= 0 then (None, highestLevel)
    else
      val ratio = usage.inputTokens.toDouble / contextWindow.toDouble
      val pct = (ratio * 100).toInt
      val newHighest = PressureLevels.filter(l => pct >= l).maxOption.getOrElse(0)
      if newHighest > highestLevel then
        val suggestion =
          if newHighest >= 80 then
            s"\nContext is approaching the limit. Consider using the ContextManage tool to summarize or delete older messages to free up space."
          else ""
        (
          Some(
            SystemReminder(
              "contextPressure",
              s"Token usage: ${usage.inputTokens} / $contextWindow (${pct}% of context window).$suggestion"
            )
          ),
          newHighest
        )
      else (None, highestLevel)

  def sessionInfo(): SystemReminder =
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    SystemReminder("sessionInfo", s"Current date and time: $now")

  def policyStatus(policy: PermissionPolicy): SystemReminder =
    val desc = policy match
      case p if p.autoApproveAll => "all tools auto-approved"
      case p if p.blockedTools.nonEmpty => s"blocked: ${p.blockedTools.mkString(", ")}; others auto-approved"
      case _ => "Bash, Write, Edit, Curl require user approval; safe tools auto-approved"
    SystemReminder("permissionPolicy", s"Current permission policy: $desc")

  def policyChange(policy: PermissionPolicy): SystemReminder =
    val desc = policy match
      case p if p.autoApproveAll => "all tools auto-approved"
      case p if p.blockedTools.nonEmpty => s"blocked: ${p.blockedTools.mkString(", ")}; others auto-approved"
      case _ => "Bash, Write, Edit, Curl require user approval; safe tools auto-approved"
    SystemReminder("permissionPolicy", s"Permission policy changed: $desc")

  def fileChanges(files: List[String]): SystemReminder =
    val fileList = files
      .take(20)
      .mkString("\n  - ", "\n  - ", if files.length > 20 then s"\n  ... and ${files.length - 20} more" else "")
    SystemReminder("fileChanges", s"The following files were modified externally since the last message:$fileList")

  private val logger = NebflowLogger.forName("nebflow.reminders")

  def collectAll(
    stateRef: Ref[IO, ReminderState],
    usage: Option[TokenUsage],
    contextWindow: Int,
    fileChangesOpt: Option[SystemReminder],
    currentPolicy: PermissionPolicy,
    skillMatchOpt: Option[SkillMatch] = None
  ): IO[List[SystemReminder]] =
    val now = System.currentTimeMillis()

    // Use modify for atomic read+update to avoid losing concurrent flag changes (e.g. setPolicy)
    stateRef
      .modify { state =>
        val reminders = scala.collection.mutable.ListBuffer.empty[SystemReminder]

        // Session info: first message, after /clear, or after idle
        val shouldInjectSession = !state.sessionStarted ||
          (state.lastActivityMs > 0 && now - state.lastActivityMs > IdleThresholdMs)
        if shouldInjectSession then reminders += sessionInfo()

        // Token pressure
        val (pressureReminder, updatedHighest) = usage match
          case Some(u) => contextPressure(u, contextWindow, state.highestPressureLevel)
          case None => (None, state.highestPressureLevel)
        pressureReminder.foreach(reminders += _)

        // Permission policy status (one-time at session start)
        if state.sandboxReminderPending then reminders += policyStatus(currentPolicy)

        // Policy change (one-time after /trust)
        if state.policyReminderPending then reminders += policyChange(currentPolicy)

        // External file changes
        fileChangesOpt.foreach(reminders += _)

        // Skill discovery match
        skillMatchOpt.foreach { m =>
          reminders += SystemReminder(
            "skillDiscovery",
            s"""检测到相关技能：${m.skillName}
描述：${m.description}
路径：${m.filePath}
如果相关，可读取该文件获取完整指令。如果无关，请忽略。"""
          )
        }

        val newState = state.copy(
          lastActivityMs = now,
          sessionStarted = true,
          highestPressureLevel = updatedHighest,
          sandboxReminderPending = false,
          policyReminderPending = false
        )

        (newState, reminders.toList)
      }
      .flatMap { reminders =>
        reminders
          .traverse_ { r =>
            logger.info(s"[${r.category}] ${r.content.take(100)}")
          }
          .as(reminders)
      }
  end collectAll
end SystemReminders
