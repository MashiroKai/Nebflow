package nebflow.core.task

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.generic.semiauto.deriveCodec

enum TaskStatus:
  case Pending, InProgress, NeedsConfirmation, Completed, Failed, Dismissed, Cancelled

object TaskStatus:

  /**
   * Wire-format status name — the SINGLE source for every string form
   * (codec, index, tools, filters). `toString.toLowerCase` loses the
   * underscore (InProgress -> "inprogress") and MUST NOT be used for
   * user/agent-visible strings or filters (qa toolopt-task-20260816).
   */
  def wireName(s: TaskStatus): String = s match
    case TaskStatus.Pending => "pending"
    case TaskStatus.InProgress => "in_progress"
    case TaskStatus.NeedsConfirmation => "needs_confirmation"
    case TaskStatus.Completed => "completed"
    case TaskStatus.Failed => "failed"
    case TaskStatus.Dismissed => "dismissed"
    case TaskStatus.Cancelled => "cancelled"

  given Codec[TaskStatus] = io.circe.Codec.from(
    io.circe.Decoder.decodeString.emap {
      case "pending" => Right(TaskStatus.Pending)
      case "in_progress" => Right(TaskStatus.InProgress)
      case "needs_confirmation" => Right(TaskStatus.NeedsConfirmation)
      case "completed" => Right(TaskStatus.Completed)
      case "failed" => Right(TaskStatus.Failed)
      case "dismissed" => Right(TaskStatus.Dismissed)
      case "cancelled" => Right(TaskStatus.Cancelled)
      case other => Left(s"Unknown task status: $other")
    },
    io.circe.Encoder.encodeString.contramap {
      case TaskStatus.Pending => "pending"
      case TaskStatus.InProgress => "in_progress"
      case TaskStatus.NeedsConfirmation => "needs_confirmation"
      case TaskStatus.Completed => "completed"
      case TaskStatus.Failed => "failed"
      case TaskStatus.Dismissed => "dismissed"
      case TaskStatus.Cancelled => "cancelled"
    }
  )

end TaskStatus

/**
 * A note attached to a task — durable outcome/summary text an agent wants to
 * keep for later retrieval (C2). Append-only via TaskUpdate(note=...).
 * Wire field names follow the design doc (/tmp/tool-opt-task-inventory.md B1):
 * content / links / at. The doc's `by` field is NOT implemented (P1, doc
 * erratum pending) — see the fix commit for the ruling.
 */
case class TaskNote(
  content: String,
  links: List[String] = Nil,
  at: Option[String] = None,
  /** Structured note kind (task-cancel #35): "cancel" marks a user-cancel
    * reason note — the archive view renders it in the dedicated cancel-reason
    * slot (when cancelReason is absent) and excludes it from the generic
    * notes list (avoid rendering the same reason twice). Absent for legacy
    * notes (withDefaults → None). */
  kind: Option[String] = None
)

object TaskNote:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskNote] = ConfiguredCodec.derived

/**
 * One entry in a task's change history (C2 event stream).
 * kind: created | status | subject | description | dependency | note
 * detail: human-readable one-liner, e.g. "in_progress→completed" or
 *         "was: <first 120 chars of the old description>".
 */
case class TaskEvent(
  kind: String,
  detail: Option[String] = None,
  at: Option[String] = None
)

object TaskEvent:
  given Configuration = Configuration.default.withDefaults
  given Codec[TaskEvent] = ConfiguredCodec.derived

case class Task(
  id: String,
  subject: String,
  description: String,
  activeForm: Option[String] = None,
  status: TaskStatus = TaskStatus.Pending,
  parentId: Option[String] = None,
  blocks: List[String] = Nil,
  blockedBy: List[String] = Nil,
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  /** When the task entered a terminal state (completed/failed/cancelled).
    * None while active. */
  completedAt: Option[String] = None,
  notes: List[TaskNote] = Nil,
  events: List[TaskEvent] = Nil,
  /** todo-panel v1.1 §2.1: "agent" = agent-tracked (auto flow); "human" =
    * reminder for the user (user completes via todos panel circle). Defaults
    * to "agent" so 535+ legacy JSON files need zero migration (C1). */
  taskKind: String = "agent",
  /** Who completed it: "user" = user clicked the circle; "agent" = agent flow.
    * None = not completed or legacy data. */
  completedBy: Option[String] = None,
  /** todo-panel v2 §2.4 (C17): times the user has returned this task from
    * needs_confirmation back to in_progress. withDefaults keeps 535+ legacy
    * JSON files zero-migration (absent key decodes to 0). */
  returnCount: Int = 0,
  /** task-cancel #35: why the task was cancelled. Set by the user-facing
    * cancel() path (WS cancelTask reason) — the agent path (TaskUpdate
    * status=cancelled) records the reason as a note instead. Absent = never
    * cancelled (withDefaults keeps legacy files zero-migration). */
  cancelReason: Option[String] = None
)

object Task:
  /**
   * Codec with useDefaults — THE compatibility red line (qa R1): 535+ legacy
   * task JSON files on disk predate completedAt/notes/events. Option fields
   * missing from old JSON decode to None under any codec, but the List fields
   * (notes/events, Scala default Nil) make plain semiauto deriveCodec FAIL on
   * a missing key. Configuration.default.withDefaults fills absent fields
   * from their defaults; strictDeserialization stays off so legacy unknown
   * keys (e.g. a stray "metadata") are ignored. Same red line covers the
   * todo-panel fields (taskKind -> "agent", completedBy -> None,
   * returnCount -> 0).
   */
  given Configuration = Configuration.default.withDefaults
  given Codec[Task] = ConfiguredCodec.derived

  /** Normalized taskKind: case-insensitive; anything unrecognized (including
    * null/blank) falls back to "agent" — legacy data and typos must never
    * break the agent/human split, and unknown kinds have no consumer. */
  def normalizeTaskKind(raw: Option[String]): String =
    raw.map(_.trim.toLowerCase).collect { case "human" => "human" }.getOrElse("agent")

  /** todo-panel v2 §6.2c (B7): the [打回任务] injection block appended to the
    * user message when the user returns a needs_confirmation task. Built from
    * the PRE-return snapshot — notes.last is the agent's outcome summary, not
    * the feedback. Fixed copy (agent context, not UI — no i18n, spec §11). */
  def returnInjectionBlock(taskId: String, snapshot: Task, feedback: String): String =
    val output = snapshot.notes.lastOption.map(_.content).getOrElse("（无）")
    val feedbackLine = if feedback.nonEmpty then feedback else "（未附意见）"
    s"[打回任务 #$taskId: ${snapshot.subject}]\n" +
      s"任务描述: ${snapshot.description}\n" +
      s"产出: $output\n" +
      s"用户意见: $feedbackLine\n" +
      "（该任务已回到进行中，请按用户意见修改；改完重新置 needs_confirmation 待用户确认）"

end Task

case class TaskCreateInput(
  subject: String,
  description: String,
  activeForm: Option[String] = None,
  parentTaskId: Option[String] = None,
  /** todo-panel §2.3: pass "human" to create a reminder the USER completes
    * via the todos panel. Absent/blank/unknown -> "agent". */
  taskKind: Option[String] = None
)

object TaskCreateInput:
  given Codec[TaskCreateInput] = deriveCodec

case class TaskUpdateInput(
  subject: Option[String] = None,
  description: Option[String] = None,
  activeForm: Option[String] = None,
  status: Option[TaskStatus] = None,
  addBlocks: Option[List[String]] = None,
  addBlockedBy: Option[List[String]] = None,
  removeBlocks: Option[List[String]] = None,
  removeBlockedBy: Option[List[String]] = None,
  /** Append a durable note to the task (C2). */
  note: Option[String] = None,
  /** Links (paths/URLs/task refs) attached to the appended note. */
  noteLinks: Option[List[String]] = None
)

object TaskUpdateInput:
  given Codec[TaskUpdateInput] = deriveCodec
