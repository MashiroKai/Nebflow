package nebflow.core.task

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.generic.semiauto.deriveCodec

enum TaskStatus:
  case Pending, InProgress, Completed, Failed, Dismissed

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
    case TaskStatus.Completed => "completed"
    case TaskStatus.Failed => "failed"
    case TaskStatus.Dismissed => "dismissed"

  given Codec[TaskStatus] = io.circe.Codec.from(
    io.circe.Decoder.decodeString.emap {
      case "pending" => Right(TaskStatus.Pending)
      case "in_progress" => Right(TaskStatus.InProgress)
      case "completed" => Right(TaskStatus.Completed)
      case "failed" => Right(TaskStatus.Failed)
      case "dismissed" => Right(TaskStatus.Dismissed)
      case other => Left(s"Unknown task status: $other")
    },
    io.circe.Encoder.encodeString.contramap {
      case TaskStatus.Pending => "pending"
      case TaskStatus.InProgress => "in_progress"
      case TaskStatus.Completed => "completed"
      case TaskStatus.Failed => "failed"
      case TaskStatus.Dismissed => "dismissed"
    }
  )

end TaskStatus

/**
 * A note attached to a task — durable outcome/summary text an agent wants to
 * keep for later retrieval (C2). Append-only via TaskUpdate(note=...).
 */
case class TaskNote(
  text: String,
  links: List[String] = Nil,
  at: Option[String] = None
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
  /** When the task entered completed/failed (terminal). None while active. */
  completedAt: Option[String] = None,
  notes: List[TaskNote] = Nil,
  events: List[TaskEvent] = Nil
)

object Task:
  /**
   * Codec with useDefaults — THE compatibility red line (qa R1): 535+ legacy
   * task JSON files on disk predate completedAt/notes/events. Option fields
   * missing from old JSON decode to None under any codec, but the List fields
   * (notes/events, Scala default Nil) make plain semiauto deriveCodec FAIL on
   * a missing key. Configuration.default.withDefaults fills absent fields
   * from their defaults; strictDeserialization stays off so legacy unknown
   * keys (e.g. a stray "metadata") are ignored.
   */
  given Configuration = Configuration.default.withDefaults
  given Codec[Task] = ConfiguredCodec.derived

case class TaskCreateInput(
  subject: String,
  description: String,
  activeForm: Option[String] = None,
  parentTaskId: Option[String] = None
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
