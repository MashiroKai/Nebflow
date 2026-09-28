package nebflow.core.task

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.generic.semiauto.deriveCodec

/** 任务工具重做（2026-08-30 作者规格）：进展展示语义——四态状态机
  * pending → in_progress → completed / failed。needs_confirmation（用户确认
  * 环节）、dismissed、cancelled 全部移除；agent 直接标 completed，无用户
  * 确认/打回/取消环节。历史数据自然 TTL 消化，不迁移（codec 容忍未知键）。 */
enum TaskStatus:
  case Pending, InProgress, Completed, Failed

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

  given Codec[TaskStatus] = io.circe.Codec.from(
    io.circe.Decoder.decodeString.emap {
      case "pending" => Right(TaskStatus.Pending)
      case "in_progress" => Right(TaskStatus.InProgress)
      case "completed" => Right(TaskStatus.Completed)
      case "failed" => Right(TaskStatus.Failed)
      // Legacy statuses from pre-redesign data decode to their nearest living
      // meaning instead of failing the whole file (历史数据自然 TTL 不迁移):
      // needs_confirmation was "done, awaiting user" → completed; dismissed /
      // cancelled were "cleared" → failed (both invisible in the four-state
      // world; the row TTL-expires out of every view).
      case "needs_confirmation" => Right(TaskStatus.Completed)
      case "dismissed" => Right(TaskStatus.Failed)
      case "cancelled" => Right(TaskStatus.Failed)
      case other => Left(s"Unknown task status: $other")
    },
    io.circe.Encoder.encodeString.contramap {
      case TaskStatus.Pending => "pending"
      case TaskStatus.InProgress => "in_progress"
      case TaskStatus.Completed => "completed"
      case TaskStatus.Failed => "failed"
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
  /** 创建时间（TTL 判据——pending/in_progress 超 2d 清理；持久化字段，重启后
    * 扫描磁盘数据仍生效）。 */
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None,
  /** When the task entered a terminal state (completed/failed).
    * None while active. TTL 判据——completed/failed 过 6h 清理。 */
  completedAt: Option[String] = None,
  notes: List[TaskNote] = Nil,
  events: List[TaskEvent] = Nil,
  /** todo-panel v1.1 §2.1: "agent" = agent-tracked (auto flow); "human" =
    * reminder for the user (user completes via todos panel circle). Defaults
    * to "agent" so legacy JSON files need zero migration (C1). 任务工具重做后
    * 无新建 human 通道（存量自然 TTL），complete 圆圈路径保留。 */
  taskKind: String = "agent",
  /** Who completed it: "user" = user clicked the circle; "agent" = agent flow.
    * None = not completed or legacy data. */
  completedBy: Option[String] = None,
  /** Team Manager task tool (2026-08-25, spec
    * 20260825_team-manager-task-tool-spec.md §4.1): "session" = legacy
    * session domain (TTL 自然消化); "team" = team task domain. Absent key on
    * legacy JSON decodes to "session" (withDefaults — zero-migration). */
  scope: String = "session",
  /** Team-domain tasks: the owning team name (scope=="team"). None for
    * session-domain tasks. Directory isolation is the authoritative boundary
    * (~/.nebflow/tasks/teams/<teamName>/); this field is metadata for
    * display/retrieval. */
  teamId: Option[String] = None,
  /** Team-domain tasks: the member responsible for this task (member
    * attribution — 作者规格④ 三级分组 team→成员→任务 的中间键)。
    * TeamTaskCreate 缺省 = 调用工具的成员自身（progress-display 语义：
    * 成员自建自领）；Manager 指派时显式传目标成员名。None = session-domain
    * 或 legacy 数据（零迁移，withDefaults）。 */
  assignee: Option[String] = None
)

object Task:
  /**
   * Codec with useDefaults — THE compatibility red line (qa R1): 535+ legacy
   * task JSON files on disk predate completedAt/notes/events. Option fields
   * missing from old JSON decode to None under any codec, but the List fields
   * (notes/events, Scala default Nil) make plain semiauto deriveCodec FAIL on
   * a missing key. Configuration.default.withDefaults fills absent fields
   * from their defaults; strictDeserialization stays off so legacy unknown
   * keys (returnCount/cancelReason/kind — removed by the task redesign) are
   * ignored, and legacy files keep decoding with zero migration.
   */
  given Configuration = Configuration.default.withDefaults
  given Codec[Task] = ConfiguredCodec.derived

  /** Normalized taskKind: case-insensitive; anything unrecognized (including
    * null/blank) falls back to "agent" — legacy data and typos must never
    * break the agent/human split, and unknown kinds have no consumer. */
  def normalizeTaskKind(raw: Option[String]): String =
    raw.map(_.trim.toLowerCase).collect { case "human" => "human" }.getOrElse("agent")

end Task

case class TaskCreateInput(
  subject: String,
  description: String,
  activeForm: Option[String] = None,
  parentTaskId: Option[String] = None,
  taskKind: Option[String] = None,
  /** Team-domain attribution (2026-08-30): the member this task belongs to.
    * Session-domain callers leave it None. */
  assignee: Option[String] = None
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
  noteLinks: Option[List[String]] = None,
  /** Reassign the responsible member (team-domain attribution, 2026-08-30).
    * Empty/blank string clears the attribution; a non-blank value sets it. */
  assignee: Option[String] = None
)

object TaskUpdateInput:
  given Codec[TaskUpdateInput] = deriveCodec
